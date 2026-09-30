package io.openems.edge.smartlogger.huawei3000a;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
	private final SmartLogger3000AImpl parent;
	private Integer lastQLimitVar = null;
	private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

	public SetReactivePowerLimitHandler(SmartLogger3000AImpl parent) {
		this.parent = parent;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Check PERCENT channel first (priority)
		IntegerWriteChannel percentChannel = this.parent
				.channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
		var percentOpt = percentChannel.getNextWriteValueAndReset();

		// Check VALUE channel (var)
		IntegerWriteChannel valueChannel = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		int reactivePowerVar;
		int qLimitPerc;

		if (percentOpt.isPresent()) {
			qLimitPerc = percentOpt.get();
			if (qLimitPerc > 100) {
				qLimitPerc = 100;
			}
			if (qLimitPerc < 0) {
				qLimitPerc = 0;
			}
			reactivePowerVar = (int) (this.parent.getMaxActivePowerWatts() * qLimitPerc / 100.0);
		} else if (valueOpt.isPresent()) {
			reactivePowerVar = valueOpt.get();
			qLimitPerc = (int) ((double) reactivePowerVar / (double) this.parent.getMaxActivePowerWatts() * 100.0);
		} else {
			return;
		}

		if (!Objects.equals(this.lastQLimitVar, reactivePowerVar) || this.lastQLimitTime
				.isBefore(LocalDateTime.now().minusSeconds(150))) {

			this.log.info("Apply Q limit to SmartLogger: " + reactivePowerVar + " var (" + qLimitPerc + " %)");

			IntegerWriteChannel qLimitCh = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
			qLimitCh.setNextWriteValue(reactivePowerVar);

			this.lastQLimitVar = reactivePowerVar;
			this.lastQLimitTime = LocalDateTime.now();
		}
	}
}
