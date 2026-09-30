package io.openems.edge.pvinverter.huawei.ongrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for applying Reactive Power Limit (Q) to Huawei OnGrid inverter.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterHuaweiOnGridImpl parent;
    private Integer lastQLimitVar = null;
    private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterHuaweiOnGridImpl parent) {
        this.parent = parent;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Check PERCENT channel first (priority)
        IntegerWriteChannel percentChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
        var percentOpt = percentChannel.getNextWriteValueAndReset();

        // Check VALUE channel (var)
        IntegerWriteChannel valueChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        int reactivePowerVar;
        int qLimitPerc;

        if (percentOpt.isPresent()) {
            // EVN sent PERCENT - calculate value (var) from percent
            qLimitPerc = percentOpt.get();

            // keep percentage in range [0, 100]
            if (qLimitPerc > 100) {
                qLimitPerc = 100;
            }
            if (qLimitPerc < 0) {
                qLimitPerc = 0;
            }
            reactivePowerVar = (int) (this.parent.config.maxActivePower() * qLimitPerc / 100.0);
        } else if (valueOpt.isPresent()) {
            // EVN sent VALUE (var) - use it directly
            reactivePowerVar = valueOpt.get();
            qLimitPerc = (int) ((double) reactivePowerVar / (double) this.parent.config.maxActivePower() * 100.0);
        } else {
            return; // No command to apply
        }

        if (!Objects.equals(this.lastQLimitVar, reactivePowerVar) || this.lastQLimitTime
                .isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, "Apply Q limit: " + reactivePowerVar + " var (" + qLimitPerc + " %)");

            IntegerWriteChannel reactivePowerLimitCh = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
            reactivePowerLimitCh.setNextWriteValue(reactivePowerVar);

            this.lastQLimitVar = reactivePowerVar;
            this.lastQLimitTime = LocalDateTime.now();
        }
    }
}
