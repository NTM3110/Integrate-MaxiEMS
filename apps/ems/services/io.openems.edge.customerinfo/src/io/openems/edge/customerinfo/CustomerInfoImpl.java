package io.openems.edge.customerinfo;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.TreeMap;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = CustomerInfoImpl.SERVICE_PID, //
		service = { JsonApi.class, OpenemsComponent.class }, //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.OPTIONAL, //
		property = { //
				"enabled=true", //
				"entry=edge.rest.root" //
		} //
)
public class CustomerInfoImpl extends AbstractOpenemsComponent implements JsonApi, OpenemsComponent {

	static final String SERVICE_PID = "CustomerInfo";
	static final String COMPONENT_ID = "CustomerInfo";

	private volatile Config config;

	@Reference
	private ConfigurationAdmin cm;

	public CustomerInfoImpl() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, COMPONENT_ID, config.alias(), config.enabled());
		this.config = config;
	}

	@Modified
	void modified(ComponentContext context, Config config) {
		super.modified(context, COMPONENT_ID, config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest("getCustomerInfo", call -> {
			var current = this.config;
			var data = JsonUtils.buildJsonObject() //
					.addProperty("businessName", current.businessName()) //
					.addProperty("businessCode", current.businessCode()) //
					.addProperty("address", current.address()) //
					.addProperty("installLocation", current.installLocation()) //
					.build();
			var result = JsonUtils.buildJsonObject() //
					.addProperty("filled", !current.businessName().isBlank()) //
					.add("data", data) //
					.build();
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest("setCustomerInfo", call -> {
			var params = call.getRequest().getParams();
			var businessName = JsonUtils.getAsString(params, "businessName");
			if (businessName.isBlank()) {
				throw new OpenemsException("Business name must not be blank.");
			}
			var businessCode = getOptionalString(params, "businessCode");
			var address = getOptionalString(params, "address");
			var installLocation = getOptionalString(params, "installLocation");

			this.updateConfiguration(businessName, businessCode, address, installLocation);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
	}

	private static String getOptionalString(JsonObject params, String memberName) throws OpenemsNamedException {
		if (!params.has(memberName)) {
			return "";
		}
		return JsonUtils.getAsString(params.get(memberName));
	}

	private synchronized void updateConfiguration(String businessName, String businessCode, String address,
			String installLocation) throws IOException {
		Configuration configuration = this.cm.getConfiguration(SERVICE_PID, null);
		Dictionary<String, Object> properties = configuration.getProperties();
		var merged = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
		if (properties != null) {
			var keys = properties.keys();
			while (keys.hasMoreElements()) {
				var key = keys.nextElement();
				merged.put(key, properties.get(key));
			}
		}
		merged.putIfAbsent("id", COMPONENT_ID);
		merged.putIfAbsent("alias", this.config.alias());
		merged.putIfAbsent("enabled", this.config.enabled());
		merged.put("businessName", businessName);
		merged.put("businessCode", businessCode);
		merged.put("address", address);
		merged.put("installLocation", installLocation);
		configuration.update(new Hashtable<>(merged));
	}
}
