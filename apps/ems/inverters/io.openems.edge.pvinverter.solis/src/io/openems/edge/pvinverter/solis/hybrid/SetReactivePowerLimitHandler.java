package io.openems.edge.pvinverter.solis.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.solis.hybrid.PvInverterSolisHybrid.ChannelId;

/**
 * Handler for applying Reactive Power Limit (Q) to Solis Hybrid inverter.
 * 
 * Input: Reads from REACTIVE_POWER_LIMIT channel (in var).
 * Output: Writes scaled value to REACTIVE_POWER_LIMIT channel (Modbus register).
 * 
 * Solis Hybrid uses 1 unit = 10Var for the reactive power limit register.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
    private final PvInverterSolisHybridImpl parent;
    private Integer lastQLimitVar = null;
    private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

    public SetReactivePowerLimitHandler(PvInverterSolisHybridImpl parent) {
        this.parent = parent;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // ONLY read from VALUE channel (var) - this is the input from EVN controller
        // DO NOT read from percentage channel as it could cause feedback loop
        IntegerWriteChannel valueChannel = this.parent
                .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
        var valueOpt = valueChannel.getNextWriteValueAndReset();

        if (!valueOpt.isPresent()) {
            // No command from EVN - do nothing, let the last value persist
            return;
        }

        // EVN sent VALUE (var) - use it directly
        int reactivePowerVar = valueOpt.get();
        int qLimitPerc = (int) ((double) reactivePowerVar / (double) this.parent.config.maxActivePower() * 100.0);

        // Only apply if value changed or timeout reached (watchdog refresh)
        if (!Objects.equals(this.lastQLimitVar, reactivePowerVar) || this.lastQLimitTime
                .isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {

            // Apply scale factor: Solis uses 1 = 10Var
            int scaledValue = reactivePowerVar / 10;

            this.parent.logInfo(this.log,
                    "Apply Q limit: " + reactivePowerVar + " var (" + qLimitPerc + " %) -> register: " + scaledValue);

            // Write scaled value to Modbus register 43134 via REACTIVE_POWER_LIMIT channel
            IntegerWriteChannel reactivePowerLimitCh = this.parent
                    .channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
            reactivePowerLimitCh.setNextWriteValue(scaledValue); // Scaled: 1 = 10Var

            IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
            watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

            this.lastQLimitVar = reactivePowerVar;
            this.lastQLimitTime = LocalDateTime.now();
        }
    }
}
