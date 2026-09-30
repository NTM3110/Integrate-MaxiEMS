package io.openems.edge.bms.service.auth.implement.platform;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;

import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

@Component(name = "io.openems.edge.bms.platform.auth.PlatformAuthenticationServiceImpl", service = PlatformAuthenticationService.class, immediate = true)
@Designate(ocd = Config.class)
public class PlatformAuthenticationServiceImpl implements PlatformAuthenticationService {

	private static final int SALT_BYTES = 16;
	private static final int HASH_BYTES = 32;
	private static final int PBKDF2_ITERATIONS = 600_000;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Logger log = LoggerFactory.getLogger(PlatformAuthenticationServiceImpl.class);

	@Reference
	private BmsDatabase database;

	private volatile Duration sessionLifetime = Duration.ofHours(8);
	private volatile int minimumPasswordLength = 12;

	@Activate
	@Modified
	protected void activate(Config config) {
		this.sessionLifetime = Duration.ofHours(Math.max(1, config.sessionLifetimeHours()));
		this.minimumPasswordLength = Math.max(12, config.minimumPasswordLength());
		if (!config.bootstrapAdminPassword().isBlank()) {
			this.bootstrapAdministrator(config.bootstrapAdminUsername(), config.bootstrapAdminName(),
					config.bootstrapAdminPassword().toCharArray());
		}
		if (config.importLegacyOpenmucAdministrator() && !this.hasUsers()) {
			this.importLegacyOpenmucAdministrator(config.legacyOpenmucAccountUrl());
		}
	}

