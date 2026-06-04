package io.openems.edge.meter.edmi;

import com.atdigital.imr.EdmiDateTime;
import com.atdigital.imr.objects.ReportProfileData;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.edmi.api.*;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.types.MeterType;
import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.EDMI", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class EdmiMeterImpl extends AbstractOpenemsEdmiComponent implements OpenemsComponent, ComponentJsonApi {

	@Reference
	private EdmiBridge bridge;

	private int serial_number;

	private String username;

	private String password;

	private EnergyMeterRole energyRole;

	private EnergySourceType energySourceType;

	private final Logger log = LoggerFactory.getLogger(EdmiMeterImpl.class);

	public EdmiMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values()
		);
	}

	private void applyConfig(Config config) {
		this.serial_number = config.serial_number();
		this.username = config.username();
		this.password = config.password();
		this.energyRole = config.energyRole();
		this.energySourceType = config.energySourceType();
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		this.log.info(String.format("----------------------- Activated EdmiMeterImpl ----------------------"));
		applyConfig(config);
		super.activate(context, config.id(), config.alias(), config.enabled(), this.bridge);
		this.bridge.registerEnergyMeter(this.id(), this.energyRole.name(), this.energySourceType.name());
		this.log.info("Username: {}, Password: {}, SerialNumber: {}", this.username, this.password, this.serial_number);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.bridge.unregisterEnergyMeter(this.id());
		super.deactivate();
	}


	@Override
	protected EdmiProtocol defineEdmiProtocol() {
		return new EdmiProtocol(
//				 30-Second Task for Real-time values
				new ReadBillingTask(this.id(), Priority.HIGH, this.bridge, this.username, this.password,
						this.serial_number, this.energyRole.name(), this.energySourceType.name()),

				// 30-Minute Task for Profile records
				new ReadProfileTask(this.id(), Priority.HIGH, this.bridge, this.username, this.password,
						this.serial_number, this.energyRole.name(), this.energySourceType.name())
		);
	}

	public static final String GET_EDMI_PROFILE_METHOD = "getEdmiProfile";
	public static final String QUERY_EDMI_BILLING_HISTORY_METHOD = "queryEdmiBillingHistory";
	public static final String QUERY_EDMI_PROFILE_HISTORY_METHOD = "queryEdmiProfileHistory";
	private static final ZoneOffset BILLING_TIME_OFFSET = ZoneOffset.ofHours(7);

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET_EDMI_PROFILE_METHOD, call -> {
			var params = call.getRequest().getParams();
			String startStr = JsonUtils.getAsString(params, "startDate"); // "yy-MM-ddTHH:mm:ss"
			String endStr   = JsonUtils.getAsString(params, "endDate");
			EdmiDateTime.ByValue from = FormatHelper.fromFormattedString(startStr);
			EdmiDateTime.ByValue to = FormatHelper.fromFormattedString(endStr);

//			short survey = 0x0325;
			ReportProfileData reportProfileData = (ReportProfileData) bridge.readProfileImmediately(from, to,
					this.serial_number, this.username, this.password, this.bridge.getProfileIngestionSettings().survey());
			Gson gson = new Gson();
			JsonObject jsonObject = gson.toJsonTree(reportProfileData).getAsJsonObject();
			JsonObject resultObj = new JsonObject();
			resultObj.add("objects", jsonObject);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(QUERY_EDMI_BILLING_HISTORY_METHOD, call -> {
			var params = call.getRequest().getParams();
			var meterId = JsonUtils.getAsString(params, "meterId");
			var startDate = JsonUtils.getAsLocalDateTime(params, "startDate");
			var endDate = JsonUtils.getAsLocalDateTime(params, "endDate");
			var fields = List.of(JsonUtils.getAsStringArray(JsonUtils.getAsJsonArray(params, "fields")));

			this.validateBillingHistoryRequest(startDate, endDate, fields);

			var records = this.bridge.queryBillingValuesFromInflux(//
					meterId, //
					startDate.toInstant(BILLING_TIME_OFFSET), //
					endDate.toInstant(BILLING_TIME_OFFSET), //
					fields);

			var resultObj = JsonUtils.buildJsonObject() //
					.addProperty("meterId", meterId) //
					.addProperty("startDate", startDate) //
					.addProperty("endDate", endDate) //
					.add("fields", JsonUtils.generateJsonArray(fields, JsonUtils::toJson)) //
					.add("records", records) //
					.build();
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(QUERY_EDMI_PROFILE_HISTORY_METHOD, call -> {
			var params = call.getRequest().getParams();
			var meterId = JsonUtils.getAsString(params, "meterId");
			var startDate = JsonUtils.getAsLocalDateTime(params, "startDate");
			var endDate = JsonUtils.getAsLocalDateTime(params, "endDate");
			var fields = List.of(JsonUtils.getAsStringArray(JsonUtils.getAsJsonArray(params, "fields")));

			this.validateBillingHistoryRequest(startDate, endDate, fields);

			var records = this.bridge.queryProfileValuesFromInflux(//
					meterId, //
					startDate.toInstant(BILLING_TIME_OFFSET), //
					endDate.toInstant(BILLING_TIME_OFFSET), //
					fields);

			var resultObj = JsonUtils.buildJsonObject() //
					.addProperty("meterId", meterId) //
					.addProperty("startDate", startDate) //
					.addProperty("endDate", endDate) //
					.add("fields", JsonUtils.generateJsonArray(fields, JsonUtils::toJson)) //
					.add("records", records) //
					.build();
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest("queryEdmiSeparatedEnergyHistory", call -> {
			var params = call.getRequest().getParams();
			var startDate = JsonUtils.getAsLocalDateTime(params, "startDate");
			var endDate = JsonUtils.getAsLocalDateTime(params, "endDate");
			var fields = List.of(JsonUtils.getAsStringArray(JsonUtils.getAsJsonArray(params, "fields")));

			this.validateBillingHistoryRequest(startDate, endDate, fields);

			var records = this.bridge.querySeparatedEnergyFromInflux(//
					startDate.toInstant(BILLING_TIME_OFFSET), //
					endDate.toInstant(BILLING_TIME_OFFSET), //
					fields);

			var resultObj = JsonUtils.buildJsonObject() //
					.addProperty("startDate", startDate) //
					.addProperty("endDate", endDate) //
					.add("fields", JsonUtils.generateJsonArray(fields, JsonUtils::toJson)) //
					.add("records", records) //
					.build();
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});
	}

	private void validateBillingHistoryRequest(LocalDateTime startDate, LocalDateTime endDate, List<String> fields) {
		if (startDate.isAfter(endDate)) {
			throw new IllegalArgumentException("startDate must be before or equal to endDate.");
		}
		if (fields.isEmpty()) {
			throw new IllegalArgumentException("fields must not be empty.");
		}
	}
}
