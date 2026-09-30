package io.openems.edge.bms.endpoints.strings;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.strings.contracts.StringManagementOperations;
import io.openems.edge.bms.service.strings.contracts.StringManagementOperations.Readiness;
import io.openems.edge.bms.endpoints.monitoring.BmsMonitoringJsonApi;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CalculationStatus;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CellData;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;
import io.openems.edge.bms.service.strings.model.BmsStringModels.StringData;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Canonical authenticated API for string lifecycle, readiness and live data. */
@Component(name = "io.openems.edge.bms.platform.strings.StringJsonApi", property = "entry=edge.websocket.root")
public class StringJsonApi implements JsonApi {
	public static final String CREATE = "bms.strings.create";
	public static final String UPDATE = "bms.strings.update";
	public static final String LIST = "bms.strings.list";
	public static final String GET_DATA = "bms.strings.getData";
	public static final String DELETE = "bms.strings.delete";
	public static final String READINESS = "bms.strings.readiness";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private StringManagementOperations strings;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(CREATE, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_OPERATOR);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					detail(this.strings.create(request(call.getRequest().getParams()))));
		});
		builder.handleRequest(UPDATE, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_OPERATOR);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					detail(this.strings.update(request(call.getRequest().getParams()))));
		});
		builder.handleRequest(LIST, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_VIEWER);
			var array = new JsonArray();
			this.strings.list().forEach(value -> array.add(definition(value)));
			var response = new JsonObject();
			response.add("strings", array);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(GET_DATA, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_VIEWER);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					detail(this.strings.getData(requiredInt(call.getRequest().getParams(), "stringIndex"))));
		});
		builder.handleRequest(DELETE, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_OPERATOR);
			this.strings.delete(requiredInt(call.getRequest().getParams(), "stringIndex"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest(READINESS, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_VIEWER);
			var trace = this.strings.readiness(requiredInt(call.getRequest().getParams(), "stringIndex"));
			var response = new JsonObject();
			response.add("trace", trace == null ? JsonNull.INSTANCE : readiness(trace));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
	}

	private void authorize(JsonObject params, PlatformRole role) {
		var principal = this.authentication.authenticateToken(required(params, "token"))
				.orElseThrow(() -> new SecurityException("Session is invalid or expired"));
		if (!principal.hasRole(role)) throw new SecurityException("Insufficient role for BMS operation");
	}

	private static Definition request(JsonObject params) {
		return new Definition(optionalInt(params, "stringIndex", 0), required(params, "stringName"),
				requiredInt(params, "cellQty"), optional(params, "cellBrand"), optional(params, "cellModel"),
				number(params, "ratedCapacity", 0), number(params, "cutoffVoltage", 0),
				number(params, "floatVoltage", 0), number(params, "boostVoltage", 0), number(params, "rNew", 1450),
				number(params, "highResistanceThreshold", 0), number(params, "highTemperatureThreshold", 0),
				number(params, "lowVoltageThreshold", 0), number(params, "highVoltageThreshold", 0),
				required(params, "serialPortId"), optionalInt(params, "startTaUnitId", 1),
				io.openems.edge.bms.service.strings.model.SocCheckpointPolicy.parse(optional(params, "socCheckpointPolicy")));
	}

	private static JsonObject detail(Detail value) {
		var json = definition(value.definition());
		json.addProperty("active", value.active());
		json.addProperty("statusMessage", value.statusMessage());
		json.add("stringData", stringData(value.stringData()));
		var cells = new JsonArray();
		value.cellsData().forEach(cell -> cells.add(cell(cell)));
		json.add("cellsData", cells);
		json.add("calculationStatus", calculationStatus(value.calculationStatus()));
		if (value.monitoring() == null) {
			json.add("monitoring", JsonNull.INSTANCE);
		} else {
			// getData already exposes every cell and string measurement above. Keep the
			// operational metadata without serializing 110 cell rows twice.
			var monitoring = BmsMonitoringJsonApi.frame(value.monitoring());
			monitoring.remove("measurements");
			monitoring.remove("cells");
			json.add("monitoring", monitoring);
		}
		return json;
	}

	private static JsonObject definition(Definition value) {
		var json = new JsonObject();
		json.addProperty("id", value.id());
		json.addProperty("stringIndex", value.stringIndex());
		json.addProperty("stringName", value.stringName());
		json.addProperty("cellQty", value.cellQty());
		json.addProperty("cellBrand", value.cellBrand());
		json.addProperty("cellModel", value.cellModel());
		json.addProperty("ratedCapacity", value.ratedCapacity());
		json.addProperty("cutoffVoltage", value.cutoffVoltage());
		json.addProperty("floatVoltage", value.floatVoltage());
		json.addProperty("boostVoltage", value.boostVoltage());
		json.addProperty("rNew", value.rNew());
		json.addProperty("highResistanceThreshold", value.highResistanceThreshold());
		json.addProperty("highTemperatureThreshold", value.highTemperatureThreshold());
		json.addProperty("lowVoltageThreshold", value.lowVoltageThreshold());
		json.addProperty("highVoltageThreshold", value.highVoltageThreshold());
		json.addProperty("serialPortId", value.bridgeId());
		json.addProperty("startTaUnitId", value.startTaUnitId());
		json.addProperty("socCheckpointPolicy", value.socCheckpointPolicy().name());
		return json;
	}

	private static JsonObject stringData(StringData value) {
		var json = new JsonObject();
		add(json, "ambientTemperature", value.ambientTemperature()); add(json, "stringCurrent", value.stringCurrent());
		add(json, "stringSoC", value.stringSoC()); add(json, "stringSoH", value.stringSoH());
		add(json, "stringVoltage", value.stringVoltage()); add(json, "midpointVoltage", value.midpointVoltage());
		add(json, "avgVoltage", value.avgVoltage()); add(json, "avgTemperature", value.avgTemperature());
		add(json, "avgResistance", value.avgResistance()); add(json, "maxVoltage", value.maxVoltage());
		add(json, "maxVoltageCellIndex", value.maxVoltageCellIndex()); add(json, "minVoltage", value.minVoltage());
		add(json, "minVoltageCellIndex", value.minVoltageCellIndex()); add(json, "maxTemperature", value.maxTemperature());
		add(json, "maxTemperatureCellIndex", value.maxTemperatureCellIndex()); add(json, "minTemperature", value.minTemperature());
		add(json, "minTemperatureCellIndex", value.minTemperatureCellIndex()); add(json, "maxResistance", value.maxResistance());
		add(json, "maxResistanceCellIndex", value.maxResistanceCellIndex()); add(json, "minResistance", value.minResistance());
		add(json, "minResistanceCellIndex", value.minResistanceCellIndex());
		json.add("socEstimator", estimator(value.socEstimator()));
		json.add("sohEstimator", estimator(value.sohEstimator()));
		return json;
	}

	private static JsonObject calculationStatus(CalculationStatus value) {
		var json = new JsonObject();
		json.addProperty("calculatorAvailable", value.calculatorAvailable());
		json.addProperty("calculatedSocCellCount", value.calculatedSocCellCount());
		json.addProperty("calculatedSohCellCount", value.calculatedSohCellCount());
		add(json, "cutoffVoltage", value.cutoffVoltage()); add(json, "floatVoltage", value.floatVoltage());
		add(json, "ratedResistanceUohm", value.ratedResistanceUohm()); add(json, "snapshotSequence", value.snapshotSequence());
		add(json, "snapshotTimestamp", value.snapshotTimestamp()); add(json, "snapshotAgeMs", value.snapshotAgeMs());
		json.addProperty("consecutiveSnapshotFailures", value.consecutiveSnapshotFailures());
		json.addProperty("snapshotStale", value.snapshotStale());
		json.add("unavailableTaCellIds", integers(value.unavailableTaCellIds()));
		if (value.error() == null) json.add("error", JsonNull.INSTANCE); else json.addProperty("error", value.error());
		if (value.stringWarning() == null) json.add("stringWarning", JsonNull.INSTANCE); else json.addProperty("stringWarning", value.stringWarning());
		return json;
	}

	private static JsonObject cell(CellData value) {
		var json = new JsonObject();
		json.addProperty("cellId", value.cellId()); add(json, "voltage", value.voltage());
		add(json, "temperature", value.temperature()); add(json, "internalResistance", value.internalResistance());
		add(json, "soc", value.soc()); add(json, "soh", value.soh()); add(json, "ir", value.ir());
		if (value.availabilityWarning() == null) json.add("availabilityWarning", JsonNull.INSTANCE); else json.addProperty("availabilityWarning", value.availabilityWarning());
		json.add("socEstimator", estimator(value.socEstimator()));
		json.add("sohEstimator", estimator(value.sohEstimator()));
		return json;
	}

	private static JsonObject estimator(io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance value) {
		if (value == null) return null;
		var json = new JsonObject();
		add(json, "method", value.method());
		add(json, "state", value.state());
		json.addProperty("quality", value.quality().name());
		add(json, "note", value.note());
		var reference = value.reference();
		if (reference == null) {
			json.add("reference", JsonNull.INSTANCE);
		} else {
			var identity = new JsonObject();
			add(identity, "calibrationEventId", reference.calibrationEventId());
			add(identity, "curveVersionId", reference.curveVersionId());
			add(identity, "calibrationProcessId", reference.calibrationProcessId());
			add(identity, "processAttemptNumber", reference.processAttemptNumber());
			add(identity, "contentHash", reference.contentHash());
			json.add("reference", identity);
		}
		return json;
	}

	private static JsonObject readiness(Readiness value) {
		var json = new JsonObject();
		json.addProperty("operationId", value.operationId()); json.addProperty("stringIndex", value.stringIndex());
		json.addProperty("generation", value.generation()); json.addProperty("operation", value.operation());
		json.addProperty("expectedCellCount", value.expectedCellCount()); json.addProperty("bridgeId", value.bridgeId());
		json.addProperty("startedAt", value.startedAt()); add(json, "configAdminCompletedAt", value.configAdminCompletedAt());
		add(json, "componentsAvailableAt", value.componentsAvailableAt()); add(json, "firstTaReadAt", value.firstTaReadAt());
		add(json, "firstTcReadAt", value.firstTcReadAt()); add(json, "completeReadAt", value.completeReadAt());
		add(json, "calculatorSnapshotAt", value.calculatorSnapshotAt()); json.addProperty("complete", value.complete());
		json.addProperty("tcState", value.tcState()); json.addProperty("calculatorFailureCount", value.calculatorFailureCount());
		json.addProperty("observedFailureCount", value.observedFailureCount()); json.addProperty("restartCount", value.restartCount());
		json.add("successfulTaIds", integers(value.successfulTaIds())); json.add("pendingTaIds", integers(value.pendingTaIds()));
		json.add("failedTaIds", integers(value.failedTaIds()));
		return json;
	}

	private static JsonArray integers(java.util.List<Integer> values) {
		var json = new JsonArray();
		values.forEach(json::add);
		return json;
	}

	private static void add(JsonObject object, String property, Number value) {
		if (value == null) object.add(property, JsonNull.INSTANCE); else object.addProperty(property, value);
	}
	private static void add(JsonObject object, String property, String value) {
		if (value == null) object.add(property, JsonNull.INSTANCE); else object.addProperty(property, value);
	}
	private static String required(JsonObject params, String property) {
		var value = optional(params, property);
		if (value.isBlank()) throw new IllegalArgumentException("Missing " + property);
		return value;
	}
	private static String optional(JsonObject params, String property) {
		return !params.has(property) || params.get(property).isJsonNull() ? "" : params.get(property).getAsString();
	}
	private static int requiredInt(JsonObject params, String property) {
		if (!params.has(property)) throw new IllegalArgumentException("Missing " + property);
		return params.get(property).getAsInt();
	}
	private static int optionalInt(JsonObject params, String property, int fallback) {
		return !params.has(property) || params.get(property).isJsonNull() ? fallback : params.get(property).getAsInt();
	}
	private static double number(JsonObject params, String property, double fallback) {
		return !params.has(property) || params.get(property).isJsonNull() ? fallback : params.get(property).getAsDouble();
	}
}
