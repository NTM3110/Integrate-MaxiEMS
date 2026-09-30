package io.openems.edge.controller.maximeter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.utils.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.controller.api.Controller;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Reference;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import io.openems.edge.common.component.ComponentManager;

/**
 * Controller that connects to PostgreSQL database and provides
 * API for energy calculation reports and meter data.
 * Also manages OSGi component lifecycle for meters and bridges.
 * 
 * Exposes JSON-RPC API via the existing Controller.Api.Rest.ReadWrite.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.MaxiMeter.Edmi", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MaxiMeterEdmiControllerImpl extends AbstractOpenemsComponent
		implements OpenemsComponent, Controller, MaxiMeterEdmiController, ComponentJsonApi {

	private final Logger log = LoggerFactory.getLogger(MaxiMeterEdmiControllerImpl.class);
	private final Gson gson = new Gson();

	// Meter status tracking for streaming
	private final Map<String, MeterStatus> meterStatusMap = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<MeterStatusListener> statusListeners = new CopyOnWriteArrayList<>();

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	private Config config;
	private String dbUrl;
	private String dbUser;
	private String dbPassword;

	// Factory PIDs for OSGi components
	private static final String METER_FACTORY_PID = "Meter.EDMI";
	private static final String BRIDGE_FACTORY_PID = "Bridge.EDMI";

	public MaxiMeterEdmiControllerImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values());
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.dbUrl = String.format("jdbc:postgresql://%s:%d/%s", config.dbHost(), config.dbPort(), config.dbName());
		this.dbUser = config.dbUser();
		this.dbPassword = config.dbPassword();
		this.log.info("MaxiMeter Controller activated. DB: {}", this.dbUrl);
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		// Profile API - reads from database
		builder.handleRequest("getProfileData", call -> {
			var params = call.getRequest().getParams();
			int meterId = JsonUtils.getAsInt(params, "meterId");
			String startDate = JsonUtils.getAsString(params, "startDate");
			String endDate = JsonUtils.getAsString(params, "endDate");
			int maxRecords = JsonUtils.getAsInt(params, "maxRecords");
			
			var records = this.getProfileData(meterId, startDate, endDate, maxRecords);
			var result = JsonUtils.buildJsonObject()
					.addProperty("meterId", meterId)
					.addProperty("startDate", startDate)
					.addProperty("endDate", endDate)
					.add("records", JsonUtils.generateJsonArray(records, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Billing data API - reads from database
		builder.handleRequest("getBillingData", call -> {
			var params = call.getRequest().getParams();
			Integer meterId = params.has("meterId") ? JsonUtils.getAsInt(params, "meterId") : null;
			String startDate = params.has("startDate") ? JsonUtils.getAsString(params, "startDate") : null;
			String endDate = params.has("endDate") ? JsonUtils.getAsString(params, "endDate") : null;
			int limit = params.has("limit") ? JsonUtils.getAsInt(params, "limit") : 100;
			
			var records = this.getBillingData(meterId, startDate, endDate, limit);
			var result = JsonUtils.buildJsonObject()
					.addProperty("meterId", meterId != null ? meterId : 0)
					.addProperty("startDate", startDate != null ? startDate : "")
					.addProperty("endDate", endDate != null ? endDate : "")
					.add("records", JsonUtils.generateJsonArray(records, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Meter CRUD APIs
		builder.handleRequest("getMeters", call -> {
			var records = this.getAllMeters();
			var result = JsonUtils.buildJsonObject()
					.add("meters", JsonUtils.generateJsonArray(records, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("getMeterById", call -> {
			var params = call.getRequest().getParams();
			int meterId = JsonUtils.getAsInt(params, "meterId");
			var meter = this.getMeterById(meterId);
			var result = meter != null ? this.toJson(meter) : new JsonObject();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("createMeter", call -> {
			var params = call.getRequest().getParams();
			Map<String, Object> meterData = new HashMap<>();
			meterData.put("serial_number", JsonUtils.getAsString(params, "serial_number"));
			meterData.put("meter_name", JsonUtils.getAsString(params, "meter_name"));
			meterData.put("username", JsonUtils.getAsString(params, "username"));
			meterData.put("password", JsonUtils.getAsString(params, "password"));
			meterData.put("role_id", JsonUtils.getAsInt(params, "role_id"));
			meterData.put("source_id", JsonUtils.getAsInt(params, "source_id"));
			meterData.put("serial_port_config_id", JsonUtils.getAsInt(params, "serial_port_config_id"));
			meterData.put("type", JsonUtils.getAsString(params, "type"));
			meterData.put("model", JsonUtils.getAsString(params, "model"));
			meterData.put("outstation", JsonUtils.getAsInt(params, "outstation"));
			
			int newMeterId = this.createMeter(meterData);
			var result = JsonUtils.buildJsonObject()
					.addProperty("meterId", newMeterId)
					.addProperty("status", "created")
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("deleteMeter", call -> {
			var params = call.getRequest().getParams();
			int meterId = JsonUtils.getAsInt(params, "meterId");
			boolean deleted = this.deleteMeter(meterId);
			var result = JsonUtils.buildJsonObject()
					.addProperty("meterId", meterId)
					.addProperty("deleted", deleted)
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Serial Port CRUD APIs
		builder.handleRequest("getSerialPorts", call -> {
			var records = this.getAllSerialPorts();
			var result = JsonUtils.buildJsonObject()
					.add("ports", JsonUtils.generateJsonArray(records, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("createSerialPort", call -> {
			var params = call.getRequest().getParams();
			Map<String, Object> portData = new HashMap<>();
			portData.put("display_name", JsonUtils.getAsString(params, "display_name"));
			portData.put("port", JsonUtils.getAsString(params, "port"));
			portData.put("baud_rate", JsonUtils.getAsInt(params, "baud_rate"));
			portData.put("data_bits", JsonUtils.getAsInt(params, "data_bits"));
			portData.put("stop_bits", JsonUtils.getAsDouble(params, "stop_bits"));
			portData.put("parity", JsonUtils.getAsString(params, "parity"));
			
			int newPortId = this.createSerialPort(portData);
			var result = JsonUtils.buildJsonObject()
					.addProperty("portId", newPortId)
					.addProperty("status", "created")
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("deleteSerialPort", call -> {
			var params = call.getRequest().getParams();
			int portId = JsonUtils.getAsInt(params, "portId");
			boolean deleted = this.deleteSerialPort(portId);
			var result = JsonUtils.buildJsonObject()
					.addProperty("portId", portId)
					.addProperty("deleted", deleted)
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Energy report API
		builder.handleRequest("getEnergyReport", call -> {
			var params = call.getRequest().getParams();
			int year = JsonUtils.getAsInt(params, "year");
			int month = JsonUtils.getAsInt(params, "month");
			
			var records = this.getEnergyReport(year, month);
			var result = JsonUtils.buildJsonObject()
					.addProperty("year", year)
					.addProperty("month", month)
					.add("records", JsonUtils.generateJsonArray(records, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Profile config API
		builder.handleRequest("getProfileConfig", call -> {
			var config = this.getProfileConfig();
			var result = this.toJson(config);
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		builder.handleRequest("updateProfileConfig", call -> {
			var params = call.getRequest().getParams();
			Map<String, Object> config = new HashMap<>();
			config.put("survey", JsonUtils.getAsString(params, "survey"));
			config.put("interval_minutes", JsonUtils.getAsInt(params, "interval_minutes"));
			config.put("first_read_delay_minutes", JsonUtils.getAsInt(params, "first_read_delay_minutes"));
			config.put("retry_minutes", JsonUtils.getAsInt(params, "retry_minutes"));
			config.put("finalize_after_minutes", JsonUtils.getAsInt(params, "finalize_after_minutes"));
			config.put("max_records", JsonUtils.getAsInt(params, "max_records"));
			config.put("field_timestamp", JsonUtils.getAsString(params, "field_timestamp"));
			config.put("field_record_status", JsonUtils.getAsString(params, "field_record_status"));
			config.put("field_total_exp_wh", JsonUtils.getAsString(params, "field_total_exp_wh"));
			config.put("field_total_imp_wh", JsonUtils.getAsString(params, "field_total_imp_wh"));
			config.put("field_total_exp_va", JsonUtils.getAsString(params, "field_total_exp_va"));
			config.put("field_total_imp_va", JsonUtils.getAsString(params, "field_total_imp_va"));
			
			boolean updated = this.updateProfileConfig(config);
			var result = JsonUtils.buildJsonObject()
					.addProperty("updated", updated)
					.add("config", this.toJson(config))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Meter status API
		builder.handleRequest("getMeterStatus", call -> {
			var statusList = this.getAllMeterStatus();
			var result = JsonUtils.buildJsonObject()
					.add("status", JsonUtils.generateJsonArray(statusList, this::toJson))
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		
		// Read profile from meter API
		builder.handleRequest("readProfileFromMeter", call -> {
			var params = call.getRequest().getParams();
			String meterId = JsonUtils.getAsString(params, "meterId");
			String startDate = JsonUtils.getAsString(params, "startDate");
			String endDate = JsonUtils.getAsString(params, "endDate");
			String survey = JsonUtils.getAsString(params, "survey");
			
			JsonObject meterResult = this.readProfileFromMeter(meterId, startDate, endDate, survey);
			var result = JsonUtils.buildJsonObject()
					.addProperty("meterId", meterId)
					.add("data", meterResult != null ? meterResult : new JsonObject())
					.build();
			return new io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
	}
	
	private JsonObject toJson(Map<String, Object> map) {
		JsonObject json = new JsonObject();
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			if (entry.getValue() instanceof String) {
				json.addProperty(entry.getKey(), (String) entry.getValue());
			} else if (entry.getValue() instanceof Number) {
				json.addProperty(entry.getKey(), (Number) entry.getValue());
			} else if (entry.getValue() instanceof Boolean) {
				json.addProperty(entry.getKey(), (Boolean) entry.getValue());
			}
		}
		return json;
	}
	
	private JsonObject toJson(MeterStatus status) {
		JsonObject json = new JsonObject();
		json.addProperty("meterId", status.meterId);
		json.addProperty("status", status.status);
		json.addProperty("lastUpdateTime", status.lastUpdateTime);
		if (status.lastReadingValue != null) {
			json.addProperty("lastReadingValue", status.lastReadingValue);
		}
		if (status.errorMessage != null) {
			json.addProperty("errorMessage", status.errorMessage);
		}
		json.addProperty("readingCount", status.readingCount);
		return json;
	}

	/**
	 * Update meter status when billing task reads from meter
	 */
	public void updateMeterStatus(String meterId, String status, Double lastReadingValue, String errorMessage) {
		MeterStatus meterStatus = this.meterStatusMap.computeIfAbsent(meterId, k -> new MeterStatus(meterId));
		meterStatus.status = status;
		meterStatus.lastReadingValue = lastReadingValue;
		meterStatus.errorMessage = errorMessage;
		meterStatus.lastUpdateTime = Instant.now().toString();
		meterStatus.readingCount++;

		// Notify all listeners
		for (MeterStatusListener listener : this.statusListeners) {
			try {
				listener.onMeterStatusUpdate(List.of(meterStatus));
			} catch (Exception e) {
				this.log.error("Error notifying status listener: {}", e.getMessage());
			}
		}
	}

	/**
	 * Remove meter status when meter is deleted
	 */
	public void removeMeterStatus(String meterId) {
		this.meterStatusMap.remove(meterId);
		this.log.info("Removed meter status for meter {}", meterId);
	}

	/**
	 * Initialize meter status for a new meter
	 */
	public void initializeMeterStatus(String meterId) {
		MeterStatus meterStatus = new MeterStatus(meterId);
		meterStatus.status = "pending";
		this.meterStatusMap.put(meterId, meterStatus);
		this.log.info("Initialized meter status for new meter {}", meterId);
	}

	/**
	 * Sync meter status map with current meters in database
	 * Removes status for deleted meters, adds pending status for new meters
	 */
	public void syncMeterStatusWithDatabase() {
		try {
			List<Map<String, Object>> meters = getAllMeters();
			java.util.Set<String> currentMeterIds = new java.util.HashSet<>();
			
			for (Map<String, Object> meter : meters) {
				String meterId = "meter" + meter.get("meter_id");
				currentMeterIds.add(meterId);
				
				// Add pending status for new meters
				if (!this.meterStatusMap.containsKey(meterId)) {
					initializeMeterStatus(meterId);
				}
			}
			
			// Remove status for deleted meters
			this.meterStatusMap.keySet().removeIf(meterId -> !currentMeterIds.contains(meterId));
			
		} catch (SQLException e) {
			this.log.error("Error syncing meter status with database: {}", e.getMessage());
		}
	}

	/**
	 * Get current meter status for all meters
	 */
	public List<MeterStatus> getAllMeterStatus() {
		return new ArrayList<>(this.meterStatusMap.values());
	}

	/**
	 * Get meter status for specific meter
	 */
	public MeterStatus getMeterStatus(String meterId) {
		return this.meterStatusMap.get(meterId);
	}

	/**
	 * Add a status listener for streaming
	 */
	public void addStatusListener(MeterStatusListener listener) {
		this.statusListeners.add(listener);
	}

	/**
	 * Remove a status listener
	 */
	public void removeStatusListener(MeterStatusListener listener) {
		this.statusListeners.remove(listener);
	}

	/**
	 * Meter status data class
	 */
	public static class MeterStatus {
		public String meterId;
		public String status; // "connected", "reading", "error", "disconnected"
		public String lastUpdateTime;
		public Double lastReadingValue;
		public String errorMessage;
		public int readingCount;

		public MeterStatus(String meterId) {
			this.meterId = meterId;
			this.status = "unknown";
			this.lastUpdateTime = Instant.now().toString();
			this.readingCount = 0;
		}
	}

	/**
	 * Interface for meter status listeners
	 */
	public interface MeterStatusListener {
		void onMeterStatusUpdate(List<MeterStatus> statusList);
	}

	// Sequence number for SSE events
	private final AtomicInteger statusSequence = new AtomicInteger(0);
	
	// Meter status history (last 1000 entries)
	private final CopyOnWriteArrayList<String> statusHistory = new CopyOnWriteArrayList<>();
	
	/**
	 * Publish meter status to all listeners and store in history
	 */
	public void publishMeterStatus(List<MeterStatus> meterStatusList, Instant slotTimestamp) {
		JsonObject payload = new JsonObject();
		payload.addProperty("task_id", this.id());
		
		JsonArray statusArray = new JsonArray();
		for (MeterStatus status : meterStatusList) {
			JsonObject statusObj = new JsonObject();
			statusObj.addProperty("meter_id", status.meterId);
			statusObj.addProperty("status", status.status);
			statusObj.addProperty("updated_at", status.lastUpdateTime);
			if (status.lastReadingValue != null) {
				statusObj.addProperty("last_reading_value", status.lastReadingValue);
			}
			if (status.errorMessage != null) {
				statusObj.addProperty("error", status.errorMessage);
			}
			statusArray.add(statusObj);
		}
		payload.add("meter_status", statusArray);
		
		if (slotTimestamp != null) {
			payload.addProperty("slot_ts", DateTimeFormatter.ISO_INSTANT.format(slotTimestamp));
		}
		
		int seq = this.statusSequence.incrementAndGet();
		JsonObject event = new JsonObject();
		event.addProperty("id", seq);
		event.addProperty("event", "meter_status");
		event.add("data", payload);
		
		String encoded = event.toString();
		
		// Store in history
		this.statusHistory.add(encoded);
		if (this.statusHistory.size() > 1000) {
			this.statusHistory.remove(0);
		}
		
		// Notify all listeners
		for (MeterStatusListener listener : this.statusListeners) {
			try {
				listener.onMeterStatusUpdate(meterStatusList);
			} catch (Exception e) {
				this.log.error("Error notifying status listener: {}", e.getMessage());
			}
		}
	}
	
	/**
	 * Get status history
	 */
	public List<String> getStatusHistory() {
		return new ArrayList<>(this.statusHistory);
	}
	
	/**
	 * Get database connection
	 */
	public Connection getConnection() throws SQLException {
		return DriverManager.getConnection(this.dbUrl, this.dbUser, this.dbPassword);
	}
	
	/**
	 * Get all meters from database
	 */
	public List<Map<String, Object>> getAllMeters() throws SQLException {
		List<Map<String, Object>> meters = new ArrayList<>();
		String sql = "SELECT id, serial_number, meter_name, username, password, role_id, source_id, " +
				"serial_port_config_id, type, model, outstation, enabled FROM meters WHERE enabled = true";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql);
				ResultSet rs = stmt.executeQuery()) {
			
			while (rs.next()) {
				Map<String, Object> meter = new HashMap<>();
				meter.put("meter_id", rs.getInt("id"));
				meter.put("serial_number", rs.getObject("serial_number"));
				meter.put("meter_name", rs.getString("meter_name"));
				meter.put("username", rs.getString("username"));
				meter.put("password", rs.getString("password"));
				meter.put("role_id", rs.getObject("role_id"));
				meter.put("source_id", rs.getObject("source_id"));
				meter.put("serial_port_config_id", rs.getObject("serial_port_config_id"));
				meter.put("type", rs.getString("type"));
				meter.put("model", rs.getString("model"));
				meter.put("outstation", rs.getObject("outstation"));
				meter.put("enabled", rs.getBoolean("enabled"));
				meters.add(meter);
			}
		}
		return meters;
	}

	/**
	 * Get one meter by ID
	 */
	public Map<String, Object> getMeterById(int meterId) throws SQLException {
		String sql = "SELECT id, serial_number, meter_name, username, password, role_id, source_id, " +
				"serial_port_config_id, type, model, outstation, enabled FROM meters WHERE id = ? AND enabled = true";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, meterId);
			ResultSet rs = stmt.executeQuery();
			
			if (rs.next()) {
				Map<String, Object> meter = new HashMap<>();
				meter.put("meter_id", rs.getInt("id"));
				meter.put("serial_number", rs.getObject("serial_number"));
				meter.put("meter_name", rs.getString("meter_name"));
				meter.put("username", rs.getString("username"));
				meter.put("password", rs.getString("password"));
				meter.put("role_id", rs.getObject("role_id"));
				meter.put("source_id", rs.getObject("source_id"));
				meter.put("serial_port_config_id", rs.getObject("serial_port_config_id"));
				meter.put("type", rs.getString("type"));
				meter.put("model", rs.getString("model"));
				meter.put("outstation", rs.getObject("outstation"));
				meter.put("enabled", rs.getBoolean("enabled"));
				return meter;
			}
		}
		return null;
	}

	/**
	 * Create a meter in PostgreSQL and create corresponding OSGi component
	 */
	public int createMeter(Map<String, Object> meterData) throws SQLException, OpenemsNamedException {
		// First insert into database
		String sql = "INSERT INTO meters (serial_number, meter_name, username, password, role_id, source_id, " +
				"serial_port_config_id, type, model, outstation, enabled) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true) RETURNING id";
		
		int meterId;
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setObject(1, meterData.get("serial_number"));
			stmt.setString(2, (String) meterData.get("meter_name"));
			stmt.setString(3, (String) meterData.get("username"));
			stmt.setString(4, (String) meterData.get("password"));
			stmt.setObject(5, meterData.get("role_id"));
			stmt.setObject(6, meterData.get("source_id"));
			stmt.setObject(7, meterData.get("serial_port_config_id"));
			stmt.setString(8, (String) meterData.get("type"));
			stmt.setString(9, (String) meterData.get("model"));
			stmt.setObject(10, meterData.get("outstation"));
			
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				meterId = rs.getInt("id");
			} else {
				throw new SQLException("Failed to create meter in database");
			}
		}
		
		// Then create OSGi component for the meter
		try {
			Dictionary<String, Object> props = new Hashtable<>();
			props.put("id", "meter" + meterId);
			props.put("alias", meterData.get("meter_name") != null ? meterData.get("meter_name") : "Meter " + meterId);
			props.put("enabled", true);
			props.put("serial_number", meterData.get("serial_number"));
			props.put("username", meterData.get("username"));
			props.put("password", meterData.get("password"));
			props.put("energyRole", meterData.get("role_id") != null ? getRoleName((Integer) meterData.get("role_id")) : "MAIN");
			props.put("energySourceType", meterData.get("source_id") != null ? getSourceTypeName((Integer) meterData.get("source_id")) : "NONE");
			props.put("enablePostgreSqlSync", true);
			props.put("postgreSqlHost", this.config.dbHost());
			props.put("postgreSqlPort", this.config.dbPort());
			props.put("postgreSqlDatabase", this.config.dbName());
			props.put("postgreSqlUser", this.config.dbUser());
			props.put("postgreSqlPassword", this.config.dbPassword());
			if (meterData.get("serial_port_config_id") != null) {
				props.put("bridge_id", "bridge" + meterData.get("serial_port_config_id"));
			}
			
			Configuration newConfig = this.cm.createFactoryConfiguration(METER_FACTORY_PID, "?");
			newConfig.update(props);
			this.log.info("Created OSGi component for meter [{}] with PID [{}]", meterId, newConfig.getPid());
		} catch (Exception e) {
			this.log.error("Failed to create OSGi component for meter [{}]: {}", meterId, e.getMessage());
			// Don't throw - the meter is already in the database
		}
		
		return meterId;
	}
	
	/**
	 * Delete a meter (soft delete in DB + delete OSGi component)
	 */
	public boolean deleteMeter(int meterId) throws SQLException, OpenemsNamedException {
		// Soft delete in database
		String sql = "UPDATE meters SET enabled = false WHERE id = ?";
		
		boolean dbUpdated;
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, meterId);
			dbUpdated = stmt.executeUpdate() > 0;
		}
		
		// Delete OSGi component
		if (dbUpdated) {
			try {
				String filter = String.format("(%s=%s)", ConfigurationAdmin.SERVICE_FACTORYPID, METER_FACTORY_PID);
				Configuration[] configs = this.cm.listConfigurations(filter);
				if (configs != null) {
					for (Configuration config : configs) {
						Dictionary<String, Object> props = config.getProperties();
						if (props != null) {
							String id = (String) props.get("id");
							if (id != null && id.equals("meter" + meterId)) {
								config.delete();
								this.log.info("Deleted OSGi component for meter [{}]", meterId);
								break;
							}
						}
					}
				}
			} catch (Exception e) {
				this.log.error("Failed to delete OSGi component for meter [{}]: {}", meterId, e.getMessage());
			}
		}
		
		return dbUpdated;
	}
	
	/**
	 * Create a serial port in PostgreSQL and create corresponding Bridge.EDMI OSGi component
	 */
	public int createSerialPort(Map<String, Object> portData) throws SQLException, OpenemsNamedException {
		// First insert into database
		String sql = "INSERT INTO serial_port_configs (display_name, port, baud_rate, data_bits, stop_bits, parity) " +
				"VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
		
		int portId;
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, (String) portData.get("display_name"));
			stmt.setString(2, (String) portData.get("port"));
			stmt.setInt(3, (Integer) portData.get("baud_rate"));
			stmt.setInt(4, (Integer) portData.get("data_bits"));
			stmt.setDouble(5, (Double) portData.get("stop_bits"));
			stmt.setString(6, (String) portData.get("parity"));
			
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				portId = rs.getInt("id");
			} else {
				throw new SQLException("Failed to create serial port in database");
			}
		}
		
		// Then create OSGi component for the bridge
		try {
			Dictionary<String, Object> props = new Hashtable<>();
			props.put("id", "bridge" + portId);
			props.put("alias", portData.get("display_name") != null ? portData.get("display_name") : "Bridge " + portId);
			props.put("enabled", true);
			props.put("portName", portData.get("port"));
			props.put("baudRate", portData.get("baud_rate"));
			props.put("databits", portData.get("data_bits"));
			props.put("stopbits", portData.get("stop_bits"));
			props.put("parity", portData.get("parity"));
			
			Configuration newConfig = this.cm.createFactoryConfiguration(BRIDGE_FACTORY_PID, "?");
			newConfig.update(props);
			this.log.info("Created OSGi component for bridge [{}] with PID [{}]", portId, newConfig.getPid());
		} catch (Exception e) {
			this.log.error("Failed to create OSGi component for bridge [{}]: {}", portId, e.getMessage());
		}
		
		return portId;
	}
	
	/**
	 * Delete a serial port (delete from DB + delete OSGi component)
	 */
	public boolean deleteSerialPort(int portId) throws SQLException, OpenemsNamedException {
		// Delete from database
		String sql = "DELETE FROM serial_port_configs WHERE id = ?";
		
		boolean dbDeleted;
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, portId);
			dbDeleted = stmt.executeUpdate() > 0;
		}
		
		// Delete OSGi component
		if (dbDeleted) {
			try {
				String filter = String.format("(%s=%s)", ConfigurationAdmin.SERVICE_FACTORYPID, BRIDGE_FACTORY_PID);
				Configuration[] configs = this.cm.listConfigurations(filter);
				if (configs != null) {
					for (Configuration config : configs) {
						Dictionary<String, Object> props = config.getProperties();
						if (props != null) {
							String id = (String) props.get("id");
							if (id != null && id.equals("bridge" + portId)) {
								config.delete();
								this.log.info("Deleted OSGi component for bridge [{}]", portId);
								break;
							}
						}
					}
				}
			} catch (Exception e) {
				this.log.error("Failed to delete OSGi component for bridge [{}]: {}", portId, e.getMessage());
			}
		}
		
		return dbDeleted;
	}
	
	/**
	 * Get all serial ports from database
	 */
	public List<Map<String, Object>> getAllSerialPorts() throws SQLException {
		List<Map<String, Object>> ports = new ArrayList<>();
		String sql = "SELECT id, display_name, port, baud_rate, data_bits, stop_bits, parity FROM serial_port_configs";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql);
				ResultSet rs = stmt.executeQuery()) {
			
			while (rs.next()) {
				Map<String, Object> port = new HashMap<>();
				port.put("id", rs.getInt("id"));
				port.put("display_name", rs.getString("display_name"));
				port.put("port", rs.getString("port"));
				port.put("baud_rate", rs.getInt("baud_rate"));
				port.put("data_bits", rs.getInt("data_bits"));
				port.put("stop_bits", rs.getDouble("stop_bits"));
				port.put("parity", rs.getString("parity"));
				ports.add(port);
			}
		}
		return ports;
	}
	
	/**
	 * Get billing data from database
	 */
	public List<Map<String, Object>> getBillingData(Integer meterId, String startDate, String endDate, int limit) throws SQLException {
		List<Map<String, Object>> data = new ArrayList<>();
		StringBuilder sqlBuilder = new StringBuilder(
			"SELECT id, meter_id, time_stamp, time_stamp_utc, total_energy_tot_imp_wh, " +
			"total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va, record_status " +
			"FROM reading_value WHERE 1=1");
		
		if (meterId != null) {
			sqlBuilder.append(" AND meter_id = ?");
		}
		if (startDate != null) {
			sqlBuilder.append(" AND time_stamp_utc >= ?");
		}
		if (endDate != null) {
			sqlBuilder.append(" AND time_stamp_utc <= ?");
		}
		sqlBuilder.append(" ORDER BY time_stamp_utc DESC LIMIT ?");
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
			
			int paramIndex = 1;
			if (meterId != null) {
				stmt.setInt(paramIndex++, meterId);
			}
			if (startDate != null) {
				stmt.setTimestamp(paramIndex++, Timestamp.valueOf(startDate));
			}
			if (endDate != null) {
				stmt.setTimestamp(paramIndex++, Timestamp.valueOf(endDate));
			}
			stmt.setInt(paramIndex, limit);
			
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Map<String, Object> row = new HashMap<>();
				row.put("id", rs.getInt("id"));
				row.put("meter_id", rs.getInt("meter_id"));
				row.put("time_stamp", rs.getTimestamp("time_stamp").toString());
				row.put("time_stamp_utc", rs.getTimestamp("time_stamp_utc").toString());
				row.put("total_energy_tot_imp_wh", rs.getDouble("total_energy_tot_imp_wh"));
				row.put("total_energy_tot_exp_wh", rs.getDouble("total_energy_tot_exp_wh"));
				row.put("total_energy_tot_imp_va", rs.getDouble("total_energy_tot_imp_va"));
				row.put("total_energy_tot_exp_va", rs.getDouble("total_energy_tot_exp_va"));
				row.put("record_status", rs.getDouble("record_status"));
				data.add(row);
			}
		}
		return data;
	}
	
	/**
	 * Get energy calculation report from database
	 */
	public List<Map<String, Object>> getEnergyReport(int year, int month) throws SQLException {
		List<Map<String, Object>> report = new ArrayList<>();
		String sql = "SELECT id, meter_id, time_stamp, time_stamp_utc, total_energy_tot_imp_wh, " +
				"total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va, " +
				"bess_energy_tot_imp_wh, bess_energy_tot_exp_wh, rts_energy_tot_imp_wh, rts_energy_tot_exp_wh, " +
				"main_energy_tot_imp_wh, main_energy_tot_exp_wh, backup_energy_tot_imp_wh, backup_energy_tot_exp_wh, " +
				"record_status " +
				"FROM energy_calculation_report WHERE EXTRACT(YEAR FROM time_stamp_utc) = ? AND EXTRACT(MONTH FROM time_stamp_utc) = ? " +
				"ORDER BY time_stamp_utc DESC";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, year);
			stmt.setInt(2, month);
			
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Map<String, Object> row = new HashMap<>();
				row.put("id", rs.getInt("id"));
				row.put("meter_id", rs.getInt("meter_id"));
				row.put("time_stamp", rs.getTimestamp("time_stamp").toString());
				row.put("time_stamp_utc", rs.getTimestamp("time_stamp_utc").toString());
				row.put("total_energy_tot_imp_wh", rs.getDouble("total_energy_tot_imp_wh"));
				row.put("total_energy_tot_exp_wh", rs.getDouble("total_energy_tot_exp_wh"));
				row.put("total_energy_tot_imp_va", rs.getDouble("total_energy_tot_imp_va"));
				row.put("total_energy_tot_exp_va", rs.getDouble("total_energy_tot_exp_va"));
				row.put("bess_energy_tot_imp_wh", rs.getDouble("bess_energy_tot_imp_wh"));
				row.put("bess_energy_tot_exp_wh", rs.getDouble("bess_energy_tot_exp_wh"));
				row.put("rts_energy_tot_imp_wh", rs.getDouble("rts_energy_tot_imp_wh"));
				row.put("rts_energy_tot_exp_wh", rs.getDouble("rts_energy_tot_exp_wh"));
				row.put("main_energy_tot_imp_wh", rs.getDouble("main_energy_tot_imp_wh"));
				row.put("main_energy_tot_exp_wh", rs.getDouble("main_energy_tot_exp_wh"));
				row.put("backup_energy_tot_imp_wh", rs.getDouble("backup_energy_tot_imp_wh"));
				row.put("backup_energy_tot_exp_wh", rs.getDouble("backup_energy_tot_exp_wh"));
				row.put("record_status", rs.getDouble("record_status"));
				report.add(row);
			}
		}
		return report;
	}
	
	/**
	 * Get profile ingestion settings directly from EdmiBridge
	 */
	public Map<String, Object> getProfileIngestionSettings(String bridgeId) {
		Map<String, Object> result = new HashMap<>();
		try {
			// Get the bridge component from ComponentManager
			OpenemsComponent component = this.componentManager.getComponent(bridgeId);
			if (component == null) {
				this.log.error("Bridge [{}] not found", bridgeId);
				return result;
			}
			
			// Use reflection to get ProfileIngestionSettings from bridge
			try {
				java.lang.reflect.Method method = component.getClass().getMethod("getProfileIngestionSettings");
				Object settings = method.invoke(component);
				
				if (settings != null) {
					// Convert ProfileIngestionSettings to Map
					java.lang.reflect.Method surveyMethod = settings.getClass().getMethod("survey");
					java.lang.reflect.Method intervalMethod = settings.getClass().getMethod("intervalMinutes");
					java.lang.reflect.Method firstReadDelayMethod = settings.getClass().getMethod("firstReadDelayMinutes");
					java.lang.reflect.Method retryMethod = settings.getClass().getMethod("retryMinutes");
					java.lang.reflect.Method finalizeMethod = settings.getClass().getMethod("finalizeAfterMinutes");
					java.lang.reflect.Method maxRecordsMethod = settings.getClass().getMethod("maxRecords");
					java.lang.reflect.Method timestampAliasesMethod = settings.getClass().getMethod("timestampAliases");
					java.lang.reflect.Method recordStatusAliasesMethod = settings.getClass().getMethod("recordStatusAliases");
					java.lang.reflect.Method importWhAliasesMethod = settings.getClass().getMethod("importWhAliases");
					java.lang.reflect.Method exportWhAliasesMethod = settings.getClass().getMethod("exportWhAliases");
					java.lang.reflect.Method importVahAliasesMethod = settings.getClass().getMethod("importVahAliases");
					java.lang.reflect.Method exportVahAliasesMethod = settings.getClass().getMethod("exportVahAliases");
					
					result.put("survey", String.format("0x%04X", surveyMethod.invoke(settings)));
					result.put("interval_minutes", intervalMethod.invoke(settings));
					result.put("first_read_delay_minutes", firstReadDelayMethod.invoke(settings));
					result.put("retry_minutes", retryMethod.invoke(settings));
					result.put("finalize_after_minutes", finalizeMethod.invoke(settings));
					result.put("max_records", maxRecordsMethod.invoke(settings));
					result.put("timestamp_aliases", timestampAliasesMethod.invoke(settings));
					result.put("record_status_aliases", recordStatusAliasesMethod.invoke(settings));
					result.put("import_wh_aliases", importWhAliasesMethod.invoke(settings));
					result.put("export_wh_aliases", exportWhAliasesMethod.invoke(settings));
					result.put("import_vah_aliases", importVahAliasesMethod.invoke(settings));
					result.put("export_vah_aliases", exportVahAliasesMethod.invoke(settings));
				}
			} catch (NoSuchMethodException e) {
				this.log.warn("Method getProfileIngestionSettings not found on bridge [{}]", bridgeId);
			}
		} catch (Exception e) {
			this.log.error("Error getting profile settings from bridge [{}]: {}", bridgeId, e.getMessage());
		}
		return result;
	}
	public Map<String, Object> getProfileConfig() throws SQLException {
		Map<String, Object> config = new HashMap<>();
		String sql = "SELECT survey, interval_minutes, first_read_delay_minutes, retry_minutes, " +
				"finalize_after_minutes, max_records, field_timestamp, field_record_status, " +
				"field_total_exp_wh, field_total_imp_wh, field_total_exp_va, field_total_imp_va " +
				"FROM profile_config LIMIT 1";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql);
				ResultSet rs = stmt.executeQuery()) {
			
			if (rs.next()) {
				config.put("survey", rs.getString("survey"));
				config.put("interval_minutes", rs.getInt("interval_minutes"));
				config.put("first_read_delay_minutes", rs.getInt("first_read_delay_minutes"));
				config.put("retry_minutes", rs.getInt("retry_minutes"));
				config.put("finalize_after_minutes", rs.getInt("finalize_after_minutes"));
				config.put("max_records", rs.getInt("max_records"));
				config.put("field_timestamp", rs.getString("field_timestamp"));
				config.put("field_record_status", rs.getString("field_record_status"));
				config.put("field_total_exp_wh", rs.getString("field_total_exp_wh"));
				config.put("field_total_imp_wh", rs.getString("field_total_imp_wh"));
				config.put("field_total_exp_va", rs.getString("field_total_exp_va"));
				config.put("field_total_imp_va", rs.getString("field_total_imp_va"));
			} else {
				// Return defaults
				config.put("survey", "LS02");
				config.put("interval_minutes", 30);
				config.put("first_read_delay_minutes", 5);
				config.put("retry_minutes", 5);
				config.put("finalize_after_minutes", 30);
				config.put("max_records", 5);
				config.put("field_timestamp", "DateTime,time_stamp,timestamp");
				config.put("field_record_status", "Record Status,record_status,EFA Status");
				config.put("field_total_exp_wh", "Total Energy Channel 1 @R,Total Energy Channel 1 @RUnified,Total Energy Tot EXP Wh @,To EXP Wh,Tot EXP Wh");
				config.put("field_total_imp_wh", "Total Energy Channel 2 @R,Total Energy Channel 2 @RUnified,Total Energy Tot IMP Wh @,To IMP Wh,Tot IMP Wh");
				config.put("field_total_exp_va", "Total Energy Channel 3 @R,Total Energy Channel 3 @RUnified,Total Energy Tot EXP va @,To EXP va,Tot EXP va");
				config.put("field_total_imp_va", "Total Energy Channel 4 @R,Total Energy Channel 4 @RUnified,Total Energy Tot IMP va @,To IMP va,Tot IMP va");
			}
		}
		return config;
	}
	
	/**
	 * Update profile configuration in database
	 */
	public boolean updateProfileConfig(Map<String, Object> config) throws SQLException {
		String sql = "INSERT INTO profile_config (survey, interval_minutes, first_read_delay_minutes, retry_minutes, " +
				"finalize_after_minutes, max_records, field_timestamp, field_record_status, " +
				"field_total_exp_wh, field_total_imp_wh, field_total_exp_va, field_total_imp_va) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (id) DO UPDATE SET " +
				"survey = EXCLUDED.survey, " +
				"interval_minutes = EXCLUDED.interval_minutes, " +
				"first_read_delay_minutes = EXCLUDED.first_read_delay_minutes, " +
				"retry_minutes = EXCLUDED.retry_minutes, " +
				"finalize_after_minutes = EXCLUDED.finalize_after_minutes, " +
				"max_records = EXCLUDED.max_records, " +
				"field_timestamp = EXCLUDED.field_timestamp, " +
				"field_record_status = EXCLUDED.field_record_status, " +
				"field_total_exp_wh = EXCLUDED.field_total_exp_wh, " +
				"field_total_imp_wh = EXCLUDED.field_total_imp_wh, " +
				"field_total_exp_va = EXCLUDED.field_total_exp_va, " +
				"field_total_imp_va = EXCLUDED.field_total_imp_va, " +
				"updated_at = NOW()";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, (String) config.getOrDefault("survey", "LS02"));
			stmt.setInt(2, (Integer) config.getOrDefault("interval_minutes", 30));
			stmt.setInt(3, (Integer) config.getOrDefault("first_read_delay_minutes", 5));
			stmt.setInt(4, (Integer) config.getOrDefault("retry_minutes", 5));
			stmt.setInt(5, (Integer) config.getOrDefault("finalize_after_minutes", 30));
			stmt.setInt(6, (Integer) config.getOrDefault("max_records", 5));
			stmt.setString(7, (String) config.getOrDefault("field_timestamp", "DateTime,time_stamp,timestamp"));
			stmt.setString(8, (String) config.getOrDefault("field_record_status", "Record Status,record_status,EFA Status"));
			stmt.setString(9, (String) config.getOrDefault("field_total_exp_wh", "Total Energy Channel 1 @R,Total Energy Channel 1 @RUnified,Total Energy Tot EXP Wh @,To EXP Wh,Tot EXP Wh"));
			stmt.setString(10, (String) config.getOrDefault("field_total_imp_wh", "Total Energy Channel 2 @R,Total Energy Channel 2 @RUnified,Total Energy Tot IMP Wh @,To IMP Wh,Tot IMP Wh"));
			stmt.setString(11, (String) config.getOrDefault("field_total_exp_va", "Total Energy Channel 3 @R,Total Energy Channel 3 @RUnified,Total Energy Tot EXP va @,To EXP va,Tot EXP va"));
			stmt.setString(12, (String) config.getOrDefault("field_total_imp_va", "Total Energy Channel 4 @R,Total Energy Channel 4 @RUnified,Total Energy Tot IMP va @,To IMP va,Tot IMP va"));
			
			return stmt.executeUpdate() > 0;
		}
	}
	
	/**
	 * Get profile data from database for a meter and time range
	 */
	public List<Map<String, Object>> getProfileData(Integer meterId, String startDate, String endDate, int maxRecords) throws SQLException {
		List<Map<String, Object>> data = new ArrayList<>();
		String sql = "SELECT time_stamp, record_status, total_energy_tot_imp_wh, " +
				"total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va " +
				"FROM meter_profile WHERE meter_id = ? AND time_stamp BETWEEN ? AND ? " +
				"ORDER BY time_stamp DESC LIMIT ?";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, "meter" + meterId);
			stmt.setTimestamp(2, Timestamp.valueOf(startDate));
			stmt.setTimestamp(3, Timestamp.valueOf(endDate));
			stmt.setInt(4, maxRecords);
			
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Map<String, Object> row = new HashMap<>();
				row.put("time_stamp", rs.getTimestamp("time_stamp").toString());
				row.put("record_status", rs.getDouble("record_status"));
				row.put("total_energy_tot_imp_wh", rs.getDouble("total_energy_tot_imp_wh"));
				row.put("total_energy_tot_exp_wh", rs.getDouble("total_energy_tot_exp_wh"));
				row.put("total_energy_tot_imp_va", rs.getDouble("total_energy_tot_imp_va"));
				row.put("total_energy_tot_exp_va", rs.getDouble("total_energy_tot_exp_va"));
				data.add(row);
			}
		}
		return data;
	}
	
	/**
	 * Write profile data to database
	 */
	public void writeProfileData(int meterId, String timeStamp, double recordStatus, 
			double totalImpWh, double totalExpWh, double totalImpVa, double totalExpVa) throws SQLException {
		String sql = "INSERT INTO meter_profile (controller_id, meter_id, time_stamp, record_status, " +
				"total_energy_tot_imp_wh, total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (controller_id, meter_id, time_stamp) DO UPDATE SET " +
				"record_status = EXCLUDED.record_status, " +
				"total_energy_tot_imp_wh = EXCLUDED.total_energy_tot_imp_wh, " +
				"total_energy_tot_exp_wh = EXCLUDED.total_energy_tot_exp_wh, " +
				"total_energy_tot_imp_va = EXCLUDED.total_energy_tot_imp_va, " +
				"total_energy_tot_exp_va = EXCLUDED.total_energy_tot_exp_va";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, this.id());
			stmt.setString(2, "meter" + meterId);
			stmt.setTimestamp(3, Timestamp.valueOf(timeStamp));
			stmt.setDouble(4, recordStatus);
			stmt.setDouble(5, totalImpWh);
			stmt.setDouble(6, totalExpWh);
			stmt.setDouble(7, totalImpVa);
			stmt.setDouble(8, totalExpVa);
			
			stmt.executeUpdate();
		}
	}
	
	/**
	 * Get current status sequence number
	 */
	public int getStatusSequence() {
		return this.statusSequence.get();
	}
	
	/**
	 * Get faults from database by time window
	 */
	public List<Map<String, Object>> getFaultsByWindow(String windowStart, String windowEnd) throws SQLException {
		List<Map<String, Object>> faults = new ArrayList<>();
		String sql = "SELECT id, meter_id, time_stamp, fault_type, fault_description, severity " +
				"FROM meter_faults WHERE time_stamp >= ? AND time_stamp <= ? " +
				"ORDER BY time_stamp DESC";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setTimestamp(1, Timestamp.valueOf(windowStart));
			stmt.setTimestamp(2, Timestamp.valueOf(windowEnd));
			
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Map<String, Object> fault = new HashMap<>();
				fault.put("id", rs.getInt("id"));
				fault.put("meter_id", rs.getInt("meter_id"));
				fault.put("time_stamp", rs.getTimestamp("time_stamp").toString());
				fault.put("fault_type", rs.getString("fault_type"));
				fault.put("fault_description", rs.getString("fault_description"));
				fault.put("severity", rs.getString("severity"));
				faults.add(fault);
			}
		}
		return faults;
	}
	
	/**
	 * Get faults from database by month
	 */
	public List<Map<String, Object>> getFaultsByMonth(int year, int month) throws SQLException {
		List<Map<String, Object>> faults = new ArrayList<>();
		String sql = "SELECT id, meter_id, time_stamp, fault_type, fault_description, severity " +
				"FROM meter_faults WHERE EXTRACT(YEAR FROM time_stamp) = ? AND EXTRACT(MONTH FROM time_stamp) = ? " +
				"ORDER BY time_stamp DESC";
		
		try (Connection conn = getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, year);
			stmt.setInt(2, month);
			
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				Map<String, Object> fault = new HashMap<>();
				fault.put("id", rs.getInt("id"));
				fault.put("meter_id", rs.getInt("meter_id"));
				fault.put("time_stamp", rs.getTimestamp("time_stamp").toString());
				fault.put("fault_type", rs.getString("fault_type"));
				fault.put("fault_description", rs.getString("fault_description"));
				fault.put("severity", rs.getString("severity"));
				faults.add(fault);
			}
		}
		return faults;
	}
	
	/**
	 * Read profile from meter using reflection
	 */
	@Override
	public JsonObject readProfileFromMeter(String meterId, String startDate, String endDate, String survey) {
		try {
			// Get the meter component from ComponentManager
			OpenemsComponent component = this.componentManager.getComponent(meterId);
			if (component == null) {
				this.log.error("Meter component [{}] not found", meterId);
				return null;
			}
			
			// Use reflection to call the readProfileAndSave method
			try {
				java.lang.reflect.Method method = component.getClass().getMethod("readProfileAndSave", String.class, String.class, String.class);
				Object result = method.invoke(component, startDate, endDate, survey);
				
				if (result instanceof JsonObject) {
					return (JsonObject) result;
				}
			} catch (NoSuchMethodException e) {
				this.log.warn("Method readProfileAndSave not found on component [{}]. Falling back to JSON-RPC.", meterId);
			}
			
			// If reflection didn't work, return null
			this.log.error("Failed to call readProfileAndSave on meter [{}]", meterId);
			return null;
			
		} catch (Exception e) {
			this.log.error("Error reading profile from meter [{}]: {}", meterId, e.getMessage());
			return null;
		}
	}
	
	private String getRoleName(int roleId) {
		// This should query the energy_roles table
		// For now, return default roles
		switch (roleId) {
			case 1: return "SOURCE";
			case 2: return "SELF_USE";
			case 3: return "MAIN";
			case 4: return "BACKUP";
			default: return "MAIN";
		}
	}
	
	private String getSourceTypeName(int sourceId) {
		// This should query the energy_sources table
		// For now, return default source types
		switch (sourceId) {
			case 1: return "BESS";
			case 2: return "RTS";
			default: return "NONE";
		}
	}
	
	@Override
	public Map<String, Object> getProfileIngestionSettings() {
		// Get from database profile_config table
		try {
			return getProfileConfig();
		} catch (SQLException e) {
			this.log.error("Error getting profile ingestion settings: {}", e.getMessage());
			return new HashMap<>();
		}
	}
	
	@Override
	public boolean updateProfileIngestionSettings(Map<String, Object> settings) {
		try {
			return updateProfileConfig(settings);
		} catch (SQLException e) {
			this.log.error("Error updating profile ingestion settings: {}", e.getMessage());
			return false;
		}
	}
	
	@Override
	public void run() {
		// This controller doesn't need to run periodically
		// It provides database access methods for other components
	}
}