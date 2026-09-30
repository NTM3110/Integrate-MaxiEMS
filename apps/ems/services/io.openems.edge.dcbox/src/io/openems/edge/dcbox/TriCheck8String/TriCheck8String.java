package io.openems.edge.dcbox.TriCheck8String;

import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface TriCheck8String extends OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        BOX_TEMPERATURE(Doc.of(OpenemsType.INTEGER).unit(Unit.DEGREE_CELSIUS)
                .persistencePriority(PersistencePriority.MEDIUM)),

        // Alarm - electrical
        OVER_VOLTAGE_ALARM(alarm(Level.FAULT, "DC Box Over Voltage Alarm")),
        UNDER_VOLTAGE_ALARM(alarm(Level.FAULT, "DC Box Under Voltage Alarm")),
        OVER_CURRENT_ALARM(alarm(Level.FAULT, "DC Box Over Current Alarm")),
        REVERSE_CURRENT_ALARM(alarm(Level.FAULT, "DC Box Reverse Current Alarm")),

        // Alarm - string
        STRING_CURRENT_ALARM(alarm(Level.WARNING, "PV String Current Alarm")),
        STRING_LOSS_ALARM(alarm(Level.WARNING, "PV String Loss Alarm")),
        STRING_REVERSE_ALARM(alarm(Level.FAULT, "PV String Reverse Alarm")),
        FUSE_ALARM(alarm(Level.FAULT, "DC Box Fuse Alarm")),

        // Protection
        SPD_ALARM(alarm(Level.FAULT, "DC Box SPD Alarm")),
        BREAKER_TRIP_ALARM(alarm(Level.FAULT, "DC Box Breaker Trip Alarm")),
        INSULATION_ALARM(alarm(Level.FAULT, "DC Box Insulation Alarm")),
        GROUND_FAULT_ALARM(alarm(Level.FAULT, "DC Box Ground Fault Alarm")),

        // Environment
        OVER_TEMPERATURE_ALARM(alarm(Level.FAULT, "DC Box Over Temperature Alarm")),
        SMOKE_ALARM(alarm(Level.FAULT, "DC Box Smoke Alarm")),
        WATER_ALARM(alarm(Level.FAULT, "DC Box Water Alarm")),
        DOOR_OPEN_ALARM(alarm(Level.WARNING, "DC Box Door Open Alarm")),

        // Device
        COMMUNICATION_ALARM(alarm(Level.WARNING, "DC Box Communication Alarm")),
        SENSOR_ALARM(alarm(Level.WARNING, "DC Box Sensor Alarm")),
        DEVICE_FAULT(alarm(Level.FAULT, "DC Box Device Fault"));

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }


        @Override
        public Doc doc() {
            return this.doc;
        }

        private static Doc alarm(Level level, String text) {
            return Doc.of(level).text(text).persistencePriority(PersistencePriority.HIGH);
        }
    }

}
