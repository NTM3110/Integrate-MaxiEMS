package io.openems.edge.pvinverter.solis.ongrid;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Handler for applying Reactive Power Limit (Q) to Solis OnGrid inverter.
 *
 * Input: Reads from REACTIVE_POWER_LIMIT channel (in var).
 * Output: Writes to registers 3071 (REMOTE_CONTROL_Q) and 3083 (REACTIVE_POWER_LIMIT).
 *
 * IMPORTANT: Do NOT read from REACTIVE_POWER_LIMIT_PERCENT as input because
 * writing to channels we also read from would cause a feedback loop.
 */
public class SetReactivePowerLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetReactivePowerLimitHandler.class);
	private final PvInverterSolisOnGridImpl parent;
	private final ManagedSymmetricPvInverter.ChannelId channelId;

	private Integer lastQLimitVar = null;
	private LocalDateTime lastQLimitTime = LocalDateTime.MIN;

	public SetReactivePowerLimitHandler(PvInverterSolisOnGridImpl parent,
			ManagedSymmetricPvInverter.ChannelId reactivePowerLimit) {
		this.parent = parent;
		this.channelId = reactivePowerLimit;
	}

	@Override
	public void run() throws OpenemsNamedException {
		// ONLY read from VALUE channel (var) - this is the input from EVN controller
		// DO NOT read from percentage channel as it could cause feedback loop
		IntegerWriteChannel valueChannel = this.parent.channel(this.channelId);
		var valueOpt = valueChannel.getNextWriteValueAndReset();

		if (!valueOpt.isPresent()) {
			// No command from EVN - do nothing, let the last value persist
			return;
		}

		// EVN sent VALUE (var) - use it directly
		int reactivePowerVar = valueOpt.get();
		var maxActivePower = this.parent.getConfiguredMaxActivePower();
		int qLimitPerc = maxActivePower > 0
				? (int) ((double) reactivePowerVar / (double) maxActivePower * 100.0)
				: 0;

		if (!Objects.equals(this.lastQLimitVar, reactivePowerVar) || this.lastQLimitTime
				.isBefore(LocalDateTime.now().minusSeconds(150))) {

			this.parent.logInfo(this.log, "Apply Q limit: " + reactivePowerVar + " var (" + qLimitPerc + " %)");

			// Enable reactive power control (register 3071): 0xA1 = Reactive setting
			// effective
			IntegerWriteChannel remoteControlQ = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q);
			remoteControlQ.setNextWriteValue(0xA1);

			// Write reactive power limit value to register 3083: 1 unit = 10 var
			IntegerWriteChannel reactivePowerLimitCh = this.parent
					.channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
			reactivePowerLimitCh.setNextWriteValue(reactivePowerVar / 10); // Solis uses gain=10 (1 unit = 10 var)

			this.lastQLimitVar = reactivePowerVar;
			this.lastQLimitTime = LocalDateTime.now();
		}
	}
}
