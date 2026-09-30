package io.openems.edge.pvinverter.solaredge;

import java.time.LocalDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingRunnable;
import io.openems.edge.common.channel.FloatWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.solaredge.SolarEdge.ChannelId;

public class SetPvLimitHandler implements ThrowingRunnable<OpenemsNamedException> {

	private final Logger log = LoggerFactory.getLogger(SetPvLimitHandler.class);
	private final SolarEdgeImpl parent;

	private Integer lastActivePower = null;
	private LocalDateTime lastActivePowerTime = LocalDateTime.MIN;

	private Integer lastReactivePower = null;
	private LocalDateTime lastReactivePowerTime = LocalDateTime.MIN;

	public SetPvLimitHandler(SolarEdgeImpl parent) {
		this.parent = parent;
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.handleActivePowerLimit();
		this.handleReactivePowerLimit();
	}

	private void handleActivePowerLimit() throws OpenemsNamedException {
		IntegerWriteChannel channel = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
		var valueOpt = channel.getNextWriteValueAndReset();

		int value;
		if (valueOpt.isPresent()) {
			value = valueOpt.get();
		} else {
			// Reset limit
			value = this.parent.config.maxActivePower();
		}

		if (!Objects.equals(this.lastActivePower, value) || this.lastActivePowerTime
				.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
			// Value needs to be set
			FloatWriteChannel pLimitCh = this.parent.channel(ChannelId.LIMIT_ACTIVE_POWER);
			pLimitCh.setNextWriteValue((float) value);

			IntegerWriteChannel watchDogTagCh = this.parent.channel(ChannelId.WATCH_DOG_TAG);
			watchDogTagCh.setNextWriteValue((int) System.currentTimeMillis());

			this.lastActivePower = value;
			this.lastActivePowerTime = LocalDateTime.now();
		}
	}

	private void handleReactivePowerLimit() throws OpenemsNamedException {
		IntegerWriteChannel channel = this.parent.channel(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
		var valueOpt = channel.getNextWriteValueAndReset();

		if (valueOpt.isPresent()) {
			int value = valueOpt.get();

			if (!Objects.equals(this.lastReactivePower, value) || this.lastReactivePowerTime
					.isBefore(LocalDateTime.now().minusSeconds(150 /* watchdog timeout is 300 */))) {
				
				FloatWriteChannel pLimitCh = this.parent.channel(ChannelId.LIMIT_REACTIVE_POWER);
				pLimitCh.setNextWriteValue((float) value);

				this.lastReactivePower = value;
				this.lastReactivePowerTime = LocalDateTime.now();
			}
		}
	}

}
