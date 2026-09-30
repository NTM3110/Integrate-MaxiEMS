package io.openems.edge.pvinverter.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

public interface HybridInverterAlarms extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		SUPPORTED_ALARM_MASK(Doc.of(OpenemsType.LONG).persistencePriority(PersistencePriority.MEDIUM)),
		ALARM_DATA_VALID(Doc.of(OpenemsType.BOOLEAN).persistencePriority(PersistencePriority.HIGH));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public default LongReadChannel getSupportedAlarmMaskChannel() {
		return this.channel(ChannelId.SUPPORTED_ALARM_MASK);
	}

	public default Value<Long> getSupportedAlarmMask() {
		return this.getSupportedAlarmMaskChannel().value();
	}

	public default void _setSupportedAlarmMask(Long value) {
		this.getSupportedAlarmMaskChannel().setNextValue(value);
	}

	public default BooleanReadChannel getAlarmDataValidChannel() {
		return this.channel(ChannelId.ALARM_DATA_VALID);
	}

	public default Value<Boolean> getAlarmDataValid() {
		return this.getAlarmDataValidChannel().value();
	}

	public default void _setAlarmDataValid(Boolean value) {
		this.getAlarmDataValidChannel().setNextValue(value);
	}
}
