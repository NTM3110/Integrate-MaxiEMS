package io.openems.edge.modem.manager;

import io.openems.common.channel.Level;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.common.exceptions.OpenemsException;

/**
 * Interface for 4G/LTE Modem Manager.
 */
public interface Modem4GManager extends OpenemsComponent {

	/** Immutable status exposed to authenticated adapters. */
	public record Status(String status, int signalStrength, int signalDbm, String operator, String ipAddress,
			String connectionType, String modemModel, String imei, String lastError) {
	}

	/** Configuration without exposing the stored password. */
	public record Settings(String apn, String username, boolean passwordConfigured) {
	}

	Status getModemStatus();

	Settings getModemConfig();

	void setModemConfig(String apn, String username, String password) throws OpenemsException;

    /**
     * Modem connection status.
     */
    public enum ModemStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
        NO_MODEM
    }

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        
        /**
         * Modem Status (stored as String).
         */
        MODEM_STATUS(Doc.of(OpenemsType.STRING)
                .text("Current modem connection status")),
        
        /**
         * Signal Strength (0-100%).
         */
        SIGNAL_STRENGTH(Doc.of(OpenemsType.INTEGER)
                .unit(io.openems.common.channel.Unit.PERCENT)
                .text("Signal strength in percentage")),
        
        /**
         * Signal Quality in dBm.
         */
        SIGNAL_DBM(Doc.of(OpenemsType.INTEGER)
                .text("Signal quality in dBm")),
        
        /**
         * Network Operator Name.
         */
        OPERATOR_NAME(Doc.of(OpenemsType.STRING)
                .text("Current network operator name")),
        
        /**
         * IP Address assigned by carrier.
         */
        IP_ADDRESS(Doc.of(OpenemsType.STRING)
                .text("IP address assigned to modem")),
        
        /**
         * Current APN in use.
         */
        CURRENT_APN(Doc.of(OpenemsType.STRING)
                .text("Currently configured APN")),
        
        /**
         * Connection Type (LTE/3G/2G).
         */
        CONNECTION_TYPE(Doc.of(OpenemsType.STRING)
                .text("Connection type: LTE, 3G, 2G")),
        
        /**
         * Modem Model/Manufacturer.
         */
        MODEM_MODEL(Doc.of(OpenemsType.STRING)
                .text("Modem model and manufacturer")),
        
        /**
         * IMEI Number.
         */
        IMEI(Doc.of(OpenemsType.STRING)
                .text("Modem IMEI number")),
        
        /**
         * SIM Card Status.
         */
        SIM_STATUS(Doc.of(OpenemsType.STRING)
                .text("SIM card status")),
        
        /**
         * Last Error Message.
         */
        LAST_ERROR(Doc.of(OpenemsType.STRING)
                .text("Last error message")),
        
        /**
         * No Modem Detected Fault.
         */
        NO_MODEM_DETECTED(Doc.of(Level.FAULT)
                .text("No 4G modem detected"));

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
