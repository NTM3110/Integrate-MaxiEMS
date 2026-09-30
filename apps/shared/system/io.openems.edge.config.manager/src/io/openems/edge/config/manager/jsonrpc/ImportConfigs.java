package io.openems.edge.config.manager.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.utils.JsonUtils;

/**
 * JSON-RPC endpoint for importing OSGi configurations.
 */
public class ImportConfigs implements EndpointRequestType<ImportConfigs.Request, EmptyObject> {

	public static final String METHOD = "importConfigs";

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

	public record Request(JsonObject data) {

		/**
		 * Returns a {@link JsonSerializer} for {@link ImportConfigs.Request}.
		 *
		 * @return the created {@link JsonSerializer}
		 */
		public static JsonSerializer<Request> serializer() {
			return jsonObjectSerializer(Request.class, //
					json -> new Request(json.getJsonObject("data")), //
					obj -> JsonUtils.buildJsonObject() //
							.add("data", obj.data()) //
							.build());
		}
	}
}
