package io.openems.edge.pvinverter.sungrow.a;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for setting Active Power Limit on Sungrow inverter.
 * Uses Sungrow Modbus registers:
 * - 5007: Power limitation switch (0xAA=Enable, 0x55=Disable)
 * - 5008: Power limitation setting (0-1100, in 0.1% of rated power)
 * - 5039: Power limitation adjustment (in 0.1kW)
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private static final int P_LIMIT_ENABLE = 0xAA;
    private static final int P_LIMIT_DISABLE = 0x55;

    private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
    private final PvInverterSungrowImpl parent;
    private final ManagedSymmetricPvInverter.ChannelId channelId;

    private Integer lastPLimitPerc = null;
    private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

    public SetPvLimitHandler(PvInverterSungrowImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
        this.parent = parent;
        this.channelId = activePowerLimit;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Check PERCENT channel first (priority)
        IntegerWriteChannel percentChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
        var percentOpt = percentChannel.getNextWriteValueAndReset();

        // Check VALUE channel (W)
        IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        int pLimitPerc; // 0-1000 scale (0.1%)
        int powerKw; // in 0.1kW

        int maxActivePower = this.parent.getConfig().maxActivePower();

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT directly (0-100) - convert to 0-1000 (0.1% scale)
            int inputPercent = percentOpt.get();
            pLimitPerc = inputPercent * 10; // Convert to 0.1% scale
            powerKw = (int) (maxActivePower * inputPercent / 100.0 / 100.0); // Convert W to 0.1kW

            // keep percentage in range [0, 1000]
            if (pLimitPerc > 1000) {
                pLimitPerc = 1000;
            }
            if (pLimitPerc < 0) {
                pLimitPerc = 0;
            }
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (W) - calculate percentage
            int powerW = valueOpt.get();
            powerKw = powerW / 100; // Convert W to 0.1kW
            pLimitPerc = (int) ((double) powerW / (double) maxActivePower * 1000.0); // 0.1% scale

            // keep percentage in range [0, 1000]
            if (pLimitPerc > 1000) {
                pLimitPerc = 1000;
            }
            if (pLimitPerc < 0) {
                pLimitPerc = 0;
            }
        } else {
            // No command - do nothing, let the last value persist
            return;
        }

        if (!Objects.equals(this.lastPLimitPerc, pLimitPerc) || this.lastPLimitPercTime
                .isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
            // Value needs to be set
            this.parent.logInfo(this.log,
                    "Apply new P limit: " + (powerKw * 100) + " W (" + (pLimitPerc / 10.0) + " %)");

            // Enable power limitation (register 5007)
            IntegerWriteChannel pLimitSwitch = this.parent.channel(PvInverterSungrow.ChannelId.P_LIMITATION_SWITCH);
            pLimitSwitch.setNextWriteValue(P_LIMIT_ENABLE);

            // Set power limitation percentage (register 5008) - 0.1% scale
            IntegerWriteChannel pLimitSetting = this.parent.channel(PvInverterSungrow.ChannelId.P_LIMITATION_SETTING);
            pLimitSetting.setNextWriteValue(pLimitPerc);

            // Set power limitation in kW (register 5039) - 0.1kW scale
            IntegerWriteChannel pLimitKw = this.parent.channel(PvInverterSungrow.ChannelId.P_LIMITATION_KW);
            pLimitKw.setNextWriteValue(powerKw);

            this.lastPLimitPerc = pLimitPerc;
            this.lastPLimitPercTime = LocalDateTime.now();
        }
    }
}
