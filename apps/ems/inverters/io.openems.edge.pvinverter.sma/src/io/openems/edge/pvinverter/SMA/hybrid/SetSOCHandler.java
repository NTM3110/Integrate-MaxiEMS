package io.openems.edge.pvinverter.SMA.hybrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public class SetSOCHandler implements ThrowingRunnable<OpenemsNamedException> {

    private final Logger log = LoggerFactory.getLogger(SetSOCHandler.class);
    private final PvInverterSMAHybridImpl parent;

    // Lưu trữ giá trị cho MAX_CHARGE
    private Integer lastMaxChargeSOCPerc = null;
    private LocalDateTime lastMaxChargeTime = LocalDateTime.MIN;

    // Lưu trữ giá trị cho MAX_DISCHARGE
    private Integer lastMaxDischargeSOCPerc = null;
    private LocalDateTime lastMaxDischargeTime = LocalDateTime.MIN;

    public SetSOCHandler(PvInverterSMAHybridImpl parent) {
        this.parent = parent;
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Xử lý MAX_CHARGE_POWER
        processMaxCharge();
        
        // Xử lý MAX_DISCHARGE_POWER
    	processMaxDischarge();
    }

    private void processMaxCharge() throws OpenemsNamedException {
        IntegerWriteChannel channel = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC);
        var powerOpt = channel.getNextWriteValueAndReset();

        int limitPerc;
        int power;
        
        if (powerOpt.isPresent()) {
            power = powerOpt.get();
            limitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);
            limitPerc = Math.max(0, Math.min(100, limitPerc)); // clamp [0,100]
        } else {
            power = this.parent.config.maxActivePower();
            limitPerc = 100;
        }

        // Kiểm tra nếu có thay đổi hoặc quá thời gian watchdog (150 giây)
        if (!Objects.equals(this.lastMaxChargeSOCPerc, limitPerc) ||
                this.lastMaxChargeTime.isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, 
                String.format("Apply MAX_CHARGE limit: %d A (%d %%)", power, limitPerc));

            // Set giá trị cho MAX_CHARGE
            IntegerWriteChannel maxChargeLimitCh = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC);
            maxChargeLimitCh.setNextWriteValue(limitPerc);

            this.lastMaxChargeSOCPerc = limitPerc;
            this.lastMaxChargeTime = LocalDateTime.now();
        }
    }

    private void processMaxDischarge() throws OpenemsNamedException {
        IntegerWriteChannel channel = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC);
        var powerOpt = channel.getNextWriteValueAndReset();

        int limitPerc;
        int power;
        
        if (powerOpt.isPresent()) {
            power = powerOpt.get();
            limitPerc = (int) ((double) power / (double) this.parent.config.maxActivePower() * 100.0);
            limitPerc = Math.max(0, Math.min(100, limitPerc)); // clamp [0,100]
        } else {
            power = this.parent.config.maxActivePower();
            limitPerc = 100;
        }

        // Kiểm tra nếu có thay đổi hoặc quá thời gian watchdog (150 giây)
        if (!Objects.equals(this.lastMaxDischargeSOCPerc, limitPerc) ||
                this.lastMaxDischargeTime.isBefore(LocalDateTime.now().minusSeconds(150))) {

            this.parent.logInfo(this.log, 
                String.format("Apply MAX_DISCHARGE limit: %d A (%d %%)", power, limitPerc));

            // Set giá trị cho MAX_DISCHARGE
            IntegerWriteChannel maxDischargeLimitCh = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC);
            maxDischargeLimitCh.setNextWriteValue(limitPerc);

            this.lastMaxDischargeSOCPerc = limitPerc;
            this.lastMaxDischargeTime = LocalDateTime.now();
        }
    }
}