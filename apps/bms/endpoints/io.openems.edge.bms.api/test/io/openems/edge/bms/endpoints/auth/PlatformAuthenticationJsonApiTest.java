package io.openems.edge.bms.endpoints.auth;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.endpoints.auth.PlatformAuthenticationJsonApi;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public class PlatformAuthenticationJsonApiTest {

	@Test
	public void readyRouteReturnsOnlyAvailabilityWithoutParameters() throws Exception {
		assertReadyResponse(true);
		assertReadyResponse(false);
	}

	@Test
	public void sessionAndAccountNameResponsesUseRequiredDisplayName() throws Exception {
		var principal = new AuthenticatedPrincipal(UUID.randomUUID(), "operator", "Nguyen Van A",
				Set.of(PlatformRole.BMS_OPERATOR));
		var api = new PlatformAuthenticationJsonApi();
		setAuthentication(api, new AvailableAuthenticationService(true, principal));
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);

		var tokenParams = new JsonObject();
		tokenParams.addProperty("token", "valid");
		var tokenCall = new Call<JsonrpcRequest, JsonrpcResponse>(
				new GenericJsonrpcRequest(PlatformAuthenticationJsonApi.AUTHENTICATE_TOKEN, tokenParams));
		builder.handle(tokenCall);
		assertEquals("Nguyen Van A",
				tokenCall.getResponse().toJsonObject().getAsJsonObject("result").get("name").getAsString());

		var updateParams = tokenParams.deepCopy();
		updateParams.addProperty("name", "Tran Thi B");
		var updateCall = new Call<JsonrpcRequest, JsonrpcResponse>(
				new GenericJsonrpcRequest(PlatformAuthenticationJsonApi.UPDATE_ACCOUNT_NAME, updateParams));
		builder.handle(updateCall);
		assertEquals("Tran Thi B",
				updateCall.getResponse().toJsonObject().getAsJsonObject("result").get("name").getAsString());
	}

	private static void assertReadyResponse(boolean available) throws Exception {
		var api = new PlatformAuthenticationJsonApi();
		setAuthentication(api, new AvailableAuthenticationService(available));
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);

		var call = new Call<JsonrpcRequest, JsonrpcResponse>(
				new GenericJsonrpcRequest(PlatformAuthenticationJsonApi.READY, new JsonObject()));
		builder.handle(call);

		var result = call.getResponse().toJsonObject().getAsJsonObject("result");
		assertEquals(1, result.size());
		assertEquals(available, result.get("ready").getAsBoolean());
	}

	private static void setAuthentication(PlatformAuthenticationJsonApi api, PlatformAuthenticationService authentication)
			throws ReflectiveOperationException {
		Field field = PlatformAuthenticationJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, authentication);
	}

	private static final class AvailableAuthenticationService implements PlatformAuthenticationService {
		private final boolean available;
		private final AuthenticatedPrincipal principal;

		private AvailableAuthenticationService(boolean available) {
			this(available, null);
		}

		private AvailableAuthenticationService(boolean available, AuthenticatedPrincipal principal) {
			this.available = available;
			this.principal = principal;
		}

		@Override
		public Optional<Login> authenticate(String username, char[] password) {
			return Optional.empty();
		}

		@Override
		public boolean isAvailable() {
			return this.available;
		}

		@Override
		public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return "valid".equals(token) ? Optional.ofNullable(this.principal) : Optional.empty();
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
			return Optional.of(new AuthenticatedPrincipal(principal.id(), principal.username(), name.trim(),
					principal.roles()));
		}
	}
}