	@Override
	public Optional<Login> authenticate(String username, char[] password) {
		if (!this.database.isAvailable() || username == null || password == null) {
			return Optional.empty();
		}
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("SELECT id, username, display_name, password_hash, salt, enabled FROM bms.auth_user WHERE username = ?")) {
			statement.setString(1, username.trim());
			try (var result = statement.executeQuery()) {
				if (!result.next() || !result.getBoolean("enabled")) {
					this.audit(connection, null, "LOGIN_FAILED", username);
					return Optional.empty();
				}
				var expected = result.getBytes("password_hash");
				var actual = hash(password, result.getBytes("salt"));
				try {
					if (!MessageDigest.isEqual(expected, actual)) {
						this.audit(connection, result.getObject("id", UUID.class), "LOGIN_FAILED", username);
						return Optional.empty();
					}
				} finally {
					Arrays.fill(actual, (byte) 0);
				}

				var principal = new AuthenticatedPrincipal(result.getObject("id", UUID.class), result.getString("username"),
						result.getString("display_name"), this.roles(connection, result.getObject("id", UUID.class)));
				var token = newToken();
				var expiresAt = Instant.now().plus(this.sessionLifetime);
				this.createSession(connection, principal.id(), token, expiresAt);
				this.audit(connection, principal.id(), "LOGIN_SUCCEEDED", null);
				return Optional.of(new Login(token, principal, expiresAt));
			}
		} catch (SQLException e) {
			this.log.warn("Platform authentication database operation failed", e);
			return Optional.empty();
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	@Override
	public boolean isAvailable() {
		return this.database.isAvailable();
	}

	@Override
	public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
		if (!this.database.isAvailable() || token == null || token.isBlank()) {
			return Optional.empty();
		}
		var tokenHash = sha256(token);
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("""
						SELECT u.id, u.username, u.display_name FROM bms.auth_session s
						JOIN bms.auth_user u ON u.id = s.user_id
						WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now() AND u.enabled = TRUE
						""")) {
			statement.setBytes(1, tokenHash);
			try (var result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				var id = result.getObject("id", UUID.class);
				try (var update = connection.prepareStatement("UPDATE bms.auth_session SET last_used_at = now() WHERE token_hash = ?")) {
					update.setBytes(1, tokenHash);
					update.executeUpdate();
				}
				return Optional.of(new AuthenticatedPrincipal(id, result.getString("username"),
						result.getString("display_name"), this.roles(connection, id)));
			}
		} catch (SQLException e) {
			this.log.warn("Platform session validation failed", e);
			return Optional.empty();
		} finally {
			Arrays.fill(tokenHash, (byte) 0);
		}
	}

	@Override
	public boolean logout(String token) {
		if (!this.database.isAvailable() || token == null || token.isBlank()) {
			return false;
		}
		var tokenHash = sha256(token);
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("UPDATE bms.auth_session SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL")) {
			statement.setBytes(1, tokenHash);
			return statement.executeUpdate() == 1;
		} catch (SQLException e) {
			this.log.warn("Platform logout failed", e);
			return false;
		} finally {
			Arrays.fill(tokenHash, (byte) 0);
		}
	}

	@Override
	public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) {
		if (!this.database.isAvailable() || principal == null || newPassword == null || newPassword.length < this.minimumPasswordLength) {
			return false;
		}
		try (var connection = this.database.getConnection();
				var select = connection.prepareStatement("SELECT password_hash, salt FROM bms.auth_user WHERE id = ? AND enabled = TRUE")) {
			select.setObject(1, principal.id());
			try (var result = select.executeQuery()) {
				if (!result.next() || !MessageDigest.isEqual(result.getBytes("password_hash"), hash(oldPassword, result.getBytes("salt")))) {
					this.audit(connection, principal.id(), "PASSWORD_CHANGE_FAILED", null);
					return false;
				}
			}
			var salt = newSalt();
			var hash = hash(newPassword, salt);
			try (var update = connection.prepareStatement("UPDATE bms.auth_user SET password_hash = ?, salt = ?, updated_at = now(), password_changed_at = now() WHERE id = ?")) {
				update.setBytes(1, hash);
				update.setBytes(2, salt);
				update.setObject(3, principal.id());
				update.executeUpdate();
			}
			try (var revoke = connection.prepareStatement("UPDATE bms.auth_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL")) {
				revoke.setObject(1, principal.id());
				revoke.executeUpdate();
			}
			this.audit(connection, principal.id(), "PASSWORD_CHANGED", null);
			Arrays.fill(hash, (byte) 0);
			Arrays.fill(salt, (byte) 0);
			return true;
		} catch (SQLException e) {
			this.log.warn("Platform password change failed", e);
			return false;
		} finally {
			if (oldPassword != null) Arrays.fill(oldPassword, '\0');
			Arrays.fill(newPassword, '\0');
		}
	}

	@Override
	public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) {
		if (!this.database.isAvailable() || principal == null || name == null) {
			return Optional.empty();
		}
		var normalized = name.trim();
		if (normalized.isEmpty() || normalized.length() > 160) {
			return Optional.empty();
		}
		try (var connection = this.database.getConnection();
				var update = connection.prepareStatement("""
					UPDATE bms.auth_user SET display_name=?,updated_at=now() WHERE id=? AND enabled=TRUE
					""")) {
			update.setString(1, normalized);
			update.setObject(2, principal.id());
			if (update.executeUpdate() != 1) {
				return Optional.empty();
			}
			this.audit(connection, principal.id(), "ACCOUNT_NAME_CHANGED", null);
			return Optional.of(new AuthenticatedPrincipal(principal.id(), principal.username(), normalized,
					principal.roles()));
		} catch (SQLException e) {
			this.log.warn("Platform account name change failed", e);
			return Optional.empty();
		}
	}

	private boolean bootstrapAdministrator(String username, String name, char[] password) {
		// Bootstrap credentials are explicitly configured for first startup. They may
		// be weaker than the normal change-password policy and should be changed
		// immediately after the first login.
		return this.createInitialAdministrator(username, name, password, false);
	}

	private boolean createInitialAdministrator(String username, String name, char[] password,
			boolean enforceMinimumPasswordLength) {
		if (!this.database.isAvailable() || username == null || username.isBlank()
				|| name == null || name.isBlank() || name.trim().length() > 160
				|| (enforceMinimumPasswordLength && password.length < this.minimumPasswordLength)) {
			return false;
		}
		try (var connection = this.database.getConnection(); var count = connection.createStatement().executeQuery("SELECT count(*) FROM bms.auth_user")) {
			if (!count.next() || count.getLong(1) != 0) {
				return false;
			}
			var id = UUID.randomUUID();
			var salt = newSalt();
			var hash = hash(password, salt);
			try (var insert = connection.prepareStatement("INSERT INTO bms.auth_user(id, username, display_name, password_hash, salt) VALUES (?, ?, ?, ?, ?)");
					var role = connection.prepareStatement("INSERT INTO bms.auth_user_role(user_id, role) VALUES (?, ?)")) {
				insert.setObject(1, id); insert.setString(2, username); insert.setString(3, name.trim());
				insert.setBytes(4, hash); insert.setBytes(5, salt); insert.executeUpdate();
				role.setObject(1, id); role.setString(2, PlatformRole.PLATFORM_ADMIN.name()); role.executeUpdate();
			}
			Arrays.fill(hash, (byte) 0); Arrays.fill(salt, (byte) 0);
			this.log.info("Created the configured bootstrap MaxiEMS platform administrator [{}].", username);
			return true;
		} catch (SQLException e) {
			this.log.warn("Unable to create bootstrap platform administrator", e);
			return false;
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Imports the single legacy BMS administrator only before any MaxiEMS user
	 * exists. The legacy password is never returned by this bundle or written to
	 * the MaxiEMS database in clear text.
	 */
	private void importLegacyOpenmucAdministrator(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			this.log.warn("Legacy OpenMUC account import is enabled but no endpoint was configured.");
			return;
		}
		try {
			var connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5_000);
			connection.setReadTimeout(5_000);
			connection.setRequestProperty("Accept", "application/json");
			try {
				if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
					this.log.warn("Legacy OpenMUC account import returned HTTP {}.", connection.getResponseCode());
					return;
				}
				var response = JsonParser.parseString(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				if (!response.has("success") || !response.get("success").getAsBoolean() || !response.has("data") || response.get("data").isJsonNull()) {
					this.log.warn("Legacy OpenMUC account import did not return an account.");
					return;
				}
				var account = response.getAsJsonObject("data");
				var username = account.has("username") ? account.get("username").getAsString().trim() : "";
				var password = account.has("password") ? account.get("password").getAsString().toCharArray() : new char[0];
				try {
					if (username.isBlank() || password.length == 0) {
						this.log.warn("Legacy OpenMUC account was not imported: username or password is blank.");
						return;
					}
					if (this.createInitialAdministrator(username, username, password, false)) {
						this.log.info("Imported the legacy OpenMUC BMS administrator [{}] into MaxiEMS platform authentication.", username);
					}
				} finally {
					Arrays.fill(password, '\0');
				}
			} finally {
				connection.disconnect();
			}
		} catch (Exception e) {
			this.log.warn("Unable to import the legacy OpenMUC BMS administrator", e);
		}
	}

	private boolean hasUsers() {
		if (!this.database.isAvailable()) return false;
		try (var connection = this.database.getConnection(); var result = connection.createStatement().executeQuery("SELECT 1 FROM bms.auth_user LIMIT 1")) {
			return result.next();
		} catch (SQLException e) {
			this.log.warn("Unable to check for existing platform users", e);
			return true;
		}
	}

	private Set<PlatformRole> roles(java.sql.Connection connection, UUID userId) throws SQLException {
		var roles = EnumSet.noneOf(PlatformRole.class);
		try (var statement = connection.prepareStatement("SELECT role FROM bms.auth_user_role WHERE user_id = ?")) {
			statement.setObject(1, userId);
			try (var result = statement.executeQuery()) {
				while (result.next()) {
					try { roles.add(PlatformRole.valueOf(result.getString(1))); } catch (IllegalArgumentException ignored) { }
				}
			}
		}
		return Set.copyOf(roles);
	}

	private void createSession(java.sql.Connection connection, UUID userId, String token, Instant expiresAt) throws SQLException {
		var hash = sha256(token);
		try (var statement = connection.prepareStatement("INSERT INTO bms.auth_session(token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
			statement.setBytes(1, hash); statement.setObject(2, userId); statement.setTimestamp(3, Timestamp.from(expiresAt)); statement.executeUpdate();
		} finally { Arrays.fill(hash, (byte) 0); }
	}

	private void audit(java.sql.Connection connection, UUID userId, String event, String detail) throws SQLException {
		try (var statement = connection.prepareStatement("INSERT INTO bms.auth_audit(user_id, event_type, detail) VALUES (?, ?, ?)")) {
			statement.setObject(1, userId); statement.setString(2, event); statement.setString(3, detail); statement.executeUpdate();
		}
	}

	private static byte[] newSalt() { var salt = new byte[SALT_BYTES]; RANDOM.nextBytes(salt); return salt; }
	private static String newToken() { var token = new byte[32]; RANDOM.nextBytes(token); return Base64.getUrlEncoder().withoutPadding().encodeToString(token); }
	private static byte[] sha256(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
	private static byte[] hash(char[] password, byte[] salt) {
		try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BYTES * 8)).getEncoded(); }
		catch (Exception e) { throw new IllegalStateException("Unable to hash password", e); }
	}
}
