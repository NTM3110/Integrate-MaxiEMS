package io.openems.edge.moxa.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/** Common channels shared by Moxa I/O devices. */
@ProviderType
public interface MoxaRegister extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DI_01(booleanInputDoc()), //
		DI_02(booleanInputDoc()), //
		DI_03(booleanInputDoc()), //
		DI_04(booleanInputDoc()), //
		DI_05(booleanInputDoc()), //
		DI_06(booleanInputDoc()), //
		DI_07(booleanInputDoc()), //
		DI_08(booleanInputDoc()), //
		DI_09(booleanInputDoc()), //
		DI_10(booleanInputDoc()), //
		DI_11(booleanInputDoc()), //
		DI_12(booleanInputDoc()), //
		DI_13(booleanInputDoc()), //
		DI_14(booleanInputDoc()), //
		DI_15(booleanInputDoc()), //
		DI_16(booleanInputDoc()), //
		DO_01(booleanOutputDoc()), //
		DO_02(booleanOutputDoc()), //
		DO_03(booleanOutputDoc()), //
		DO_04(booleanOutputDoc()), //
		DO_05(booleanOutputDoc()), //
		DO_06(booleanOutputDoc()), //
		DO_07(booleanOutputDoc()), //
		DO_08(booleanOutputDoc()), //
		DO_09(booleanOutputDoc()), //
		DO_10(booleanOutputDoc()), //
		DO_11(booleanOutputDoc()), //
		DO_12(booleanOutputDoc()), //
		DO_13(booleanOutputDoc()), //
		DO_14(booleanOutputDoc()), //
		DO_15(booleanOutputDoc()), //
		DO_16(booleanOutputDoc()), //
		AI_01(integerInputDoc()), //
		AI_02(integerInputDoc()), //
		AI_03(integerInputDoc()), //
		AI_04(integerInputDoc()), //
		AI_05(integerInputDoc()), //
		AI_06(integerInputDoc()), //
		AI_07(integerInputDoc()), //
		AI_08(integerInputDoc()), //
		AI_09(integerInputDoc()), //
		AI_10(integerInputDoc()), //
		AI_11(integerInputDoc()), //
		AI_12(integerInputDoc()), //
		AI_13(integerInputDoc()), //
		AI_14(integerInputDoc()), //
		AI_15(integerInputDoc()), //
		AI_16(integerInputDoc()), //
		AO_01(integerOutputDoc()), //
		AO_02(integerOutputDoc()), //
		AO_03(integerOutputDoc()), //
		AO_04(integerOutputDoc()), //
		AO_05(integerOutputDoc()), //
		AO_06(integerOutputDoc()), //
		AO_07(integerOutputDoc()), //
		AO_08(integerOutputDoc()), //
		AO_09(integerOutputDoc()), //
		AO_10(integerOutputDoc()), //
		AO_11(integerOutputDoc()), //
		AO_12(integerOutputDoc()), //
		AO_13(integerOutputDoc()), //
		AO_14(integerOutputDoc()), //
		AO_15(integerOutputDoc()), //
		AO_16(integerOutputDoc()),
		RLY_01(booleanInputDoc()),
		RLY_02(booleanInputDoc()),
		RLY_03(booleanInputDoc()),
		RLY_04(booleanInputDoc()),
		RLY_05(booleanInputDoc()),
		RLY_06(booleanInputDoc()),
		RLY_07(booleanInputDoc()),
		RLY_08(booleanInputDoc()),
		RLY_09(booleanInputDoc()),
		RLY_10(booleanInputDoc()),
		RLY_11(booleanInputDoc()),
		RLY_12(booleanInputDoc()),
		RLY_13(booleanInputDoc()),
		RLY_14(booleanInputDoc()),
		RLY_15(booleanInputDoc()),
		RLY_16(booleanInputDoc());

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

		private static Doc booleanInputDoc() {
			return Doc.of(OpenemsType.BOOLEAN) //
					.persistencePriority(PersistencePriority.MEDIUM);
		}

		private static Doc booleanOutputDoc() {
			return Doc.of(OpenemsType.BOOLEAN) //
					.accessMode(AccessMode.READ_WRITE) //
					.persistencePriority(PersistencePriority.MEDIUM);
		}

		private static Doc integerInputDoc() {
			return Doc.of(OpenemsType.INTEGER) //
					.persistencePriority(PersistencePriority.MEDIUM);
		}

		private static Doc integerOutputDoc() {
			return Doc.of(OpenemsType.INTEGER) //
					.accessMode(AccessMode.READ_WRITE) //
					.persistencePriority(PersistencePriority.MEDIUM);
		}

	}

	public default BooleanReadChannel getDigitalInputChannel(int channelIndex) {
		return this.channel(channelId("DI", channelIndex));
	}

	public default Value<Boolean> getDigitalInput(int channelIndex) {
		return this.getDigitalInputChannel(channelIndex).value();
	}

	public default void _setDigitalInput(int channelIndex, Boolean value) {
		this.getDigitalInputChannel(channelIndex).setNextValue(value);
	}

	public default BooleanWriteChannel getDigitalOutputChannel(int channelIndex) {
		return this.channel(channelId("DO", channelIndex));
	}

	public default Value<Boolean> getDigitalOutput(int channelIndex) {
		return this.getDigitalOutputChannel(channelIndex).value();
	}

	public default void _setDigitalOutput(int channelIndex, Boolean value) {
		this.getDigitalOutputChannel(channelIndex).setNextValue(value);
	}

	public default void setDigitalOutput(int channelIndex, Boolean value) throws OpenemsNamedException {
		this.getDigitalOutputChannel(channelIndex).setNextWriteValue(value);
	}

	public default IntegerReadChannel getAnalogInputChannel(int channelIndex) {
		return this.channel(channelId("AI", channelIndex));
	}

	public default Value<Integer> getAnalogInput(int channelIndex) {
		return this.getAnalogInputChannel(channelIndex).value();
	}

	public default void _setAnalogInput(int channelIndex, Integer value) {
		this.getAnalogInputChannel(channelIndex).setNextValue(value);
	}

	public default IntegerWriteChannel getAnalogOutputChannel(int channelIndex) {
		return this.channel(channelId("AO", channelIndex));
	}

	public default Value<Integer> getAnalogOutput(int channelIndex) {
		return this.getAnalogOutputChannel(channelIndex).value();
	}

	public default void _setAnalogOutput(int channelIndex, Integer value) {
		this.getAnalogOutputChannel(channelIndex).setNextValue(value);
	}

	public default void setAnalogOutput(int channelIndex, Integer value) throws OpenemsNamedException {
		this.getAnalogOutputChannel(channelIndex).setNextWriteValue(value);
	}

	private static ChannelId channelId(String prefix, int channelIndex) {
		if (channelIndex < 1 || channelIndex > 16) {
			throw new IllegalArgumentException("Channel index must be between 1 and 16");
		}
		return ChannelId.valueOf("%s_%02d".formatted(prefix, channelIndex));
	}
}
