package io.openems.edge.pvinverter.api;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

public interface HybridInverterControl extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		WRITE_ENABLED(booleanRead()),
		LAST_COMMAND_RESULT(Doc.of(CommandResult.values()).persistencePriority(PersistencePriority.HIGH)),
		LAST_COMMAND_REJECTION_REASON(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.HIGH)),
		EMS_MODE_REQUEST(integerWrite(Unit.NONE)),
		EMS_MODE_READBACK(integerRead(Unit.NONE)),
		BATTERY_POWER_REFERENCE_REQUEST(integerWrite(Unit.THOUSANDTH)),
		BATTERY_POWER_REFERENCE_READBACK(integerRead(Unit.THOUSANDTH)),
		ACTIVE_POWER_LIMIT_REQUEST(integerWrite(Unit.WATT)),
		ACTIVE_POWER_LIMIT_READBACK(integerRead(Unit.WATT)),
		RESERVE_SOC_REQUEST(integerWrite(Unit.PERCENT)),
		RESERVE_SOC_READBACK(integerRead(Unit.PERCENT)),
		CHARGE_CURRENT_LIMIT_REQUEST(integerWrite(Unit.MILLIAMPERE)),
		CHARGE_CURRENT_LIMIT_READBACK(integerRead(Unit.MILLIAMPERE)),
		DISCHARGE_CURRENT_LIMIT_REQUEST(integerWrite(Unit.MILLIAMPERE)),
		DISCHARGE_CURRENT_LIMIT_READBACK(integerRead(Unit.MILLIAMPERE)),
		SYSTEM_COMMAND_REQUEST(Doc.of(SystemCommand.values()).accessMode(AccessMode.WRITE_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
		SYSTEM_COMMAND_READBACK(Doc.of(SystemCommand.values()).persistencePriority(PersistencePriority.HIGH)),
		GRID_CHARGE_ENABLE_REQUEST(Doc.of(OpenemsType.BOOLEAN).accessMode(AccessMode.WRITE_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
		GRID_CHARGE_ENABLE_READBACK(booleanRead());

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	private static Doc booleanRead() {
		return Doc.of(OpenemsType.BOOLEAN).persistencePriority(PersistencePriority.HIGH);
	}

	private static Doc integerRead(Unit unit) {
		return Doc.of(OpenemsType.INTEGER).unit(unit).persistencePriority(PersistencePriority.HIGH);
	}

	private static Doc integerWrite(Unit unit) {
		return Doc.of(OpenemsType.INTEGER).unit(unit).accessMode(AccessMode.WRITE_ONLY)
				.persistencePriority(PersistencePriority.HIGH);
	}

	public default BooleanReadChannel getWriteEnabledChannel() {
		return this.channel(ChannelId.WRITE_ENABLED);
	}

	public default Value<Boolean> getWriteEnabled() {
		return this.getWriteEnabledChannel().value();
	}

	public default void _setWriteEnabled(Boolean value) {
		this.getWriteEnabledChannel().setNextValue(value);
	}

	public default EnumReadChannel getLastCommandResultChannel() {
		return this.channel(ChannelId.LAST_COMMAND_RESULT);
	}

	public default Value<Integer> getLastCommandResult() {
		return this.getLastCommandResultChannel().value();
	}

	public default void _setLastCommandResult(CommandResult value) {
		this.getLastCommandResultChannel().setNextValue(value == null ? null : value.getValue());
	}

	public default StringReadChannel getLastCommandRejectionReasonChannel() {
		return this.channel(ChannelId.LAST_COMMAND_REJECTION_REASON);
	}

	public default Value<String> getLastCommandRejectionReason() {
		return this.getLastCommandRejectionReasonChannel().value();
	}

	public default void _setLastCommandRejectionReason(String value) {
		this.getLastCommandRejectionReasonChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getEmsModeRequestChannel() {
		return this.channel(ChannelId.EMS_MODE_REQUEST);
	}

	public default void setEmsModeRequest(Integer value) throws OpenemsNamedException {
		this.getEmsModeRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getEmsModeReadbackChannel() {
		return this.channel(ChannelId.EMS_MODE_READBACK);
	}

	public default Value<Integer> getEmsModeReadback() {
		return this.getEmsModeReadbackChannel().value();
	}

	public default void _setEmsModeReadback(Integer value) {
		this.getEmsModeReadbackChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getBatteryPowerReferenceRequestChannel() {
		return this.channel(ChannelId.BATTERY_POWER_REFERENCE_REQUEST);
	}

	public default void setBatteryPowerReferenceRequest(Integer value) throws OpenemsNamedException {
		this.getBatteryPowerReferenceRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getBatteryPowerReferenceReadbackChannel() {
		return this.channel(ChannelId.BATTERY_POWER_REFERENCE_READBACK);
	}

	public default Value<Integer> getBatteryPowerReferenceReadback() {
		return this.getBatteryPowerReferenceReadbackChannel().value();
	}

	public default void _setBatteryPowerReferenceReadback(Integer value) {
		this.getBatteryPowerReferenceReadbackChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getActivePowerLimitRequestChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_LIMIT_REQUEST);
	}

	public default void setActivePowerLimitRequest(Integer value) throws OpenemsNamedException {
		this.getActivePowerLimitRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getActivePowerLimitReadbackChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_LIMIT_READBACK);
	}

	public default Value<Integer> getActivePowerLimitReadback() {
		return this.getActivePowerLimitReadbackChannel().value();
	}

	public default void _setActivePowerLimitReadback(Integer value) {
		this.getActivePowerLimitReadbackChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getReserveSocRequestChannel() {
		return this.channel(ChannelId.RESERVE_SOC_REQUEST);
	}

	public default void setReserveSocRequest(Integer value) throws OpenemsNamedException {
		this.getReserveSocRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getReserveSocReadbackChannel() {
		return this.channel(ChannelId.RESERVE_SOC_READBACK);
	}

	public default Value<Integer> getReserveSocReadback() {
		return this.getReserveSocReadbackChannel().value();
	}

	public default void _setReserveSocReadback(Integer value) {
		this.getReserveSocReadbackChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getChargeCurrentLimitRequestChannel() {
		return this.channel(ChannelId.CHARGE_CURRENT_LIMIT_REQUEST);
	}

	public default void setChargeCurrentLimitRequest(Integer value) throws OpenemsNamedException {
		this.getChargeCurrentLimitRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getChargeCurrentLimitReadbackChannel() {
		return this.channel(ChannelId.CHARGE_CURRENT_LIMIT_READBACK);
	}

	public default Value<Integer> getChargeCurrentLimitReadback() {
		return this.getChargeCurrentLimitReadbackChannel().value();
	}

	public default void _setChargeCurrentLimitReadback(Integer value) {
		this.getChargeCurrentLimitReadbackChannel().setNextValue(value);
	}

	public default IntegerWriteChannel getDischargeCurrentLimitRequestChannel() {
		return this.channel(ChannelId.DISCHARGE_CURRENT_LIMIT_REQUEST);
	}

	public default void setDischargeCurrentLimitRequest(Integer value) throws OpenemsNamedException {
		this.getDischargeCurrentLimitRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getDischargeCurrentLimitReadbackChannel() {
		return this.channel(ChannelId.DISCHARGE_CURRENT_LIMIT_READBACK);
	}

	public default Value<Integer> getDischargeCurrentLimitReadback() {
		return this.getDischargeCurrentLimitReadbackChannel().value();
	}

	public default void _setDischargeCurrentLimitReadback(Integer value) {
		this.getDischargeCurrentLimitReadbackChannel().setNextValue(value);
	}

	public default EnumWriteChannel getSystemCommandRequestChannel() {
		return this.channel(ChannelId.SYSTEM_COMMAND_REQUEST);
	}

	public default void setSystemCommandRequest(SystemCommand value) throws OpenemsNamedException {
		this.getSystemCommandRequestChannel().setNextWriteValue(value);
	}

	public default EnumReadChannel getSystemCommandReadbackChannel() {
		return this.channel(ChannelId.SYSTEM_COMMAND_READBACK);
	}

	public default Value<Integer> getSystemCommandReadback() {
		return this.getSystemCommandReadbackChannel().value();
	}

	public default void _setSystemCommandReadback(SystemCommand value) {
		this.getSystemCommandReadbackChannel().setNextValue(value == null ? null : value.getValue());
	}

	public default BooleanWriteChannel getGridChargeEnableRequestChannel() {
		return this.channel(ChannelId.GRID_CHARGE_ENABLE_REQUEST);
	}

	public default void setGridChargeEnableRequest(Boolean value) throws OpenemsNamedException {
		this.getGridChargeEnableRequestChannel().setNextWriteValue(value);
	}

	public default BooleanReadChannel getGridChargeEnableReadbackChannel() {
		return this.channel(ChannelId.GRID_CHARGE_ENABLE_READBACK);
	}

	public default Value<Boolean> getGridChargeEnableReadback() {
		return this.getGridChargeEnableReadbackChannel().value();
	}

	public default void _setGridChargeEnableReadback(Boolean value) {
		this.getGridChargeEnableReadbackChannel().setNextValue(value);
	}
}
