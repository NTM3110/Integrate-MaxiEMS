package io.openems.edge.bms.service.discharge.implement.persistence;

import java.sql.ResultSet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import com.google.gson.*;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.bms.service.discharge.contracts.DischargeQueryService;

/** Legacy discharge storage projection; endpoint owns authentication. */
@Component(name = "io.openems.edge.bms.discharge.implement.persistence.PostgresDischargeQueryService", service = DischargeQueryService.class)
public class PostgresDischargeQueryService implements DischargeQueryService {
	@Reference private BmsDatabase database;
	public JsonObject events() {
		var output = new JsonObject();
		var events = new JsonArray();
		var sql = """
				SELECT e.id,bs.string_key,e.event_type,e.baseline_event_id,e.current_a,e.raw_current_a,
					e.domain_current_a,e.current_direction,e.current_provenance,e.used_ah,e.nominal_capacity_ah,
					e.baseline_reference_ah,e.computed_soh,e.endpoint_reached,e.active_baseline,e.automatic_mode,
					e.automatic_start_current_a,e.automatic_stop_current_a,e.sample_interval_seconds,
					e.time_scale_factor,e.status,e.failure_reason,e.start_time,e.end_time,e.created_at,e.updated_at
				FROM bms.discharge_event e JOIN bms.bms_string bs ON bs.id=e.string_id ORDER BY e.id DESC
				""";
		try (var connection = this.database.getConnection(); var statement = connection.prepareStatement(sql);
				var result = statement.executeQuery()) {
			while (result.next()) {
				var event = new JsonObject();
				event.addProperty("id", result.getLong(1));
				event.addProperty("strId", result.getString(2));
				event.addProperty("eventType", result.getString(3));
				add(event, "baselineEventId", result.getObject(4, Long.class));
				addCurrentFields(event, number(result, 5), number(result, 6), number(result, 7), result.getString(8),
						result.getString(9), null);
				add(event, "usedAh", number(result, 10));
				add(event, "nominalCapacityAh", number(result, 11));
				add(event, "baselineReferenceAh", number(result, 12));
				add(event, "computedSoh", number(result, 13));
				event.addProperty("endpointReached", result.getBoolean(14));
				event.addProperty("startedFull", false);
				event.addProperty("activeBaseline", result.getBoolean(15));
				event.addProperty("automaticMode", result.getBoolean(16));
				add(event, "automaticStartCurrentA", number(result, 17));
				add(event, "automaticStopCurrentA", number(result, 18));
				event.addProperty("sampleIntervalSeconds", result.getInt(19));
				add(event, "timeScaleFactor", number(result, 20));
				event.addProperty("status", result.getString(21));
				add(event, "failureReason", result.getString(22));
				event.addProperty("startTime", result.getTimestamp(23).toInstant().toString());
				add(event, "endTime", instant(result, 24));
				event.addProperty("createdAt", result.getTimestamp(25).toInstant().toString());
				event.addProperty("updatedAt", result.getTimestamp(26).toInstant().toString());
				event.add("baselineUsedAh", JsonNull.INSTANCE);
				event.add("baselineCapacitySource", JsonNull.INSTANCE);
				event.add("vcutoffSocPct", JsonNull.INSTANCE);
				events.add(event);
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		output.add("events", events);
		return output;
	}

	public JsonObject samples(int eventId) {
		var output = new JsonObject();
		var samples = new JsonArray();
		var sql = """
				SELECT ds.id,ds.sample_time,ds.avg_voltage_v,ds.current_a,ds.raw_current_a,ds.domain_current_a,
					ds.current_direction,ds.current_provenance,ds.sample_valid,ds.delta_seconds,ds.used_ah,
					ds.temperature_c,bs.string_key
				FROM bms.discharge_sample ds
				JOIN bms.discharge_event e ON e.id=ds.event_id
				JOIN bms.bms_string bs ON bs.id=e.string_id
				WHERE ds.event_id=? ORDER BY ds.sample_time
				""";
		try (var connection = this.database.getConnection(); var statement = connection.prepareStatement(sql)) {
			statement.setInt(1, eventId);
			try (var result = statement.executeQuery()) {
				while (result.next()) {
					var sample = new JsonObject();
					sample.addProperty("id", result.getLong(1));
					sample.addProperty("sampleTime", result.getTimestamp(2).toInstant().toString());
					add(sample, "avgVoltageV", number(result, 3));
					addCurrentFields(sample, number(result, 4), number(result, 5), number(result, 6),
							result.getString(7), result.getString(8), result.getObject(9, Boolean.class));
					add(sample, "deltaSeconds", number(result, 10));
					add(sample, "usedAh", number(result, 11));
					add(sample, "temperatureC", number(result, 12));
					sample.addProperty("eventId", eventId);
					sample.addProperty("strId", result.getString(13));
					samples.add(sample);
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		output.add("samples", samples);
		return output;
	}

	/** Adds both the backward-compatible value and its explicit sign provenance. */
	static void addCurrentFields(JsonObject json, Double legacyCurrent, Double rawCurrent, Double domainCurrent,
			String direction, String provenance, Boolean sampleValid) {
		add(json, json.has("sampleTime") ? "currentA" : "current", legacyCurrent);
		add(json, "rawCurrentA", rawCurrent);
		add(json, "domainCurrentA", domainCurrent);
		add(json, "currentDirection", direction);
		json.addProperty("currentProvenance", provenance == null ? "LEGACY_UNKNOWN" : provenance);
		if (json.has("sampleTime")) {
			if (sampleValid == null) json.add("sampleValid", JsonNull.INSTANCE);
			else json.addProperty("sampleValid", sampleValid);
		}
	}

	public JsonObject config() {
		try (var connection = this.database.getConnection()) {
			this.ensureConfig(connection);
			try (var statement = connection.prepareStatement("SELECT id,direction,automatic_mode,automatic_start_current_a,automatic_stop_current_a,sample_interval_seconds,time_scale_factor,invalid_sample_limit,rising_voltage_sample_limit,updated_at FROM bms.discharge_calibration_config WHERE id=1");
					var result = statement.executeQuery()) {
				result.next();
				var config = new JsonObject();
				config.addProperty("id", result.getInt(1));
				config.addProperty("dischargeCurrentDirection", result.getString(2));
				config.addProperty("automaticMode", result.getBoolean(3));
				config.addProperty("automaticStartCurrentA", result.getDouble(4));
				config.addProperty("automaticStopCurrentA", result.getDouble(5));
				config.addProperty("sampleIntervalSeconds", result.getInt(6));
				config.addProperty("timeScaleFactor", result.getDouble(7));
				config.addProperty("invalidSampleLimit", result.getInt(8));
				config.addProperty("risingVoltageSampleLimit", result.getInt(9));
				add(config, "updatedAt", instant(result, 10));
				return config;
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public void saveConfig(JsonObject params) {
		var direction = DischargeCurrentDirection.parse(string(params, "dischargeCurrentDirection"));
		var startCurrent = nonNegative(number(params, "automaticStartCurrentA", 6), "Auto start current");
		var stopCurrent = nonNegative(number(params, "automaticStopCurrentA", 1), "Auto stop current");
		var interval = positiveInteger(params, "sampleIntervalSeconds");
		var scale = positive(number(params, "timeScaleFactor", 1), "Time scale factor");
		var invalidLimit = positiveInteger(params, "invalidSampleLimit");
		var risingLimit = positiveInteger(params, "risingVoltageSampleLimit");
		try (var connection = this.database.getConnection()) {
			this.ensureConfig(connection);
			try (var statement = connection.prepareStatement("UPDATE bms.discharge_calibration_config SET direction=?,automatic_mode=?,automatic_start_current_a=?,automatic_stop_current_a=?,sample_interval_seconds=?,time_scale_factor=?,invalid_sample_limit=?,rising_voltage_sample_limit=?,updated_at=now() WHERE id=1")) {
				statement.setString(1, direction.name());
				statement.setBoolean(2, bool(params, "automaticMode", true));
				statement.setDouble(3, startCurrent);
				statement.setDouble(4, stopCurrent);
				statement.setInt(5, interval);
				statement.setDouble(6, scale);
				statement.setInt(7, invalidLimit);
				statement.setInt(8, risingLimit);
				statement.executeUpdate();
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void ensureConfig(java.sql.Connection connection) throws Exception {
		try (var statement = connection.prepareStatement("INSERT INTO bms.discharge_calibration_config(id,direction,automatic_mode,automatic_start_current_a,automatic_stop_current_a,sample_interval_seconds,time_scale_factor,invalid_sample_limit,rising_voltage_sample_limit) VALUES(1,'NEGATIVE',true,6,1,1800,1,10,10) ON CONFLICT (id) DO NOTHING")) {
			statement.executeUpdate();
		}
	}

	public JsonObject curve(int stringIndex) {
		var output = new JsonObject();
		var points = new JsonArray();
		try (var connection = this.database.getConnection(); var statement = connection.prepareStatement("SELECT p.id,bs.string_key,p.source_event_id,p.soc_pct,p.voltage_v,p.sample_count,p.active,p.created_at FROM bms.soc_voltage_curve p JOIN bms.bms_string bs ON bs.id=p.string_id WHERE bs.string_index=? ORDER BY p.soc_pct")) {
			statement.setInt(1, stringIndex);
			try (var result = statement.executeQuery()) {
				while (result.next()) {
					var point = new JsonObject();
					point.addProperty("id", result.getLong(1)); point.addProperty("strId", result.getString(2));
					point.addProperty("sourceEventId", result.getLong(3)); point.addProperty("socPct", result.getDouble(4));
					point.addProperty("voltageV", result.getDouble(5)); point.addProperty("sampleCount", result.getInt(6));
					point.addProperty("active", result.getBoolean(7)); point.addProperty("createdAt", result.getTimestamp(8).toInstant().toString());
					points.add(point);
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		output.add("points", points);
		return output;
	}

	public void promoteCurve(int stringIndex, int sourceEventId) {
		try (var connection = this.database.getConnection();
				var clear = connection.prepareStatement("UPDATE bms.soc_voltage_curve SET active=false WHERE string_id=(SELECT id FROM bms.bms_string WHERE string_index=?)");
				var set = connection.prepareStatement("UPDATE bms.soc_voltage_curve SET active=true WHERE string_id=(SELECT id FROM bms.bms_string WHERE string_index=?) AND source_event_id=?")) {
			clear.setInt(1, stringIndex); clear.executeUpdate();
			set.setInt(1, stringIndex); set.setInt(2, sourceEventId); set.executeUpdate();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void add(JsonObject json, String key, Number value) {
		if (value == null) json.add(key, JsonNull.INSTANCE); else json.addProperty(key, value);
	}

	private static void add(JsonObject json, String key, String value) {
		if (value == null) json.add(key, JsonNull.INSTANCE); else json.addProperty(key, value);
	}

	private static Double number(ResultSet result, int column) throws java.sql.SQLException {
		var value = result.getBigDecimal(column);
		return value == null ? null : value.doubleValue();
	}

	private static String instant(ResultSet result, int column) throws java.sql.SQLException {
		var value = result.getTimestamp(column);
		return value == null ? null : value.toInstant().toString();
	}

	private static String string(JsonObject params, String key) {
		if (!params.has(key) || !params.get(key).isJsonPrimitive()
				|| !params.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Missing " + key);
		return params.get(key).getAsString();
	}

	private static String optional(JsonObject params, String key, String defaultValue) {
		return !params.has(key) || params.get(key).isJsonNull() ? defaultValue : params.get(key).getAsString();
	}

	private static int integer(JsonObject params, String key) {
		if (!params.has(key)) throw new IllegalArgumentException("Missing " + key);
		return params.get(key).getAsInt();
	}

	private static int positiveInteger(JsonObject params, String key) {
		var value = integer(params, key);
		if (value <= 0) throw new IllegalArgumentException(key + " must be greater than zero");
		return value;
	}

	private static boolean bool(JsonObject params, String key, boolean defaultValue) {
		return !params.has(key) ? defaultValue : params.get(key).getAsBoolean();
	}

	private static double number(JsonObject params, String key, double defaultValue) {
		var value = !params.has(key) ? defaultValue : params.get(key).getAsDouble();
		if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
		return value;
	}

	private static Double nullableNumber(JsonObject params, String key) {
		if (!params.has(key) || params.get(key).isJsonNull()) return null;
		var value = params.get(key).getAsDouble();
		if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
		return value;
	}

	private static double positive(double value, String label) {
		if (value <= 0) throw new IllegalArgumentException(label + " must be greater than zero");
		return value;
	}

	private static double nonNegative(double value, String label) {
		if (value < 0) throw new IllegalArgumentException(label + " must not be negative");
		return value;
	}
}
