package io.openems.edge.controller.thingsboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/**
 * JSON-RPC handler that persists the frontend configuration tree
 * (Project -> Device) to a JSON file on disk.
 *
 * Registered into the Edge WebSocket root handler via the OSGi
 * service property "entry=edge.websocket.root".
 *
 * Methods exposed:
 *   getTreeConfig  -> returns { project: ProjectNode | null }
 *   saveTreeConfig <- params { project: ProjectNode | null }
 */
@Component(property = "entry=edge.websocket.root")
public class TreeConfigJsonApiHandler implements JsonApi {

	private static final Path CONFIG_FILE = Paths.get(
			System.getProperty("user.home"), ".openems", "tree-config.json");

	private final Logger log = LoggerFactory.getLogger(TreeConfigJsonApiHandler.class);

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest("getTreeConfig", call -> {
			JsonObject result = new JsonObject();
			result.add("project", this.readProjectFromFile());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});

		builder.handleRequest("saveTreeConfig", call -> {
			JsonObject params = call.getRequest().getParams();
			var projectElement = params.get("project");
			JsonElement project = (projectElement != null && (projectElement.isJsonObject() || projectElement.isJsonNull()))
					? projectElement
					: JsonNull.INSTANCE;
			this.writeProjectToFile(project);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
	}

	private JsonElement readProjectFromFile() {
		try {
			if (!Files.exists(CONFIG_FILE)) {
				return JsonNull.INSTANCE;
			}
			String content = Files.readString(CONFIG_FILE);
			var parsed = JsonParser.parseString(content);
			if (parsed.isJsonObject() || parsed.isJsonNull()) {
				return parsed;
			}
			if (parsed.isJsonArray() && parsed.getAsJsonArray().size() > 0
					&& parsed.getAsJsonArray().get(0).isJsonObject()) {
				return parsed.getAsJsonArray().get(0);
			}
			return JsonNull.INSTANCE;
		} catch (IOException e) {
			this.log.error("Failed to read tree-config.json", e);
			return JsonNull.INSTANCE;
		}
	}

	private void writeProjectToFile(JsonElement project) throws IOException {
		Files.createDirectories(CONFIG_FILE.getParent());
		Files.writeString(CONFIG_FILE, project.toString(),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}
}
