package io.openems.edge.bms.service.auth.contracts;

import java.time.Instant;
import java.util.Optional;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;

public interface PlatformAuthenticationService {

	record Login(String token, AuthenticatedPrincipal principal, Instant expiresAt) {
	}

	Optional<Login> authenticate(String username, char[] password);

	boolean isAvailable();

	Optional<AuthenticatedPrincipal> authenticateToken(String token);

	boolean logout(String token);

	boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword);

	Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name);
}
