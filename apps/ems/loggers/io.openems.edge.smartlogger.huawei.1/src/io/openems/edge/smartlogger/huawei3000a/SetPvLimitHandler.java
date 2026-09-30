package io.openems.edge.smartlogger.huawei3000a;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final SmartLogger3000AImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;

	private Integer lastPLimitPerc = null;
	private LocalDateTime lastPLimitPercTime = LocalDateTime.MIN;

	public SetPvLimitHandler(SmartLogger3000AImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
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

		Integer pLimitPerc = null;
		Integer power = null;

		if (percentOpt.isPresent()) {
			pLimitPerc = percentOpt.get();
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
			power = (int) (this.parent.getMaxActivePowerWatts() * pLimitPerc / 100.0);
		} else if (valueOpt.isPresent()) {
			power = valueOpt.get();
			pLimitPerc = (int) ((double) power / (double) this.parent.getMaxActivePowerWatts() * 100.0);
			if (pLimitPerc > 100) {
				pLimitPerc = 100;
			}
			if (pLimitPerc < 0) {
				pLimitPerc = 0;
			}
		}

		// Always update Watchdog to keep connection alive
		IntegerWriteChannel watchDogTagCh = this.parent.channel(SmartLogger3000A.ChannelId.WATCH_DOG_TAG);
		int heartBeat = (int) (System.currentTimeMillis() / 1000 % 65535);
		watchDogTagCh.setNextWriteValue(heartBeat);

		if (power != null) {
			if (!Objects.equals(this.lastPLimitPerc, pLimitPerc) || this.lastPLimitPercTime
					.isBefore(LocalDateTime.now().minusSeconds(30))) {
				
				this.log.info("Apply P limit to SmartLogger: " + power + " W (" + pLimitPerc + " %) | HB: " + heartBeat);

				// Write to the actual Modbus channel
				IntegerWriteChannel pLimitCh = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
				pLimitCh.setNextWriteValue(power);

				EnumWriteChannel pLimitTypeCh = this.parent.channel(SmartLogger3000A.ChannelId.P_LIMIT_TYPE);
				pLimitTypeCh.setNextWriteValue(PLimitType.FIXED_LIMIT);

				this.lastPLimitPerc = pLimitPerc;
				this.lastPLimitPercTime = LocalDateTime.now();
			}
		}
	}
}
