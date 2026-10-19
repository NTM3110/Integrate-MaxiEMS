package io.openems.edge.bms.endpoints.modbus;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.modbus.contracts.BmsModbusServerService;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.ServerState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringModbusMap;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated BMS-only configuration API for the compact Modbus server. */
@Component(name = "io.openems.edge.bms.api.modbus.BmsModbusServerJsonApi", property = "entry=edge.websocket.root")
public class BmsModbusServerJsonApi implements JsonApi {

	public static final String GET = "bms.tools.modbusServer.get";
	public static final String UPDATE = "bms.tools.modbusServer.update";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsModbusServerService service;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET, call -> {
			this.authorize(call.getRequest().getParams(), false);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json(this.service.get()));
		});
		builder.handleRequest(UPDATE, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, true); // authentication intentionally precedes command parsing
			var transport = Transport.valueOf(requiredString(params, "transport"));
			var state = this.service.update(requiredBoolean(params, "enabled"), transport, requiredInt(params, "unitId"),
					transport == Transport.TCP ? requiredString(params, "bindAddress")
							: optionalString(params, "bindAddress", "0.0.0.0"),
					transport == Transport.TCP ? requiredInt(params, "tcpPort") : optionalInt(params, "tcpPort", 1502),
					optionalString(params, "serialPortId"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json(state));
		});
	}

	void authorize(JsonObject params, boolean administrator) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
		var principal = this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (administrator && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
	}

	static JsonObject json(ServerState value) {
		var result = new JsonObject();
		result.addProperty("enabled", value.enabled());
		result.addProperty("transport", value.transport().name());
		result.addProperty("unitId", value.unitId());
		result.addProperty("bindAddress", value.bindAddress());
		result.addProperty("tcpPort", value.tcpPort());
		if (value.serialPortId() == null || value.serialPortId().isBlank()) result.add("serialPortId", null);
		else result.addProperty("serialPortId", value.serialPortId());
		result.addProperty("runtimeState", value.runtimeState().name());
		if (value.runtimeError() == null) result.add("runtimeError", null); else result.addProperty("runtimeError", value.runtimeError());
		var ports = new JsonArray();
		for (var port : value.ports()) {
			var json = new JsonObject();
			json.addProperty("id", port.id());
			json.addProperty("displayName", port.displayName());
			json.addProperty("portName", port.portName());
			json.addProperty("serialFormat", port.serialFormat());
			json.addProperty("available", port.available());
			json.addProperty("selected", port.selected());
			json.addProperty("owner", port.owner());
			var ownerIds = new JsonArray();
			port.ownerIds().forEach(ownerIds::add);
			json.add("ownerIds", ownerIds);
			ports.add(json);
		}
		result.add("ports", ports);
		result.add("registerMap", registerMapJson());
		return result;
	}

	private static JsonObject registerMapJson() {
		var map = new JsonObject();
		map.addProperty("version", BmsMonitoringModbusMap.CONTRACT_VERSION);
		map.addProperty("functionCode", 4);
		map.addProperty("stringStride", BmsMonitoringModbusMap.STRING_STRIDE);
		map.addProperty("maxStringId", BmsMonitoringModbusMap.MAX_STRING_INDEX);
		map.addProperty("registersPerString", BmsMonitoringModbusMap.LAST_RELATIVE_ADDRESS);
		map.addProperty("unavailableS32", "0x80000000");
		map.addProperty("alarmActiveRelativeAddress", BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS);
		map.addProperty("alarmValidityRelativeAddress", BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS);
		var measurements = new JsonArray();
		for (var register : BmsMonitoringModbusMap.measurementRegisters()) {
			var item = new JsonObject();
			item.addProperty("relativeAddress", register.address());
			item.addProperty("registerCount", register.width());
			item.addProperty("type", register.type().name());
			item.addProperty("label", register.label());
			item.addProperty("unit", register.unit());
			item.addProperty("scale", register.scale());
			measurements.add(item);
		}
		map.add("measurements", measurements);
		var alarmBits = new JsonArray();
		for (var bit : BmsMonitoringModbusMap.alarmBits()) {
			var item = new JsonObject();
			item.addProperty("bit", bit.bit());
			item.addProperty("label", bit.label());
			alarmBits.add(item);
		}
		map.add("alarmBits", alarmBits);
		return map;
	}

	private static String requiredString(JsonObject params, String name) {
		var value = optionalString(params, name);
		if (value.isBlank()) throw new IllegalArgumentException("Missing " + name);
		return value;
	}
	private static String optionalString(JsonObject params, String name) { return optionalString(params, name, ""); }
	private static String optionalString(JsonObject params, String name, String fallback) { return !params.has(name) || params.get(name).isJsonNull() ? fallback : params.get(name).getAsString(); }
	private static int requiredInt(JsonObject params, String name) { if (!params.has(name) || params.get(name).isJsonNull()) throw new IllegalArgumentException("Missing " + name); return params.get(name).getAsInt(); }
	private static int optionalInt(JsonObject params, String name, int fallback) { return !params.has(name) || params.get(name).isJsonNull() ? fallback : params.get(name).getAsInt(); }
	private static boolean requiredBoolean(JsonObject params, String name) { if (!params.has(name) || params.get(name).isJsonNull()) throw new IllegalArgumentException("Missing " + name); return params.get(name).getAsBoolean(); }
}
