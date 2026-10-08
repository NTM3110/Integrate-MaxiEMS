package io.openems.edge.bms.endpoints.strings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.strings.contracts.StringManagementOperations;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CalculationStatus;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CellData;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;
import io.openems.edge.bms.service.strings.model.BmsStringModels.StringData;
import io.openems.edge.bms.service.strings.model.SocCheckpointPolicy;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public class StringJsonApiTest {

	@Test
	public void viewerCanReadButOnlyOperatorCanMutate() throws Exception {
		var viewerOperations = new Operations();
		var viewer = api(Set.of(PlatformRole.BMS_VIEWER), viewerOperations);
		invokeAuthorize(viewer, PlatformRole.BMS_VIEWER);
		assertDenied(viewer, PlatformRole.BMS_OPERATOR);
		var viewerRoutes = new JsonApiBuilder();
		viewer.buildJsonApiRoutes(viewerRoutes);
		assertTrue(call(viewerRoutes, StringJsonApi.LIST, tokenParams()).has("result"));
		assertTrue(call(viewerRoutes, StringJsonApi.GET_DATA, indexParams()).has("result"));
		assertTrue(call(viewerRoutes, StringJsonApi.READINESS, indexParams()).has("result"));
		assertInsufficientRole(call(viewerRoutes, StringJsonApi.CREATE, mutationParams(false)));
		assertInsufficientRole(call(viewerRoutes, StringJsonApi.UPDATE, mutationParams(true)));
		assertInsufficientRole(call(viewerRoutes, StringJsonApi.DELETE, indexParams()));
		assertEquals(0, viewerOperations.creates);
		assertEquals(0, viewerOperations.updates);
		assertEquals(0, viewerOperations.deletes);

		var operator = api(Set.of(PlatformRole.BMS_VIEWER, PlatformRole.BMS_OPERATOR), new Operations());
		invokeAuthorize(operator, PlatformRole.BMS_VIEWER);
		invokeAuthorize(operator, PlatformRole.BMS_OPERATOR);
		assertDenied(api(null, new Operations()), PlatformRole.BMS_VIEWER);
	}

	private static void assertInsufficientRole(JsonObject response) {
		assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("Insufficient role"));
	}

	@Test
	public void canonicalRoutesPreserveLiveAndReadinessShape() throws Exception {
		var operations = new Operations();
		var api = api(Set.of(PlatformRole.PLATFORM_ADMIN), operations);
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);

		var detail = call(builder, StringJsonApi.GET_DATA, indexParams()).getAsJsonObject("result");
		assertEquals("str1", detail.get("id").getAsString());
		assertEquals("DISABLED", detail.get("socCheckpointPolicy").getAsString());
		assertEquals(126.2, detail.getAsJsonObject("stringData").get("midpointVoltage").getAsDouble(), 0.001);
		assertEquals(7, detail.getAsJsonObject("calculationStatus").get("snapshotSequence").getAsLong());
		assertTrue(detail.get("monitoring").isJsonNull());
		assertEquals("TA warning", detail.getAsJsonArray("cellsData").get(0).getAsJsonObject()
				.get("availabilityWarning").getAsString());
		assertEquals("RETAINED", detail.getAsJsonArray("cellsData").get(0).getAsJsonObject()
				.get("quality").getAsString());
		assertEquals(9L, detail.getAsJsonArray("cellsData").get(0).getAsJsonObject()
				.get("measuredAtEpochMs").getAsLong());
		assertEquals(1L, detail.getAsJsonArray("cellsData").get(0).getAsJsonObject().get("ageMs").getAsLong());
		assertEquals("AUTHORITATIVE_BASELINE", detail.getAsJsonObject("stringData")
				.getAsJsonObject("sohEstimator").get("method").getAsString());
		assertEquals(42, detail.getAsJsonArray("cellsData").get(0).getAsJsonObject()
				.getAsJsonObject("sohEstimator").getAsJsonObject("reference")
				.get("calibrationEventId").getAsLong());

		var readiness = call(builder, StringJsonApi.READINESS, indexParams()).getAsJsonObject("result")
				.getAsJsonObject("trace");
		assertTrue(readiness.get("complete").getAsBoolean());
		assertEquals("UPDATE", readiness.get("operation").getAsString());

		call(builder, StringJsonApi.CREATE, mutationParams(false));
		var update = mutationParams(true);
		update.addProperty("socCheckpointPolicy", "PERSIST_DATABASE");
		call(builder, StringJsonApi.UPDATE, update);
		call(builder, StringJsonApi.LIST, tokenParams());
		call(builder, StringJsonApi.DELETE, indexParams());
		assertEquals(1, operations.creates);
		assertEquals(1, operations.lastCreate.stringIndex());
		assertEquals("str1", operations.lastCreate.stringName());
		assertEquals(1, operations.updates);
		assertEquals(SocCheckpointPolicy.PERSIST_DATABASE, operations.lastUpdate.socCheckpointPolicy());
		assertEquals(1, operations.deletes);
	}

	@Test
	public void createRequiresExplicitPositiveStringIndexAndIgnoresOperatorDisplayName() throws Exception {
		var operations = new Operations();
		var api = api(Set.of(PlatformRole.PLATFORM_ADMIN), operations);
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);

		var missing = mutationParams(false);
		missing.remove("stringIndex");
		assertErrorContains(call(builder, StringJsonApi.CREATE, missing), "Missing stringIndex");

		var zero = mutationParams(false);
		zero.addProperty("stringIndex", 0);
		assertErrorContains(call(builder, StringJsonApi.CREATE, zero), "positive whole number");

		var fractional = mutationParams(false);
		fractional.addProperty("stringIndex", 1.5);
		assertErrorContains(call(builder, StringJsonApi.CREATE, fractional), "positive whole number");

		var valid = mutationParams(false);
		valid.addProperty("stringIndex", 7);
		valid.addProperty("stringName", "operator supplied name");
		call(builder, StringJsonApi.CREATE, valid);
		assertEquals(7, operations.lastCreate.stringIndex());
		assertEquals("str7", operations.lastCreate.stringName());
	}

	private static void assertErrorContains(JsonObject response, String expected) {
		assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains(expected));
	}

	@Test
	public void getDataMonitoringProjectionKeepsTheCapturedFrameIdentity() throws Exception {
		var source = detail();
		var generation = UUID.randomUUID();
		var frame = new BmsMonitoringSnapshot("str1", generation, 7, Instant.ofEpochMilli(10), 10L,
				CurrentQuality.FRESH, false, null,
				new BmsMonitoringSnapshot.StringMeasurements(253d, 126.2, null, null, null, null,
						null, null, null, null, null, null, null, null, null, null, null),
				Map.of(), null, null, null, BmsCurrentDirectionMapping.POSITIVE, Set.of(), 0,
				List.of(), null);
		var withFrame = new Detail(source.definition(), source.active(), source.statusMessage(),
				source.stringData(), source.cellsData(), source.calculationStatus(), frame);
		var mapper = StringJsonApi.class.getDeclaredMethod("detail", Detail.class);
		mapper.setAccessible(true);
		var json = (JsonObject) mapper.invoke(null, withFrame);
		var monitoring = json.getAsJsonObject("monitoring");
		assertEquals(7L, monitoring.get("sourceSequence").getAsLong());
		assertEquals(generation.toString(), monitoring.get("sourceGeneration").getAsString());
		assertEquals(7L, json.getAsJsonObject("calculationStatus").get("snapshotSequence").getAsLong());
		assertEquals(253d, json.getAsJsonObject("stringData").get("stringVoltage").getAsDouble(), 0.001);
		assertTrue(!monitoring.has("measurements"));
		assertTrue(!monitoring.has("cells"));
		assertTrue(!monitoring.has("calculationSnapshot"));
	}

	private static JsonObject call(JsonApiBuilder builder, String method, JsonObject params) throws Exception {
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, params));
		builder.handle(call);
		return call.getResponse().toJsonObject();
	}

	private static StringJsonApi api(Set<PlatformRole> roles, StringManagementOperations operations) throws Exception {
		var api = new StringJsonApi();
		set(api, "authentication", new Authentication(roles));
		set(api, "strings", operations);
		return api;
	}

	private static void assertDenied(StringJsonApi api, PlatformRole role) throws Exception {
		var exception = assertThrows(InvocationTargetException.class, () -> invokeAuthorize(api, role));
		assertTrue(exception.getCause() instanceof SecurityException);
	}

	private static void invokeAuthorize(StringJsonApi api, PlatformRole role) throws Exception {
		Method method = StringJsonApi.class.getDeclaredMethod("authorize", JsonObject.class, PlatformRole.class);
		method.setAccessible(true);
		method.invoke(api, tokenParams(), role);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static JsonObject tokenParams() {
		var params = new JsonObject();
		params.addProperty("token", "valid");
		return params;
	}

	private static JsonObject indexParams() {
		var params = tokenParams();
		params.addProperty("stringIndex", 1);
		return params;
	}

	private static JsonObject mutationParams(boolean update) {
		var params = indexParams();
		if (update) params.addProperty("stringName", "String 1");
		params.addProperty("cellQty", 1);
		params.addProperty("serialPortId", "modbus0");
		return params;
	}

	private static Definition definition() {
		return new Definition(1, "String 1", 1, "Huasu", "TA", 200, 1.8, 2.3, 2.45, 1800,
				2200, 40, 1.8, 2.5, "modbus0", 1);
	}

	private static Detail detail() {
		var reference = new EstimatorReferenceIdentity(42L, 2L, 100L, 1, "curve-hash");
		var soc = new EstimatorProvenance("COULOMB_COUNT", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		var soh = new EstimatorProvenance("AUTHORITATIVE_BASELINE", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		return new Detail(definition(), true, "ACTIVE",
				new StringData(25d, -0.15, 99d, 90d, 253d, 126.2, 2.3, 25d, 1800d, 2.3, 1,
						2.3, 1, 25d, 1, 25d, 1, 1800, 1, 1800, 1, soc, soh),
				List.of(new CellData(1, 2.3, 25d, 1800, 99d, 90d, 0.004, "TA warning", soc, soh,
						9L, 1L, "RETAINED")),
				new CalculationStatus(true, 1, 1, 1.8, 2.3, 1800, null, 7L, 10L, 0, false,
						5L, List.of(), null));
	}

	private static final class Operations implements StringManagementOperations {
		private int creates;
		private int updates;
		private int deletes;
		private Definition lastUpdate;
		private Definition lastCreate;

		@Override public Detail create(Definition request) { this.creates++; this.lastCreate = request; return detail(); }
		@Override public Detail update(Definition request) { this.updates++; this.lastUpdate = request; return detail(); }
		@Override public List<Definition> list() { return List.of(definition()); }
		@Override public Detail getData(int stringIndex) { return detail(); }
		@Override public Readiness readiness(int stringIndex) {
			return new Readiness("op", 1, 2, "UPDATE", 1, "modbus0", 1, 2L, 3L, 4L, 5L, 6L, 7L,
					true, List.of(1), List.of(), List.of(), "SUCCESS", 0, 0, 0);
		}
		@Override public void reconcile() { }
		@Override public void delete(int stringIndex) { this.deletes++; }
	}

	private static final class Authentication implements PlatformAuthenticationService {
		private final Set<PlatformRole> roles;
		private Authentication(Set<PlatformRole> roles) { this.roles = roles; }
		@Override public Optional<Login> authenticate(String username, char[] password) { return Optional.empty(); }
		@Override public boolean isAvailable() { return true; }
		@Override public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return this.roles == null ? Optional.empty()
					: Optional.of(new AuthenticatedPrincipal(UUID.randomUUID(), "user", "BMS User", this.roles));
		}
		@Override public boolean logout(String token) { return false; }
		@Override public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) { return false; }
		@Override public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) { return Optional.empty(); }
	}
}
