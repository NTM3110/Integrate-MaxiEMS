package io.openems.edge.network.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerStatus.Response;

/**
 * Gets the current status of all network interfaces.
 * 
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "getNetworkManagerStatus",
 *   "params": {}
 * }
 * </pre>
 */
public class GetNetworkManagerStatus implements EndpointRequestType<EmptyObject, Response> {

    public static final String METHOD = "getNetworkManagerStatus";

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
            String lan1Ip,
            String lan2Ip,
            String mobileIp,
            String lastErrorMessage) {
        /**
         * Handles GetNetworkManagerStatus request.
         *
         * @return the response with current IP status
         */
        public static JsonSerializer<Response> serializer() {
            return jsonObjectSerializer(Response.class,
                    json -> new Response(
                            json.getOptionalString("lan1Ip").orElse(null),
                            json.getOptionalString("lan2Ip").orElse(null),
                            json.getOptionalString("mobileIp").orElse(null),
                            json.getOptionalString("lastErrorMessage").orElse(null)),
                    obj -> JsonUtils.buildJsonObject()
                            .addPropertyIfNotNull("lan1Ip", obj.lan1Ip())
                            .addPropertyIfNotNull("lan2Ip", obj.lan2Ip())
                            .addPropertyIfNotNull("mobileIp", obj.mobileIp())
                            .addPropertyIfNotNull("lastErrorMessage", obj.lastErrorMessage())
                            .build());
        }
    }
}
