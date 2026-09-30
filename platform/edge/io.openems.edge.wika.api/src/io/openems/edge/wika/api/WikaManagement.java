package io.openems.edge.wika.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface WikaManagement extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		P_BAR(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.BAR)
				.persistencePriority(PersistencePriority.HIGH)),
		P_MPA(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MEGA_PASCAL)
				.persistencePriority(PersistencePriority.HIGH)),
		P_PA(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PASCAL)
				.persistencePriority(PersistencePriority.HIGH)),
		P_KPA(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILO_PASCAL)
				.persistencePriority(PersistencePriority.HIGH)),
		P_PSI(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.POUND_FORCE_PER_SQUARE_INCH)
				.persistencePriority(PersistencePriority.HIGH)),
		P_NCM3(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.NEWTON_PER_SQUARE_CENTIMETER)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMP_C(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMP_K(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KELVIN)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMP_F(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.FAHREHEIT)
				.persistencePriority(PersistencePriority.HIGH)),
		RHO_GL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.GRAMS_PER_LIT)
				.persistencePriority(PersistencePriority.HIGH)),
		RHO_KGM3(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILO_GRAMS_PER_CUBIC_METER)),
		P20_BAR(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.BAR)
				.persistencePriority(PersistencePriority.HIGH)),
		/**
		 * Tempurature sensor.
		 */
		SENSOR_TEMPURATURE(Doc.of(OpenemsType.FLOAT) //
				.unit(Unit.DEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH)), //
		RELATIVE_HUMIDITY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.RELATIVE_HUMIDITY)
				.persistencePriority(PersistencePriority.HIGH)),
		/**
		 * Infomation Device.
		 */
		ADDRESS(Doc.of(OpenemsType.FLOAT)),
		BAUDRATE(Doc.of(OpenemsType.INTEGER)),
		PARITY(Doc.of(OpenemsType.BOOLEAN)),
		SERIAL_NUMBER(Doc.of(OpenemsType.STRING)),
		H_W_VERSION(Doc.of(OpenemsType.STRING)),
		S_W_VERSION(Doc.of(OpenemsType.STRING)),
		TAG_NUMBER(Doc.of(OpenemsType.STRING))
		;

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
