package io.openems.edge.pvinverter.solis.ongrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverterAlarms;

/** Solis on-grid inverter with RTS monitoring and alarm channels. */
public interface PvInverterSolisOnGrid extends ManagedSymmetricPvInverter, RtsPvInverter, RtsPvInverterAlarms,
		OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		PRODUCT_MODEL(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		DSP_SOFTWARE_VERSION(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		LCD_SOFTWARE_VERSION(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		AC_OUTPUT_TYPE(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		DC_INPUT_TYPE(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		DEVICE_TYPE(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		INVERTER_STATUS(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		FAULT_CODE_01(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		FAULT_CODE_02(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		FAULT_CODE_03(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		FAULT_CODE_04(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		WORKING_STATUS(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH)), //
		P_LIMIT_RAW(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), // Signed value in units of 10 W
		PV_LIMIT_FAILED(Doc.of(Level.FAULT) //
				.text("PV-Limit failed"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
