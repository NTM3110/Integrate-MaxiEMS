package io.openems.edge.modem.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;

/**
 * Set 4G Modem Configuration JSON-RPC Request.
 */
public class SetModemConfig implements EndpointRequestType<SetModemConfig.Request, EmptyObject> {

    public static final String METHOD = "setModemConfig";

    @Override
    public String getMethod() {
        return METHOD;
    }

    @Override
    public JsonSerializer<Request> getRequestSerializer() {
        return Request.serializer();
    }

    @Override
    public JsonSerializer<EmptyObject> getResponseSerializer() {
        return EmptyObject.serializer();
    }

    public record Request(
            String apn,
            String username,
            String password) {

        public static JsonSerializer<Request> serializer() {
            return jsonObjectSerializer(Request.class,
                    json -> new Request(
                            json.getString("apn"),
                            json.getOptionalString("username").orElse(""),
                            json.getOptionalString("password").orElse("")),
                    obj -> JsonUtils.buildJsonObject()
                            .addProperty("apn", obj.apn())
                            .addPropertyIfNotNull("username", obj.username())
                            .addPropertyIfNotNull("password", obj.password())
                            .build());
        }
    }
}
