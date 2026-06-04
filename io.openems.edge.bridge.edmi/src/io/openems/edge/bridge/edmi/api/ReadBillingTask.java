package io.openems.edge.bridge.edmi.api;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.taskmanager.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A concrete Task for reading real-time registers from an EDMI meter.
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

    public ReadBillingTask(String meterId, Priority priority, EdmiBridge bridge, String username, String password,
            int serialNumber, String energyRole, String energySourceType) {
        super(priority);
        this.meterId = meterId;
        this.bridge = bridge;
        this.setNextRunTime(this.calculateNextRunTime());
        this.username = username;
        this.password = password;
        this.serialNumber = serialNumber;
        this.energyRole = energyRole;
        this.energySourceType = energySourceType;
    }

    @Override
    public void execute() throws Exception {
        try {
            this.log.warn("-------------- Excecuting reading billing task for meter {}--------------", meterId);

            List<Object> objects = this.readBillingValuesFromHardware();
            if (objects == null || objects.size() < BILLING_VALUE_COUNT) {
                throw new IllegalStateException("Expected at least " + BILLING_VALUE_COUNT
                        + " billing values but got " + (objects == null ? 0 : objects.size()));
            }

            this.log.info("Billing values for [{}]: {}", this.meterId, objects);

            String date = objects.get(2).toString();
            String time = objects.get(3).toString();
            LocalDateTime ldt = LocalDateTime.parse(date + "T" + time);
            Point point = Point.measurement("edmi_billing_values") //
                    .addTag("meter_id", this.meterId) //
                    .addTag("meter_role", this.energyRole) //
                    .addTag("source_type", this.energySourceType) //
                    .time(ldt.toInstant(ZoneOffset.ofHours(7)), WritePrecision.MS) //
                    .addField("meter_serial_number", objects.get(0).toString()) //
                    .addField("error_code", this.toNumber(objects.get(1), "error_code")) //
                    .addField("rate1_imp_wh", this.toNumber(objects.get(4), "rate1_imp_wh"))
                    .addField("rate2_imp_kwh", this.toNumber(objects.get(5), "rate2_imp_kwh"))
                    .addField("rate3_imp_kwh", this.toNumber(objects.get(6), "rate3_imp_kwh"))
                    .addField("total_imp_kwh", this.toNumber(objects.get(7), "total_imp_kwh"))
                    .addField("total_imp_kvar", this.toNumber(objects.get(8), "total_imp_kvar"))
                    .addField("rate1_exp_kwh", this.toNumber(objects.get(9), "rate1_exp_kwh"))
                    .addField("rate2_exp_kwh", this.toNumber(objects.get(10), "rate2_exp_kwh"))
                    .addField("rate3_exp_kwh", this.toNumber(objects.get(11), "rate3_exp_kwh"))
                    .addField("total_exp_kwh", this.toNumber(objects.get(12), "total_exp_kwh"))
                    .addField("total_exp_kvar", this.toNumber(objects.get(13), "total_exp_kvar"));
            this.bridge.writeToInflux(point);
            this.log.info("Write billing data to InfluxDB for [{}] at [{}]", this.meterId, ldt);
        } finally {
            this.setNextRunTime(this.calculateNextRunTime());
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
}
