package io.openems.edge.bms.endpoints.email;

import io.openems.edge.bms.endpoints.email.EmailAlertJsonApi;

import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;

public class EmailAlertJsonApiTest {

	@Test
	public void authorizationAcceptsOnlyPlatformAdministrators() throws Exception {
		assertAuthorized(Set.of(PlatformRole.PLATFORM_ADMIN));
		assertDenied(Set.of(PlatformRole.BMS_OPERATOR));
		assertDenied(null);
	}

	private static void assertAuthorized(Set<PlatformRole> roles) throws Exception {
		invokeAuthorize(api(roles), params());
	}

	private static void assertDenied(Set<PlatformRole> roles) throws Exception {
		var exception = assertThrows(InvocationTargetException.class, () -> invokeAuthorize(api(roles), params()));
		if (!(exception.getCause() instanceof SecurityException)) {
			throw exception;
		}
	}

	private static EmailAlertJsonApi api(Set<PlatformRole> roles) throws ReflectiveOperationException {
		var api = new EmailAlertJsonApi();
		Field field = EmailAlertJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, new Authentication(roles));
		return api;
	}

	private static JsonObject params() {
		var params = new JsonObject();
		params.addProperty("token", "valid-token");
		return params;
	}

	private static void invokeAuthorize(EmailAlertJsonApi api, JsonObject params) throws Exception {
		Method method = EmailAlertJsonApi.class.getDeclaredMethod("authorize", JsonObject.class);
		method.setAccessible(true);
		method.invoke(api, params);
	}

	private static final class Authentication implements PlatformAuthenticationService {
		private final Set<PlatformRole> roles;

		private Authentication(Set<PlatformRole> roles) {
			this.roles = roles;
		}

		@Override
		public Optional<Login> authenticate(String username, char[] password) {
			return Optional.empty();
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			if (this.roles == null) {
				return Optional.empty();
			}
			return Optional.of(new AuthenticatedPrincipal(UUID.randomUUID(), "operator", "BMS Operator", this.roles));
		}

		@Override
		public boolean logout(String token) {
			return false;
		}

		@Override
		public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) {
			return false;
		}

		@Override
		public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) {
			return Optional.empty();
		}
	}
}
