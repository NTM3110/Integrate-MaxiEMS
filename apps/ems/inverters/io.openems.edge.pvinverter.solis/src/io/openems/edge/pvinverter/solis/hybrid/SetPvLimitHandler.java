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
 * SetPvLimitHandler for Solis Hybrid inverter.
 * 
 * Input: Reads power limit from ACTIVE_POWER_LIMIT channel (in Watts).
 * Output: Writes scaled value to P_LIMIT channel (Modbus register 43133).
 * 
 * IMPORTANT: Do NOT write back to ACTIVE_POWER_LIMIT to avoid feedback loop!
 * Solis Hybrid uses 1 unit = 10W for the power limit register.
 */
public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final PvInverterSolisHybridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;
	private Integer lastPowerW = null;
	private LocalDateTime lastWriteTime = LocalDateTime.MIN;

	public SetPvLimitHandler(PvInverterSolisHybridImpl parent, ManagedSymmetricPvInverter.ChannelId activePowerLimit) {
		this.parent = parent;
		this.channelId = activePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Read from INPUT channel (Watts) - this is the command from Controller
		IntegerWriteChannel inputChannel = this.parent.channel(this.channelId);
		var valueOpt = inputChannel.getNextWriteValueAndReset();

		if (!valueOpt.isPresent()) {
			// No new command - do nothing
			return;
		}

		int powerW = valueOpt.get();

		// Skip if same value and not timeout
		if (Objects.equals(this.lastPowerW, powerW) && this.lastWriteTime
				.isAfter(LocalDateTime.now().minusSeconds(150))) {
			return;
		}

		// Calculate scaled value: Solis uses 1 unit = 10W
		int scaledValue = powerW / 10;

		// Calculate percentage for logging
		int pLimitPerc = (int) ((double) powerW / (double) this.parent.config.maxActivePower() * 100.0);
		pLimitPerc = Math.max(0, Math.min(100, pLimitPerc));

		this.parent.logInfo(this.log,
				"Apply P limit: " + powerW + " W (" + pLimitPerc + " %) -> register: " + scaledValue);

		// Write to REMOTE_CONTROL channel (register 43132)
		IntegerWriteChannel pRemoteCtrl = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
		pRemoteCtrl.setNextWriteValue(1); // 1 = ON

		// Write SCALED value to P_LIMIT channel (register 43133)
		// NOTE: P_LIMIT is mapped to Modbus register, NOT ACTIVE_POWER_LIMIT!
		IntegerWriteChannel pLimitCh = this.parent.channel(ChannelId.P_LIMIT);
		pLimitCh.setNextWriteValue(scaledValue);

		// Write watchdog
		IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
		watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

		this.lastPowerW = powerW;
		this.lastWriteTime = LocalDateTime.now();
	}

}
