package io.openems.edge.bridge.edmi.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.taskmanager.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A concrete Task for reading real-time registers from an EDMI meter.
 * Writes billing data to PostgreSQL instead of InfluxDB.
 */
public class ReadBillingTask extends AbstractEdmiTask {
    private static final long BILLING_INTERVAL_MILLIS = 30 * 1000L;
    private static final int BILLING_VALUE_COUNT = 14;

    private final String meterId;
    private final Logger log = LoggerFactory.getLogger(ReadBillingTask.class);
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
    
    // Status callback for streaming meter status
    private final MeterStatusCallback statusCallback;

    public ReadBillingTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
            int serialNumber, String energyRole, String energySourceType) {
        this(meterId, priority, bridge, username, password, serialNumber, energyRole, energySourceType,
                "localhost", 5432, "maximeter", "maximeter_app", "", false, null);
    }

    public ReadBillingTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
            int serialNumber, String energyRole, String energySourceType,
            String pgHost, int pgPort, String pgDatabase, String pgUser, String pgPassword, boolean enablePgWrite) {
        this(meterId, priority, bridge, username, password, serialNumber, energyRole, energySourceType,
                pgHost, pgPort, pgDatabase, pgUser, pgPassword, enablePgWrite, null);
    }

    public ReadBillingTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
            int serialNumber, String energyRole, String energySourceType,
            String pgHost, int pgPort, String pgDatabase, String pgUser, String pgPassword, 
            boolean enablePgWrite, MeterStatusCallback statusCallback) {
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
        this.statusCallback = statusCallback;
    }

    @Override
    public void execute() throws Exception {
        try {
            this.log.warn("-------------- Executing reading billing task for meter {}--------------", meterId);

            List<Object> objects = this.readBillingValuesFromHardware();
            if (objects == null || objects.size() < BILLING_VALUE_COUNT) {
                throw new IllegalStateException("Expected at least " + BILLING_VALUE_COUNT
                        + " billing values but got " + (objects == null ? 0 : objects.size()));
            }

            this.log.info("Billing values for [{}]: {}", this.meterId, objects);

            String date = objects.get(2).toString();
            String time = objects.get(3).toString();
            LocalDateTime ldt = LocalDateTime.parse(date + "T" + time);
            
            // Write to PostgreSQL if enabled
            if (this.enablePgWrite) {
                this.writeToPostgreSQL(objects, ldt);
            }
            
            // Update status callback
            if (this.statusCallback != null) {
                Double totalImpKwh = this.toNumber(objects.get(7), "total_imp_kwh").doubleValue();
                this.statusCallback.onMeterStatusUpdate(this.meterId, "connected", totalImpKwh, null);
            }
            
            this.log.info("Write billing data to PostgreSQL for [{}] at [{}]", this.meterId, ldt);
        } catch (Exception e) {
            // Update status callback with error
            if (this.statusCallback != null) {
                this.statusCallback.onMeterStatusUpdate(this.meterId, "error", null, e.getMessage());
            }
            this.log.error("Error reading billing values for meter [{}]: {}", this.meterId, e.getMessage());
        } finally {
            this.setNextRunTime(this.calculateNextRunTime());
        }
    }

    private void writeToPostgreSQL(List<Object> objects, LocalDateTime ldt) {
        String url = String.format("jdbc:postgresql://%s:%d/%s", this.pgHost, this.pgPort, this.pgDatabase);
        String sql = "INSERT INTO reading_value (meter_id, time_stamp, time_stamp_utc, total_energy_tot_imp_wh, " +
                "total_energy_tot_exp_wh, total_energy_tot_imp_va, total_energy_tot_exp_va, record_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (meter_id, time_stamp) DO UPDATE SET " +
                "total_energy_tot_imp_wh = EXCLUDED.total_energy_tot_imp_wh, " +
                "total_energy_tot_exp_wh = EXCLUDED.total_energy_tot_exp_wh, " +
                "total_energy_tot_imp_va = EXCLUDED.total_energy_tot_imp_va, " +
                "total_energy_tot_exp_va = EXCLUDED.total_energy_tot_exp_va, " +
                "record_status = EXCLUDED.record_status";
        
        try (Connection conn = DriverManager.getConnection(url, this.pgUser, this.pgPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, Integer.parseInt(this.meterId.replaceAll("[^0-9]", "")));
            stmt.setTimestamp(2, Timestamp.valueOf(ldt));
            stmt.setTimestamp(3, Timestamp.valueOf(ldt));
            
            // Map billing values to reading_value fields
            // total_imp_kwh -> total_energy_tot_imp_wh
            stmt.setDouble(4, this.toNumber(objects.get(7), "total_imp_kwh").doubleValue());
            // total_exp_kwh -> total_energy_tot_exp_wh
            stmt.setDouble(5, this.toNumber(objects.get(12), "total_exp_kwh").doubleValue());
            // total_imp_kvar -> total_energy_tot_imp_va (approximate)
            stmt.setDouble(6, this.toNumber(objects.get(8), "total_imp_kvar").doubleValue());
            // total_exp_kvar -> total_energy_tot_exp_va (approximate)
            stmt.setDouble(7, this.toNumber(objects.get(13), "total_exp_kvar").doubleValue());
            // error_code -> record_status
            stmt.setDouble(8, this.toNumber(objects.get(1), "error_code").doubleValue());
            
            stmt.executeUpdate();
            this.log.debug("Wrote billing data to PostgreSQL for meter [{}]", this.meterId);
            
        } catch (SQLException e) {
            this.log.error("Failed to write billing data to PostgreSQL: {}", e.getMessage());
        }
    }

    public List<Object> readBillingValuesFromHardware() throws Exception {
        return this.bridge.readBillingValues(this.username, this.password, this.serialNumber);
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
                        "Billing field [" + fieldName + "] is not numeric: [" + stringValue + "]", e);
            }
        }
        throw new IllegalArgumentException("Billing field [" + fieldName + "] has unsupported type ["
                + (value == null ? "null" : value.getClass().getName()) + "]");
    }

    private long calculateNextRunTime() {
        long now = System.currentTimeMillis();
        return ((now / BILLING_INTERVAL_MILLIS) + 1) * BILLING_INTERVAL_MILLIS;
    }
    
    /**
     * Callback interface for meter status updates
     */
    public interface MeterStatusCallback {
        void onMeterStatusUpdate(String meterId, String status, Double lastReadingValue, String errorMessage);
    }
}