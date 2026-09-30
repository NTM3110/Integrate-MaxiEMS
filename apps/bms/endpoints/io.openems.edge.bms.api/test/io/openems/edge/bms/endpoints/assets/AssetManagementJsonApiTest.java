package io.openems.edge.bms.endpoints.assets;

import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;

public class AssetManagementJsonApiTest {
	@Test
	public void writeRequiresValidAdminSession() throws Exception {
		invoke(Set.of(PlatformRole.PLATFORM_ADMIN), PlatformRole.PLATFORM_ADMIN);
		assertThrows(SecurityException.class, () -> invoke(Set.of(PlatformRole.BMS_VIEWER), PlatformRole.PLATFORM_ADMIN));
		assertThrows(SecurityException.class, () -> invoke(null, PlatformRole.PLATFORM_ADMIN));
	}

	private static void invoke(Set<PlatformRole> roles, PlatformRole required) throws Exception {
		var api = new AssetManagementJsonApi();
		var field = AssetManagementJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, new Authentication(roles));
		var method = AssetManagementJsonApi.class.getDeclaredMethod("authorize", JsonObject.class, PlatformRole.class);
		method.setAccessible(true);
		var params = new JsonObject();
		params.addProperty("token", "token");
		try {
			method.invoke(api, params, required);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof SecurityException security) throw security;
			throw e;
		}
	}

	private record Authentication(Set<PlatformRole> roles) implements PlatformAuthenticationService {
		@Override public Optional<Login> authenticate(String username, char[] password) { return Optional.empty(); }
		@Override public boolean isAvailable() { return true; }
		@Override public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return this.roles == null ? Optional.empty() : Optional.of(
					new AuthenticatedPrincipal(UUID.randomUUID(), "user", "User", this.roles));
		}
		@Override public boolean logout(String token) { return false; }
		@Override public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) {
			return false;
		}
		@Override public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) {
			return Optional.empty();
		}
	}
}
