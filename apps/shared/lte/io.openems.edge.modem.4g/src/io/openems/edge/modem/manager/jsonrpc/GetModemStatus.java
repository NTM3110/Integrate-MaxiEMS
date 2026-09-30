package io.openems.edge.modem.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;

/**
 * Get 4G Modem Status JSON-RPC Request.
 */
public class GetModemStatus implements EndpointRequestType<EmptyObject, GetModemStatus.Response> {

    public static final String METHOD = "getModemStatus";

    @Override
    public String getMethod() {
        return METHOD;
    }

    @Override
    public JsonSerializer<EmptyObject> getRequestSerializer() {
        return EmptyObject.serializer();
    }

    @Override
    public JsonSerializer<Response> getResponseSerializer() {
        return Response.serializer();
    }

    public record Response(
            String status,
            int signalStrength,
            int signalDbm,
            String operator,
            String ipAddress,
            String connectionType,
            String modemModel,
            String imei,
            String lastError) {

        public static JsonSerializer<Response> serializer() {
            return jsonObjectSerializer(Response.class,
                    json -> new Response(
                            json.getOptionalString("status").orElse(""),
                            json.getOptionalInt("signalStrength").orElse(0),
                            json.getOptionalInt("signalDbm").orElse(0),
                            json.getOptionalString("operator").orElse(""),
                            json.getOptionalString("ipAddress").orElse(""),
                            json.getOptionalString("connectionType").orElse(""),
                            json.getOptionalString("modemModel").orElse(""),
                            json.getOptionalString("imei").orElse(""),
                            json.getOptionalString("lastError").orElse("")),
                    obj -> JsonUtils.buildJsonObject()
                            .addPropertyIfNotNull("status", obj.status())
                            .addProperty("signalStrength", obj.signalStrength())
                            .addProperty("signalDbm", obj.signalDbm())
                            .addPropertyIfNotNull("operator", obj.operator())
                            .addPropertyIfNotNull("ipAddress", obj.ipAddress())
                            .addPropertyIfNotNull("connectionType", obj.connectionType())
                            .addPropertyIfNotNull("modemModel", obj.modemModel())
                            .addPropertyIfNotNull("imei", obj.imei())
                            .addPropertyIfNotNull("lastError", obj.lastError())
                            .build());
        }
    }
}
