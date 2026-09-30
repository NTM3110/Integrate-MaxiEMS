package io.openems.edge.config.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;

/**
 * JSON-RPC endpoint for exporting OSGi configurations.
 */
public class ExportConfigs implements EndpointRequestType<EmptyObject, ExportConfigs.Response> {

	public static final String METHOD = "exportConfigs";

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

	public record Response(JsonObject data) {

		/**
		 * Returns a {@link JsonSerializer} for {@link ExportConfigs.Response}.
		 *
		 * @return the created {@link JsonSerializer}
		 */
		public static JsonSerializer<Response> serializer() {
			return jsonObjectSerializer(Response.class, //
					json -> new Response(json.getJsonObject("data")), //
					obj -> JsonUtils.buildJsonObject() //
							.add("data", obj.data()) //
							.build());
		}
	}
}
