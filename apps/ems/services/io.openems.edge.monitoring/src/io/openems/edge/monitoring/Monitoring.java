package io.openems.edge.monitoring;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Interface for 4G/LTE Modem Manager.
 */
public interface Monitoring extends OpenemsComponent {


    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        /**
         * Modem Status (stored as String).
         */
        NAME(Doc.of(OpenemsType.STRING)
                .text("Name Device")),
        /**
         * Modem Status (stored as String).
         */
        FW(Doc.of(OpenemsType.STRING)
                .text("Firmware")),
        /**
         * Modem Status (stored as String).
         */
        S_N(Doc.of(OpenemsType.STRING)
                .text("Serial Number")),
        /**
         * Modem Status (stored as String).
         */
        CPU_USAGE(Doc.of(OpenemsType.STRING)
                .unit(Unit.PERCENT)
                .text("CPU used %")),
        
        /**
         * Signal Strength (0-100%).
         */
        RAM_USAGE(Doc.of(OpenemsType.INTEGER)
                .unit(Unit.PERCENT)
                .text("RAM used %")),
        
        /**
         * Signal Quality in dBm.
         */
        DISK_USAGE(Doc.of(OpenemsType.INTEGER)
                .unit(Unit.PERCENT)
                .text("STORAGE used %"));

        private final Doc doc;

        ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }
}
