package io.openems.edge.controller.api.modbus.evn;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.FloatReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

/**
 * EVN Modbus TCP Controller Interface.
 * 
 * <p>
 * This controller receives P-out and Q-out setpoints from EVN via Modbus TCP
 * and exposes them as channels for other controllers to read.
 * 
 * <p>
 * The setpoints represent target power values at the grid connection point.
 * Another controller should read these setpoints, compare with actual meter
 * readings, and control inverters accordingly.
 */
public interface ControllerApiModbusEvn extends Controller, OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        
        // ==================== P-out Control Channels ====================
        
        /**
         * P-out control enabled by EVN.
         * <ul>
         * <li>Type: Boolean
         * <li>true = EVN has enabled active power control
         * </ul>
         */
        P_OUT_ENABLED(Doc.of(OpenemsType.BOOLEAN)
                .text("P-out control enabled by EVN")),
        
        /**
         * P-out setpoint in percentage (0-100%).
         * <ul>
         * <li>Type: Float
         * <li>Unit: %
         * <li>Range: 0-100
         * </ul>
         */
        P_OUT_SETPOINT_PERCENT(Doc.of(OpenemsType.FLOAT)
                .unit(Unit.PERCENT)
                .text("P-out setpoint in percentage")),
        
        /**
         * P-out setpoint in Watts.
         * <ul>
         * <li>Type: Integer
         * <li>Unit: W
         * <li>This is the target active power at grid connection point
         * </ul>
         */
        P_OUT_SETPOINT_WATT(Doc.of(OpenemsType.INTEGER)
                .unit(Unit.WATT)
                .text("P-out setpoint in Watts")),
        
        // ==================== Q-out Control Channels ====================
        
        /**
         * Q-out control enabled by EVN.
         * <ul>
         * <li>Type: Boolean
         * <li>true = EVN has enabled reactive power control
         * </ul>
         */
        Q_OUT_ENABLED(Doc.of(OpenemsType.BOOLEAN)
                .text("Q-out control enabled by EVN")),
        
        /**
         * Q-out setpoint in percentage (-100 to +100%).
         * <ul>
         * <li>Type: Float
         * <li>Unit: %
         * <li>Range: -100 to +100
         * </ul>
         */
        Q_OUT_SETPOINT_PERCENT(Doc.of(OpenemsType.FLOAT)
                .unit(Unit.PERCENT)
                .text("Q-out setpoint in percentage")),
        
        /**
         * Q-out setpoint in var.
         * <ul>
         * <li>Type: Integer
         * <li>Unit: var
         * <li>This is the target reactive power at grid connection point
         * </ul>
         */
        Q_OUT_SETPOINT_VAR(Doc.of(OpenemsType.INTEGER)
                .unit(Unit.VOLT_AMPERE_REACTIVE)
                .text("Q-out setpoint in var"));

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    // ==================== P-out Getters ====================

    /**
     * Gets the Channel for {@link ChannelId#P_OUT_ENABLED}.
     */
    public default BooleanReadChannel getPOutEnabledChannel() {
        return this.channel(ChannelId.P_OUT_ENABLED);
    }

    /**
     * Gets P-out control enabled status.
     */
    public default Value<Boolean> getPOutEnabled() {
        return this.getPOutEnabledChannel().value();
    }

    /**
     * Internal method to set P-out enabled.
     */
    public default void _setPOutEnabled(Boolean value) {
        this.getPOutEnabledChannel().setNextValue(value);
    }

    /**
     * Gets the Channel for {@link ChannelId#P_OUT_SETPOINT_PERCENT}.
     */
    public default FloatReadChannel getPOutSetpointPercentChannel() {
        return this.channel(ChannelId.P_OUT_SETPOINT_PERCENT);
    }

    /**
     * Gets P-out setpoint in percentage.
     */
    public default Value<Float> getPOutSetpointPercent() {
        return this.getPOutSetpointPercentChannel().value();
    }

    /**
     * Internal method to set P-out setpoint percent.
     */
    public default void _setPOutSetpointPercent(Float value) {
        this.getPOutSetpointPercentChannel().setNextValue(value);
    }

    /**
     * Gets the Channel for {@link ChannelId#P_OUT_SETPOINT_WATT}.
     */
    public default IntegerReadChannel getPOutSetpointWattChannel() {
        return this.channel(ChannelId.P_OUT_SETPOINT_WATT);
    }

    /**
     * Gets P-out setpoint in Watts.
     */
    public default Value<Integer> getPOutSetpointWatt() {
        return this.getPOutSetpointWattChannel().value();
    }

    /**
     * Internal method to set P-out setpoint in Watts.
     */
    public default void _setPOutSetpointWatt(Integer value) {
        this.getPOutSetpointWattChannel().setNextValue(value);
    }

    // ==================== Q-out Getters ====================

    /**
     * Gets the Channel for {@link ChannelId#Q_OUT_ENABLED}.
     */
    public default BooleanReadChannel getQOutEnabledChannel() {
        return this.channel(ChannelId.Q_OUT_ENABLED);
    }

    /**
     * Gets Q-out control enabled status.
     */
    public default Value<Boolean> getQOutEnabled() {
        return this.getQOutEnabledChannel().value();
    }

    /**
     * Internal method to set Q-out enabled.
     */
    public default void _setQOutEnabled(Boolean value) {
        this.getQOutEnabledChannel().setNextValue(value);
    }

    /**
     * Gets the Channel for {@link ChannelId#Q_OUT_SETPOINT_PERCENT}.
     */
    public default FloatReadChannel getQOutSetpointPercentChannel() {
        return this.channel(ChannelId.Q_OUT_SETPOINT_PERCENT);
    }

    /**
     * Gets Q-out setpoint in percentage.
     */
    public default Value<Float> getQOutSetpointPercent() {
        return this.getQOutSetpointPercentChannel().value();
    }

    /**
     * Internal method to set Q-out setpoint percent.
     */
    public default void _setQOutSetpointPercent(Float value) {
        this.getQOutSetpointPercentChannel().setNextValue(value);
    }

    /**
     * Gets the Channel for {@link ChannelId#Q_OUT_SETPOINT_VAR}.
     */
    public default IntegerReadChannel getQOutSetpointVarChannel() {
        return this.channel(ChannelId.Q_OUT_SETPOINT_VAR);
    }

    /**
     * Gets Q-out setpoint in var.
     */
    public default Value<Integer> getQOutSetpointVar() {
        return this.getQOutSetpointVarChannel().value();
    }

    /**
     * Internal method to set Q-out setpoint in var.
     */
    public default void _setQOutSetpointVar(Integer value) {
        this.getQOutSetpointVarChannel().setNextValue(value);
    }
}
