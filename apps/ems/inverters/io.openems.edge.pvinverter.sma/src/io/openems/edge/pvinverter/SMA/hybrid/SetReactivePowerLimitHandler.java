package io.openems.edge.pvinverter.SMA.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.SMA.hybrid.PvInverterSMAHybrid.ChannelId;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for applying Reactive Power Limit (Q) to SMA Hybrid inverter.
 * 
 * Input: Reads from REACTIVE_POWER_LIMIT channel (in var).
 * Output: Writes to SMA registers 40200=mode, 40202=VAr.
 * 
 * IMPORTANT: Do NOT read from REACTIVE_POWER_LIMIT_PERCENT as input because
 * writing to channels we also read from would cause a feedback loop.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private static final int Q_OPERATING_MODE_KVAR = 1071; // Q const in kvar

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterSMAHybridImpl parent;
    private Integer lastQLimit = null;
    private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterSMAHybridImpl parent) {
        this.parent = parent;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // ONLY read from VALUE channel (var) - this is the input from EVN controller
        // DO NOT read from REACTIVE_POWER_LIMIT_PERCENT as it's mapped to Modbus register
        // and would cause feedback loop
        IntegerWriteChannel valueChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        if (!valueOpt.isPresent()) {
            // No command from EVN - do nothing, let the last value persist
            return;
        }

        // EVN sent VALUE (var) - use kvar mode
        int qLimit = valueOpt.get();

        if (!Objects.equals(this.lastQLimit, qLimit) || this.lastQLimitTime
                .isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, "Apply Q limit: " + qLimit + " var (mode=" + Q_OPERATING_MODE_KVAR + ")");

            // Set operating mode to kvar mode (40200)
            IntegerWriteChannel qModeChannel = this.parent.channel(ChannelId.Q_OPERATING_MODE);
            qModeChannel.setNextWriteValue(Q_OPERATING_MODE_KVAR);

            // Write to REACTIVE_POWER_LIMIT (40202)
            IntegerWriteChannel qValueCh = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
            qValueCh.setNextWriteValue(qLimit);

            this.lastQLimit = qLimit;
            this.lastQLimitTime = LocalDateTime.now();
        }
    }
}
