package io.openems.edge.modem.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;

/**
 * Get 4G Modem Configuration JSON-RPC Request.
 */
public class GetModemConfig implements EndpointRequestType<EmptyObject, GetModemConfig.Response> {

    public static final String METHOD = "getModemConfig";

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
            String apn,
            String username,
            String password) {

        public static JsonSerializer<Response> serializer() {
            return jsonObjectSerializer(Response.class,
                    json -> new Response(
                            json.getOptionalString("apn").orElse(""),
                            json.getOptionalString("username").orElse(""),
                            json.getOptionalString("password").orElse("")),
                    obj -> JsonUtils.buildJsonObject()
                            .addPropertyIfNotNull("apn", obj.apn())
                            .addPropertyIfNotNull("username", obj.username())
                            .addPropertyIfNotNull("password", obj.password())
                            .build());
        }
    }
}
