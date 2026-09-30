package io.openems.edge.bms.endpoints.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
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
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringModbusMap;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService.AcknowledgeResult;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public class BmsMonitoringJsonApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("e995296a-a645-49fa-9bd6-0a586f1afc41");

	@Test
	public void apiAndModbusShouldExposeTheSameAtomicIdentityMeasurementsAndAlarmMask() throws Exception {
		var frame = frame();
		var api = api(principal(PlatformRole.BMS_VIEWER), frame, new AlarmService());
		var result = invoke(api, BmsMonitoringJsonApi.GET, params("valid"));
		var json = result.getAsJsonObject("frame");
		var registers = BmsMonitoringModbusMap.registers(frame);

		assertEquals(2, result.get("contractVersion").getAsInt());
		assertFalse(json.has("calculationSnapshot"));
		assertEquals(frame.snapshotGeneration().toString(), json.get("snapshotGeneration").getAsString());
		assertEquals(frame.sourceGeneration().toString(), json.get("sourceGeneration").getAsString());
		assertEquals(frame.sourceSequence(), json.get("sourceSequence").getAsLong());
		assertEquals(frame.publicationGeneration().toString(), json.get("publicationGeneration").getAsString());
		assertEquals(frame.publicationRevision(), json.get("publicationRevision").getAsLong());
		assertTrue(json.get("referenceAvailable").getAsBoolean());
		assertEquals(12, json.getAsJsonObject("alarmEvaluations").size());
		assertEquals(u64(registers, 6019), json.get("snapshotSequence").getAsLong());
		assertEquals(u64(registers, 6023), json.get("capturedAtEpochMs").getAsLong());
		assertEquals(u32(registers, 6007), json.get("alarmMask").getAsInt());
		assertEquals(s32(registers, 6101) / 100.0,
				json.getAsJsonObject("measurements").get("batteryVoltageV").getAsDouble(), 0.000001);
		assertEquals(s32(registers, 6105) / 100.0,
				json.getAsJsonObject("measurements").get("batteryCurrentA").getAsDouble(), 0.000001);
		assertEquals(17, json.getAsJsonObject("measurements").size());
		assertEquals(2, json.getAsJsonArray("activeAlarms").size());
		assertEquals(42, json.getAsJsonObject("reference").get("calibrationEventId").getAsLong());
		assertEquals("DISCHARGING", json.getAsJsonObject("operatingMode").get("mode").getAsString());
	}

	@Test
	public void shouldExposeAdditiveStringAndCellEstimatorProvenance() {
		var source = frame();
		var reference = new EstimatorReferenceIdentity(42L, 2L, 100L, 1, "curve-hash");
		var soc = new EstimatorProvenance("COULOMB_COUNT", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		var soh = new EstimatorProvenance("AUTHORITATIVE_BASELINE", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		var originalCell = source.cells().get(7);
		var cell = new BmsMonitoringSnapshot.CellMeasurements(originalCell.cellIndex(), originalCell.voltageV(),
				originalCell.resistanceUohm(), originalCell.temperatureC(), originalCell.socPct(), originalCell.sohPct(),
				originalCell.availabilityWarning(), originalCell.resistanceAlarmThresholdUohm(), soc, soh);
		var enriched = new BmsMonitoringSnapshot(source.stringKey(), source.snapshotGeneration(), source.snapshotSequence(),
				source.capturedAt(), source.currentMeasuredAt(), source.currentQuality(), source.stale(), source.reference(),
				source.string(), Map.of(7, cell), source.thresholds(), source.activeCalibration(), source.operatingMode(),
				source.currentDirectionMapping(), source.activeAlarms(), source.alarmMask(), source.alarmDetails(),
				source.qualityWarning(), source.calculationSnapshot(), source.publicationGeneration(),
				source.publicationRevision(), source.alarmEvaluations(), soc, soh);
		var json = BmsMonitoringJsonApi.frame(enriched);
		assertEquals("COULOMB_COUNT", json.getAsJsonObject("socEstimator").get("method").getAsString());
		assertEquals(42, json.getAsJsonObject("sohEstimator").getAsJsonObject("reference")
				.get("calibrationEventId").getAsLong());
		assertEquals("AUTHORITATIVE", json.getAsJsonArray("cells").get(0).getAsJsonObject()
				.getAsJsonObject("sohEstimator").get("quality").getAsString());
	}

	@Test
	public void shouldReturnExplicitNullFrameButFailForMissingProvider() throws Exception {
		var unavailable = api(principal(PlatformRole.BMS_VIEWER), null, new AlarmService());
		assertEquals(true, invoke(unavailable, BmsMonitoringJsonApi.GET, params("valid")).get("frame").isJsonNull());

		var missing = api(principal(PlatformRole.BMS_VIEWER), null, new AlarmService());
		missing.removeMonitoringProvider(missing.providersForTest().get(0));
		assertTrue(invokeRaw(missing, BmsMonitoringJsonApi.GET, params("valid"))
				.getAsJsonObject("error").get("message").getAsString().contains("No monitoring provider"));
	}

	@Test
	public void shouldAuthenticateBeforeLookupAndAuthorizeAlarmAcknowledgement() throws Exception {
		var denied = api(null, frame(), new AlarmService());
		assertTrue(invokeRaw(denied, BmsMonitoringJsonApi.GET, new JsonObject())
				.getAsJsonObject("error").get("message").getAsString().contains("session"));
		assertTrue(invokeRaw(denied, BmsMonitoringJsonApi.GET, params("invalid"))
				.getAsJsonObject("error").get("message").getAsString().contains("session"));

		var viewer = api(principal(PlatformRole.BMS_VIEWER), frame(), new AlarmService());
		var ack = params("valid");
		ack.addProperty("occurrenceId", 9);
		assertTrue(invokeRaw(viewer, BmsMonitoringJsonApi.ACKNOWLEDGE_ALARM, ack)
				.getAsJsonObject("error").get("message").getAsString().contains("Insufficient role"));

		var alarms = new AlarmService();
		var operator = api(principal(PlatformRole.BMS_OPERATOR), frame(), alarms);
		var result = invoke(operator, BmsMonitoringJsonApi.ACKNOWLEDGE_ALARM, ack);
		assertEquals("ACKNOWLEDGED_ACTIVE", result.get("result").getAsString());
		assertEquals("operator", alarms.actor);
	}

	@Test
	public void shouldRejectAmbiguousOrCrossStringProviders() throws Exception {
		var duplicate = api(principal(PlatformRole.BMS_VIEWER), frame(), new AlarmService());
		duplicate.addProviderForTest(provider(frame()));
		assertTrue(invokeRaw(duplicate, BmsMonitoringJsonApi.GET, params("valid"))
				.getAsJsonObject("error").get("message").getAsString().contains("Multiple monitoring providers"));

		var wrongFrame = copyWithStringKey(frame(), "str2");
		var mismatched = api(principal(PlatformRole.BMS_VIEWER), wrongFrame, new AlarmService());
		assertTrue(invokeRaw(mismatched, BmsMonitoringJsonApi.GET, params("valid"))
				.getAsJsonObject("error").get("message").getAsString().contains("published a frame for str2"));
	}

	@Test
	public void shouldRejectFractionalPaginationAndOccurrenceIdentifiers() throws Exception {
		var alarms = new AlarmService();
		var api = api(principal(PlatformRole.BMS_OPERATOR), frame(), alarms);
		var list = params("valid");
		list.addProperty("limit", 1.5);
		assertTrue(invokeRaw(api, BmsMonitoringJsonApi.LIST_ALARMS, list)
				.getAsJsonObject("error").get("message").getAsString().contains("integer"));

		var ack = params("valid");
		ack.addProperty("occurrenceId", 9.5);
		assertTrue(invokeRaw(api, BmsMonitoringJsonApi.ACKNOWLEDGE_ALARM, ack)
				.getAsJsonObject("error").get("message").getAsString().contains("integer"));
		assertEquals(0, alarms.acknowledgements);
	}

	@Test
	public void shouldExposePersistedAlarmProvenanceAndTransitions() throws Exception {
		var alarms = new AlarmService();
		alarms.values = List.of(occurrence());
		var api = api(principal(PlatformRole.BMS_VIEWER), frame(), alarms);
		var params = params("valid");
		params.addProperty("stringKey", "str1");
		var result = invoke(api, BmsMonitoringJsonApi.LIST_ALARMS, params);
		var alarm = result.getAsJsonArray("alarms").get(0).getAsJsonObject();
		assertEquals("CELL:7", alarm.get("subjectKey").getAsString());
		assertEquals(42, alarm.getAsJsonObject("reference").get("calibrationEventId").getAsLong());
		assertEquals("RAISED", alarm.getAsJsonArray("transitions").get(0).getAsJsonObject()
				.get("type").getAsString());
	}

	@Test
	public void shouldExposeProcessorCheckpointEvenWithNoAlarms() throws Exception {
		var alarms = new AlarmService();
		var generation = UUID.randomUUID();
		alarms.checkpoint = new MonitoringAlarmService.ProcessorCheckpoint(generation, 7L,
				Instant.parse("2026-09-25T01:02:03Z"));
		var api = api(principal(PlatformRole.BMS_VIEWER), frame(), alarms);
		var params = params("valid");
		params.addProperty("stringKey", "str1");
		var result = invoke(api, BmsMonitoringJsonApi.LIST_ALARMS, params);
		assertEquals(0, result.getAsJsonArray("alarms").size());
		var checkpoint = result.getAsJsonObject("processorCheckpoint");
		assertEquals(generation.toString(), checkpoint.get("publicationGeneration").getAsString());
		assertEquals(7L, checkpoint.get("publicationRevision").getAsLong());
		assertEquals("2026-09-25T01:02:03Z", checkpoint.get("processedAt").getAsString());

		params.remove("stringKey");
		result = invoke(api, BmsMonitoringJsonApi.LIST_ALARMS, params);
		checkpoint = result.getAsJsonObject("processorCheckpoint");
		assertTrue(checkpoint.get("publicationGeneration").isJsonNull());
		assertTrue(checkpoint.get("publicationRevision").isJsonNull());
		assertTrue(checkpoint.get("processedAt").isJsonNull());
	}

	private static TestApi api(AuthenticatedPrincipal principal, BmsMonitoringSnapshot frame,
			AlarmService alarms) throws Exception {
		var api = new TestApi();
		setField(api, "authentication", new Authentication(principal));
		setField(api, "alarms", alarms);
		api.addMonitoringProvider(provider(frame));
		return api;
	}

	private static BmsMonitoringSnapshotProvider provider(BmsMonitoringSnapshot frame) {
		return (BmsMonitoringSnapshotProvider) Proxy.newProxyInstance(BmsMonitoringSnapshotProvider.class.getClassLoader(),
				new Class<?>[] { BmsMonitoringSnapshotProvider.class }, (proxy, method, arguments) -> switch (method.getName()) {
				case "stringKey" -> "str1";
				case "latestFrame" -> frame;
				case "id" -> "ctrlBmsMonitoring-str1";
				case "alias" -> "test";
				case "isEnabled" -> true;
				case "equals" -> proxy == arguments[0];
				case "hashCode" -> System.identityHashCode(proxy);
				case "toString" -> "test-monitoring-provider";
				default -> null;
				});
	}

	private static JsonObject invoke(BmsMonitoringJsonApi api, String method, JsonObject params) throws Exception {
		return invokeRaw(api, method, params).getAsJsonObject("result");
	}

	private static JsonObject invokeRaw(BmsMonitoringJsonApi api, String method, JsonObject params) throws Exception {
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, params));
		builder.handle(call);
		return call.getResponse().toJsonObject();
	}

	private static JsonObject params(String token) {
		var params = new JsonObject();
		params.addProperty("token", token);
		params.addProperty("stringKey", "str1");
		return params;
	}

	private static AuthenticatedPrincipal principal(PlatformRole role) {
		return new AuthenticatedPrincipal(ACTOR_ID, "operator", "Operator", Set.of(role));
	}

	private static BmsMonitoringSnapshot frame() {
		var generation = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000");
		var at = Instant.ofEpochMilli(1_789_000_000_123L);
		var values = new BmsMonitoringSnapshot.StringMeasurements(253.25, 126.6, 20.0, 5065.0, null, 2.291,
				2.309, 2.302, 1901, 1800.4, 25.1, 26.0, 25.4, 65.0, 6.5, 87.56, 80.01);
		var reference = new BmsMonitoringSnapshot.ReferenceIdentity(42, 2, 100, 1, 200, 201, "curve-hash");
		var active = new ActiveCalibrationContext(43L, 500L, 600L, "INITIAL_BASELINE", "DISCHARGE",
				"CONTROLLED_DISCHARGE", 40.0);
		var mode = new ModeEvidence(OperatingMode.DISCHARGING, ModeSource.ACTIVE_CALIBRATION, at, generation, 77,
				ModeQuality.VALID, "Active discharge");
		var alarmMask = Alarm.LOW_SOC.mask() | Alarm.CELL_TEMPERATURE.mask();
		return new BmsMonitoringSnapshot("str1", generation, 77, at, at.toEpochMilli(), CurrentQuality.FRESH,
				false, reference, values,
				Map.of(7, new BmsMonitoringSnapshot.CellMeasurements(7, 2.291, 1901.0, 26.0, 64.0, 80.01, null)),
				new BmsMonitoringSnapshot.AppliedThresholds(0.15, 0.15, 200, 242, 1.25, 4, 6, 3, 0.3, 40,
						70, 40, 2), active, mode, BmsCurrentDirectionMapping.POSITIVE,
				Set.of(Alarm.LOW_SOC, Alarm.CELL_TEMPERATURE), alarmMask,
				List.of(new BmsMonitoringSnapshot.AlarmDetail(Alarm.LOW_SOC, null, 65.0, 70.0, "Low SOC")), null);
	}

	private static MonitoringAlarmService.AlarmOccurrence occurrence() {
		var frame = frame();
		var transition = new MonitoringAlarmService.AlarmTransition(2, "RAISED", frame.capturedAt(), null, 2.6,
				2.5, "hot", frame.reference(), frame.snapshotGeneration(), frame.snapshotSequence(),
				frame.capturedAt(), 1);
		return new MonitoringAlarmService.AlarmOccurrence(1, "str1", Alarm.CELL_TEMPERATURE, "CELL:7", 7,
				"ACTIVE", true, frame.capturedAt(), null, null, null, null, 2.6, 2.5, "hot",
				frame.snapshotGeneration(), frame.snapshotSequence(), frame.capturedAt(), frame.reference(),
				frame.activeCalibration(), 1, 3, 3, 1, "ABSOLUTE", true, List.of(transition));
	}

	private static BmsMonitoringSnapshot copyWithStringKey(BmsMonitoringSnapshot frame, String stringKey) {
		return new BmsMonitoringSnapshot(stringKey, frame.snapshotGeneration(), frame.snapshotSequence(),
				frame.capturedAt(), frame.currentMeasuredAt(), frame.currentQuality(), frame.stale(), frame.reference(),
				frame.string(), frame.cells(), frame.thresholds(), frame.activeCalibration(), frame.operatingMode(),
				frame.currentDirectionMapping(), frame.activeAlarms(), frame.alarmMask(), frame.alarmDetails(),
				frame.qualityWarning());
	}

	private static long u64(Map<Integer, Integer> registers, int address) {
		long result = 0;
		for (var i = 0; i < 4; i++) result = (result << 16) | registers.get(address + i);
		return result;
	}

	private static int u32(Map<Integer, Integer> registers, int address) {
		return (registers.get(address) << 16) | registers.get(address + 1);
	}

	private static int s32(Map<Integer, Integer> registers, int address) {
		return (registers.get(address) << 16) | registers.get(address + 1);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		var type = target.getClass();
		while (type != null) {
			try {
				var field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static final class TestApi extends BmsMonitoringJsonApi {
		void addProviderForTest(BmsMonitoringSnapshotProvider provider) {
			this.addMonitoringProvider(provider);
		}

		List<BmsMonitoringSnapshotProvider> providersForTest() throws Exception {
			var field = BmsMonitoringJsonApi.class.getDeclaredField("providers");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			var values = (List<BmsMonitoringSnapshotProvider>) field.get(this);
			return values;
		}
	}

	private record Authentication(AuthenticatedPrincipal principal) implements PlatformAuthenticationService {
		@Override public Optional<Login> authenticate(String username, char[] password) { return Optional.empty(); }
		@Override public boolean isAvailable() { return true; }
		@Override public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return "valid".equals(token) ? Optional.ofNullable(this.principal) : Optional.empty();
		}
		@Override public boolean logout(String token) { return false; }
		@Override public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) {
			return false;
		}
		@Override public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) {
			return Optional.empty();
		}
	}

	private static final class AlarmService implements MonitoringAlarmService {
		private List<AlarmOccurrence> values = List.of();
		private ProcessorCheckpoint checkpoint = ProcessorCheckpoint.unavailable();
		private String actor;
		private int acknowledgements;
		@Override public List<AlarmOccurrence> list(String stringKey, boolean includeCleared, int offset, int limit) {
			return this.values;
		}
		@Override public AlarmListResult listWithCheckpoint(String stringKey, boolean includeCleared, int offset,
				int limit) {
			return new AlarmListResult(this.values,
					stringKey == null ? ProcessorCheckpoint.unavailable() : this.checkpoint);
		}
		@Override public AcknowledgeResult acknowledge(long occurrenceId, String actor, Instant acknowledgedAt)
				throws SQLException {
			this.acknowledgements++;
			this.actor = actor;
			return AcknowledgeResult.ACKNOWLEDGED_ACTIVE;
		}
	}
}
