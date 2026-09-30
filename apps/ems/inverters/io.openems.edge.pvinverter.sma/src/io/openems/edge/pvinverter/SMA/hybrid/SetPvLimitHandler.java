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
 * SetPvLimitHandler for SMA Hybrid inverter.
 * 
 * Uses SMA Modbus registers:
 * - 40210: P operating mode (1077=Watt mode, 1078=Percent mode)
 * - 40211: P setpoint in W (if mode=1077)
 * - 40212: P setpoint in % (if mode=1078)
 * 
 * Input: Reads power limit from ACTIVE_POWER_LIMIT channel (in Watts).
 * Output: Writes to SMA Modbus registers.
 * 
 * IMPORTANT: Do NOT read from ACTIVE_POWER_LIMIT_PERCENT as input because
 * writing to the same channel we read from would cause a feedback loop.
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private static final int P_OPERATING_MODE_WATT = 1077; // Active power limitation P in W

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterSMAHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPLimit = null;
	private LocalDateTime lastPLimitTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterSMAHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// ONLY read from VALUE channel (W) - this is the input from EVN controller
		// DO NOT read from ACTIVE_POWER_LIMIT_PERCENT as it's mapped to Modbus register
		// and would cause feedback loop
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		if (!valueOpt.isPresent()) {
			// No command from EVN - do nothing, let the last value persist
			return;
		}

		// EVN sent VALUE (W) - use Watt mode
		int pLimit = valueOpt.get();

		if (!Objects.equals(this.lastPLimit, pLimit) || this.lastPLimitTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
			// Value needs to be set
			this.parent.logInfo(this.log, "Apply P limit: " + pLimit + " W (mode=" + P_OPERATING_MODE_WATT + ")");

			// Set operating mode to Watt mode (40210)
			IntegerWriteChannel pModeChannel = this.parent.channel(ChannelId.P_OPERATING_MODE);
			pModeChannel.setNextWriteValue(P_OPERATING_MODE_WATT);

			// Write power value in Watts to ACTIVE_POWER_LIMIT (40211)
			IntegerWriteChannel pValueCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
			pValueCh.setNextWriteValue(pLimit);

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastPLimit = pLimit;
			this.lastPLimitTime = LocalDateTime.now();
		}
	}

}
