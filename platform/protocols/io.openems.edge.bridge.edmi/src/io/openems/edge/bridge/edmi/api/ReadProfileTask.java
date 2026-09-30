package io.openems.edge.bridge.edmi.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atdigital.imr.EdmiDateTime;
import com.atdigital.imr.objects.ReportProfileData;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.taskmanager.Priority;

/**
 * A concrete Task for reading profile load records from an EDMI meter.
 * Writes profile data to PostgreSQL instead of InfluxDB.
 */
public class ReadProfileTask extends AbstractEdmiTask {
	private static final int PROFILE_FIELD_COUNT = 4;
	private static final ZoneId DEVICE_ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter PROFILE_TIMESTAMP_FORMATTER = DateTimeFormatter
			.ofPattern("yy-MM-dd'T'HH:mm:ss");
	private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^a-z0-9]+");

	private final String meterId;
	private final Logger log = LoggerFactory.getLogger(ReadProfileTask.class);

	private final EdmiBridge bridge;

	private final String username;
	private final String password;
	private final int serialNumber;
	private final String energyRole;
	private final String energySourceType;

	// PostgreSQL configuration
	private final String pgHost;
	private final int pgPort;
	private final String pgDatabase;
	private final String pgUser;
	private final String pgPassword;
	private final boolean enablePgWrite;

	public ReadProfileTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
			int serialNumber, String energyRole, String energySourceType) {
		this(meterId, priority, bridge, username, password, serialNumber, energyRole, energySourceType,
				"localhost", 5432, "maximeter", "maximeter_app", "", false);
	}

	public ReadProfileTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
			int serialNumber, String energyRole, String energySourceType,
			String pgHost, int pgPort, String pgDatabase, String pgUser, String pgPassword, boolean enablePgWrite) {
		super(priority);
		this.meterId = meterId;
		this.bridge = bridge;
		this.setNextRunTime(this.calculateNextRunTime());
		this.username = username;
		this.password = password;
		this.serialNumber = serialNumber;
		this.energyRole = energyRole;
		this.energySourceType = energySourceType;
		this.pgHost = pgHost;
		this.pgPort = pgPort;
		this.pgDatabase = pgDatabase;
		this.pgUser = pgUser;
		this.pgPassword = pgPassword;
		this.enablePgWrite = enablePgWrite;
	}

	@Override
	public void execute() throws Exception {
		try {
			this.log.warn("Reading Profile from meter {}", this.meterId);
			ReportProfileData recordProfile = this.readProfileFromHardware();
			if (recordProfile == null || recordProfile.getData() == null) {
				return;
			}
			var fieldMap = this.resolveProfileFields(recordProfile.getChannels());
			int processedRecords = 0;

			for (List<Object> row : recordProfile.getData()) {
				if (row == null || row.size() < PROFILE_FIELD_COUNT) {
					throw new IllegalStateException("Expected at least " + PROFILE_FIELD_COUNT
							+ " profile values but got " + (row == null ? 0 : row.size()));
				}

				LocalDateTime timestamp = this.parseProfileTimestamp(this.rowValue(row, fieldMap.timestampIndex()));
				
				// Write to PostgreSQL if enabled
				if (this.enablePgWrite) {
					this.writeToPostgreSQL(timestamp, row, fieldMap);
				}
				
				processedRecords++;
				if (processedRecords >= this.bridge.getProfileIngestionSettings().maxRecords()) {
					break;
				}
			}

		} finally {
			this.setNextRunTime(this.calculateNextRunTime());
		}
	}

	private void writeToPostgreSQL(LocalDateTime timestamp, List<Object> row, ProfileFieldMap fieldMap) {
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
			stmt.setString(2, this.meterId);
			stmt.setTimestamp(3, Timestamp.valueOf(timestamp));
			stmt.setDouble(4, this.toNumber(this.rowValue(row, fieldMap.recordStatusIndex()), "record_status").doubleValue());
			stmt.setDouble(5, this.toNumber(this.rowValue(row, fieldMap.importWhIndex()), "total_energy_tot_imp_wh").doubleValue());
			stmt.setDouble(6, this.toNumber(this.rowValue(row, fieldMap.exportWhIndex()), "total_energy_tot_exp_wh").doubleValue());
			
			// Handle optional VA fields
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
			
			stmt.setString(9, this.energyRole);
			stmt.setString(10, this.energySourceType);
			
			stmt.executeUpdate();
			this.log.debug("Wrote profile data to PostgreSQL for meter [{}] at [{}]", this.meterId, timestamp);
			
		} catch (SQLException e) {
			this.log.error("Failed to write profile data to PostgreSQL: {}", e.getMessage());
		}
	}

	private ReportProfileData readProfileFromHardware() throws Exception {
		var settings = this.bridge.getProfileIngestionSettings();
		long intervalMillis = settings.intervalMillis();
		long finalizedNow = System.currentTimeMillis() - settings.finalizeAfterMillis();
		long currentSlotEndMillis = (finalizedNow / intervalMillis) * intervalMillis;
		long currentSlotStartMillis = currentSlotEndMillis - intervalMillis;
		EdmiDateTime.ByValue from = this.toEdmiDateTime(currentSlotStartMillis);
		EdmiDateTime.ByValue to = this.toEdmiDateTime(currentSlotEndMillis);
		return (ReportProfileData) this.bridge.readProfile(from, to, this.serialNumber, this.username, this.password,
				settings.survey());
	}

	private ProfileFieldMap resolveProfileFields(List<String> channels) {
		var settings = this.bridge.getProfileIngestionSettings();
		return new ProfileFieldMap(//
				this.findChannelIndex(channels, settings.timestampAliases(), 1), //
				this.findChannelIndex(channels, settings.recordStatusAliases(), 0), //
				this.findChannelIndex(channels, settings.importWhAliases(), 2), //
				this.findChannelIndex(channels, settings.exportWhAliases(), 3), //
				this.findChannelIndex(channels, settings.importVahAliases(), -1), //
				this.findChannelIndex(channels, settings.exportVahAliases(), -1));
	}

	private int findChannelIndex(List<String> channels, List<String> aliases, int fallbackIndex) {
		if (channels == null || channels.isEmpty() || aliases == null || aliases.isEmpty()) {
			return fallbackIndex;
		}
		var normalizedAliases = aliases.stream().map(ReadProfileTask::normalize).collect(java.util.stream.Collectors.toSet());
		for (int i = 0; i < channels.size(); i++) {
			if (normalizedAliases.contains(normalize(channels.get(i)))) {
				return i;
			}
		}
		return fallbackIndex;
	}

	private Object rowValue(List<Object> row, int index) {
		if (index < 0 || index >= row.size()) {
			throw new IllegalStateException("Profile row does not contain configured field index [" + index + "]");
		}
		return row.get(index);
	}

	private LocalDateTime parseProfileTimestamp(Object rawValue) {
		String raw = rawValue == null ? "" : rawValue.toString();
		String normalized = raw.replaceFirst("(\\d{2}-\\d{2}-\\d{2}T\\d{2})-(\\d{2})-(\\d{2})", "$1:$2:$3");
		return LocalDateTime.parse(normalized, PROFILE_TIMESTAMP_FORMATTER);
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

	private EdmiDateTime.ByValue toEdmiDateTime(long epochMillis) {
		LocalDateTime timestamp = Instant.ofEpochMilli(epochMillis).atZone(DEVICE_ZONE).toLocalDateTime();
		return new EdmiDateTime.ByValue( //
				timestamp.getYear() % 100, //
				timestamp.getMonthValue(), //
				timestamp.getDayOfMonth(), //
				timestamp.getHour(), //
				timestamp.getMinute(), //
				timestamp.getSecond());
	}

	private long calculateNextRunTime() {
		var settings = this.bridge.getProfileIngestionSettings();
		long intervalMillis = settings.intervalMillis();
		long now = System.currentTimeMillis();
		return ((now / intervalMillis) + 1) * intervalMillis + settings.firstReadDelayMillis();
	}

	private static String normalize(String value) {
		return NORMALIZE_PATTERN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	private record ProfileFieldMap(int timestampIndex, int recordStatusIndex, int importWhIndex, int exportWhIndex,
			int importVahIndex, int exportVahIndex) {
	}
}
