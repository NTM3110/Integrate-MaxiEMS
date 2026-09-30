package io.openems.edge.bms.endpoints.discharge;

import io.openems.edge.bms.endpoints.discharge.DischargeJsonApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

public class DischargeJsonApiTest {

	@Test
	public void readsAllowAuthenticatedOperatorsButMutationsRequirePlatformAdmin() throws Exception {
		var operatorApi = api(Set.of(PlatformRole.BMS_OPERATOR));
		assertEquals("operator", invokeAuthenticate(operatorApi, params()).username());
		assertDeniedMutation(operatorApi);

		invokeAuthorizeMutation(api(Set.of(PlatformRole.PLATFORM_ADMIN)), params());
		assertDeniedMutation(api(null));
	}

	private static void assertDeniedMutation(DischargeJsonApi api) throws Exception {
		var exception = assertThrows(InvocationTargetException.class, () -> invokeAuthorizeMutation(api, params()));
		assertFalse(exception.getCause() == null);
		assertTrue(exception.getCause() instanceof SecurityException);
	}

	private static DischargeJsonApi api(Set<PlatformRole> roles) throws ReflectiveOperationException {
		var api = new DischargeJsonApi();
		Field field = DischargeJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, new Authentication(roles));
		return api;
	}

	private static JsonObject params() {
		var params = new JsonObject();
		params.addProperty("token", "valid-token");
		return params;
	}

	private static AuthenticatedPrincipal invokeAuthenticate(DischargeJsonApi api, JsonObject params) throws Exception {
		Method method = DischargeJsonApi.class.getDeclaredMethod("authenticate", JsonObject.class);
		method.setAccessible(true);
		return (AuthenticatedPrincipal) method.invoke(api, params);
	}

	private static void invokeAuthorizeMutation(DischargeJsonApi api, JsonObject params) throws Exception {
		Method method = DischargeJsonApi.class.getDeclaredMethod("authorizeMutation", JsonObject.class);
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
			return this.roles == null ? Optional.empty()
					: Optional.of(new AuthenticatedPrincipal(UUID.randomUUID(), "operator", "BMS Operator", this.roles));
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
