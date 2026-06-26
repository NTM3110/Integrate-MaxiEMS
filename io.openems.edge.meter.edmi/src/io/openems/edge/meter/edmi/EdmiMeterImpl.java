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

import io.openems.edge.controller.maximeter.MaxiMeterEdmiController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
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

	@Reference
	private MaxiMeterEdmiController maxiMeterController;

	private int serial_number;

	private String username;

	private String password;

	private EnergyMeterRole energyRole;

	private EnergySourceType energySourceType;

	private EdmiMeterPostgreSqlSync postgreSqlSync;

	private String pgHost;
	private int pgPort;
	private String pgDatabase;
	private String pgUser;
	private String pgPassword;
	private boolean enablePgWrite;

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
		this.pgHost = config.postgreSqlHost();
		this.pgPort = config.postgreSqlPort();
		this.pgDatabase = config.postgreSqlDatabase();
		this.pgUser = config.postgreSqlUser();
		this.pgPassword = config.postgreSqlPassword();
		this.enablePgWrite = config.enablePostgreSqlSync();
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		this.log.info(String.format("----------------------- Activated EdmiMeterImpl ----------------------"));
		applyConfig(config);
		super.activate(context, config.id(), config.alias(), config.enabled(), this.bridge);
		this.bridge.registerEnergyMeter(this.id(), this.energyRole.name(), this.energySourceType.name());
		this.log.info("Username: {}, Password: {}, SerialNumber: {}", this.username, this.password, this.serial_number);
		
		// Initialize PostgreSQL sync if enabled
		if (config.enablePostgreSqlSync()) {
			this.postgreSqlSync = new EdmiMeterPostgreSqlSync(
					config.postgreSqlHost(), config.postgreSqlPort(), config.postgreSqlDatabase(),
					config.postgreSqlUser(), config.postgreSqlPassword(), config.enablePostgreSqlSync());
			this.postgreSqlSync.syncMeter(this.id(), this.serial_number, this.username, 
					this.password, this.energyRole.name(), this.energySourceType.name(), 
					config.alias(), config.enabled());
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.bridge.unregisterEnergyMeter(this.id());
		
		// Sync deletion to PostgreSQL if enabled
		if (this.postgreSqlSync != null) {
			this.postgreSqlSync.deleteMeter(this.id(), this.serial_number);
		}
		
		super.deactivate();
	}


	@Override
	protected EdmiProtocol defineEdmiProtocol() {
		// Create ReadBillingTask with status callback
		ReadBillingTask billingTask = new ReadBillingTask(
				this.id(), Priority.HIGH, this.bridge, this.username, this.password,
				this.serial_number, this.energyRole.name(), this.energySourceType.name(),
				this.pgHost, this.pgPort, this.pgDatabase,
				this.pgUser, this.pgPassword, this.enablePgWrite,
				new ReadBillingTask.MeterStatusCallback() {
					@Override
					public void onMeterStatusUpdate(String meterId, String status, Double lastReadingValue, String errorMessage) {
						if (maxiMeterController != null) {
							maxiMeterController.updateMeterStatus(meterId, status, lastReadingValue, errorMessage);
						}
					}
				});
		
		return new EdmiProtocol(
				billingTask,
				
				// 30-Minute Task for Profile records
				new ReadProfileTask(this.id(), Priority.HIGH, this.bridge, this.username, this.password,
						this.serial_number, this.energyRole.name(), this.energySourceType.name(),
						this.pgHost, this.pgPort, this.pgDatabase,
						this.pgUser, this.pgPassword, this.enablePgWrite)
		);
	}

	public static final String GET_EDMI_PROFILE_METHOD = "getEdmiProfile";
	public static final String QUERY_EDMI_BILLING_HISTORY_METHOD = "queryEdmiBillingHistory";
	public static final String QUERY_EDMI_PROFILE_HISTORY_METHOD = "queryEdmiProfileHistory";
	private static final ZoneOffset BILLING_TIME_OFFSET = ZoneOffset.ofHours(7);

	private void writeProfileDataToPostgreSQL(ReportProfileData reportProfileData) {
		try {
			var fieldMap = this.resolveProfileFields(reportProfileData.getChannels());
			int processedRecords = 0;
			
			for (List<Object> row : reportProfileData.getData()) {
				if (row == null || row.size() < 4) {
					continue;
				}
				
				try {
					LocalDateTime timestamp = this.parseProfileTimestamp(row.get(fieldMap.timestampIndex()));
					
					String url = String.format("jdbc:postgresql://%s:%d/%s", this.pgHost, this.pgPort, this.pgDatabase);
					String sql = "INSERT INTO meter_profile (controller_id, meter_id, time_stamp, record_status, " +
							"total_energy_tot_imp_wh, total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va, " +
							"meter_role, source_type) " +
							"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
							"ON CONFLICT (controller_id, meter_id, time_stamp) DO UPDATE SET " +
							"record_status = EXCLUDED.record_status, " +
							"total_energy_tot_imp_wh = EXCLUDED.total_energy_tot_imp_wh, " +
							"total_energy_tot_exp_wh = EXCLUDED.total_energy_tot_exp_wh, " +
							"total_energy_tot_imp_va = EXCLUDED.total_energy_tot_imp_va, " +
							"total_energy_tot_exp_va = EXCLUDED.total_energy_tot_exp_va, " +
							"meter_role = EXCLUDED.meter_role, " +
							"source_type = EXCLUDED.source_type";
					
					try (Connection conn = DriverManager.getConnection(url, this.pgUser, this.pgPassword);
							PreparedStatement stmt = conn.prepareStatement(sql)) {
						
						stmt.setString(1, "controller0"); // Default controller ID
						stmt.setString(2, this.id());
						stmt.setTimestamp(3, Timestamp.valueOf(timestamp));
						stmt.setDouble(4, this.toNumber(row.get(fieldMap.recordStatusIndex()), "record_status").doubleValue());
						stmt.setDouble(5, this.toNumber(row.get(fieldMap.importWhIndex()), "total_energy_tot_imp_wh").doubleValue());
						stmt.setDouble(6, this.toNumber(row.get(fieldMap.exportWhIndex()), "total_energy_tot_exp_wh").doubleValue());
						
						if (fieldMap.importVahIndex() >= 0 && fieldMap.importVahIndex() < row.size()) {
							stmt.setDouble(7, this.toNumber(row.get(fieldMap.importVahIndex()), "total_energy_tot_imp_va").doubleValue());
						} else {
							stmt.setDouble(7, 0.0);
						}
						
						if (fieldMap.exportVahIndex() >= 0 && fieldMap.exportVahIndex() < row.size()) {
							stmt.setDouble(8, this.toNumber(row.get(fieldMap.exportVahIndex()), "total_energy_tot_exp_va").doubleValue());
						} else {
							stmt.setDouble(8, 0.0);
						}
						
						stmt.setString(9, this.energyRole.name());
						stmt.setString(10, this.energySourceType.name());
						
						stmt.executeUpdate();
						processedRecords++;
					}
					
				} catch (Exception e) {
					this.log.error("Error writing profile row to PostgreSQL: {}", e.getMessage());
				}
			}
			
			this.log.info("Wrote {} profile records to PostgreSQL for meter [{}]", processedRecords, this.id());
			
		} catch (Exception e) {
			this.log.error("Error writing profile data to PostgreSQL: {}", e.getMessage());
		}
	}
	
	private ProfileFieldMap resolveProfileFields(List<String> channels) {
		var settings = this.bridge.getProfileIngestionSettings();
		return new ProfileFieldMap(
				this.findChannelIndex(channels, settings.timestampAliases(), 1),
				this.findChannelIndex(channels, settings.recordStatusAliases(), 0),
				this.findChannelIndex(channels, settings.importWhAliases(), 2),
				this.findChannelIndex(channels, settings.exportWhAliases(), 3),
				this.findChannelIndex(channels, settings.importVahAliases(), -1),
				this.findChannelIndex(channels, settings.exportVahAliases(), -1));
	}
	
	private int findChannelIndex(List<String> channels, List<String> aliases, int fallbackIndex) {
		if (channels == null || channels.isEmpty() || aliases == null || aliases.isEmpty()) {
			return fallbackIndex;
		}
		var normalizedAliases = aliases.stream().map(EdmiMeterImpl::normalize).collect(java.util.stream.Collectors.toSet());
		for (int i = 0; i < channels.size(); i++) {
			if (normalizedAliases.contains(normalize(channels.get(i)))) {
				return i;
			}
		}
		return fallbackIndex;
	}
	
	private LocalDateTime parseProfileTimestamp(Object rawValue) {
		String raw = rawValue == null ? "" : rawValue.toString();
		String normalized = raw.replaceFirst("(\\d{2}-\\d{2}-\\d{2}T\\d{2})-(\\d{2})-(\\d{2})", "$1:$2:$3");
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yy-MM-dd'T'HH:mm:ss");
		return LocalDateTime.parse(normalized, formatter);
	}
	
	private Number toNumber(Object value, String fieldName) {
		if (value instanceof Number number) {
			return number;
		}
		if (value instanceof String stringValue) {
			try {
				return Double.parseDouble(stringValue);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						"Profile field [" + fieldName + "] is not numeric: [" + stringValue + "]", e);
			}
		}
		throw new IllegalArgumentException("Profile field [" + fieldName + "] has unsupported type ["
				+ (value == null ? "null" : value.getClass().getName()) + "]");
	}
	
	private static String normalize(String value) {
		return java.util.regex.Pattern.compile("[^a-z0-9]+")
				.matcher(value == null ? "" : value.toLowerCase(java.util.Locale.ROOT))
				.replaceAll("");
	}
	
	private record ProfileFieldMap(int timestampIndex, int recordStatusIndex, int importWhIndex, int exportWhIndex,
			int importVahIndex, int exportVahIndex) {
	}
	
	/**
	 * Read profile from meter and save to PostgreSQL.
	 * This method can be called directly from other components (e.g., MaxiMeterController).
	 * 
	 * @param startDate start date in format "yy-MM-ddTHH:mm:ss"
	 * @param endDate end date in format "yy-MM-ddTHH:mm:ss"
	 * @param survey the survey code (e.g., "LS02")
	 * @return JsonObject with profile data, or null if error
	 */
	public JsonObject readProfileAndSave(String startDate, String endDate, String survey) {
		try {
			EdmiDateTime.ByValue from = FormatHelper.fromFormattedString(startDate);
			EdmiDateTime.ByValue to = FormatHelper.fromFormattedString(endDate);
			
			ReportProfileData reportProfileData = (ReportProfileData) bridge.readProfileImmediately(from, to,
					this.serial_number, this.username, this.password, 
					this.bridge.getProfileIngestionSettings().survey());
			
			// Write to PostgreSQL if enabled
			if (this.enablePgWrite && reportProfileData != null && reportProfileData.getData() != null) {
				this.writeProfileDataToPostgreSQL(reportProfileData);
			}
			
			Gson gson = new Gson();
			JsonObject jsonObject = gson.toJsonTree(reportProfileData).getAsJsonObject();
			JsonObject resultObj = new JsonObject();
			resultObj.add("objects", jsonObject);
			return resultObj;
			
		} catch (Exception e) {
			this.log.error("Error reading profile from meter [{}]: {}", this.id(), e.getMessage());
			return null;
		}
	}
	
	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET_EDMI_PROFILE_METHOD, call -> {
			var params = call.getRequest().getParams();
			String startStr = JsonUtils.getAsString(params, "startDate"); // "yy-MM-ddTHH:mm:ss"
			String endStr   = JsonUtils.getAsString(params, "endDate");
			EdmiDateTime.ByValue from = FormatHelper.fromFormattedString(startStr);
			EdmiDateTime.ByValue to = FormatHelper.fromFormattedString(endStr);

			ReportProfileData reportProfileData = (ReportProfileData) bridge.readProfileImmediately(from, to,
					this.serial_number, this.username, this.password, this.bridge.getProfileIngestionSettings().survey());
			
			// Write to PostgreSQL if enabled
			if (this.enablePgWrite && reportProfileData != null && reportProfileData.getData() != null) {
				this.writeProfileDataToPostgreSQL(reportProfileData);
			}
			
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
