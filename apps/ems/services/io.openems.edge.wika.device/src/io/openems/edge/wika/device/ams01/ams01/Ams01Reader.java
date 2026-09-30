package io.openems.edge.wika.device.ams01.ams01;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.wika.api.WikaAms01Alarm;

public interface Ams01Reader extends WikaAms01Alarm {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		// --- Temperature sensors ---
		IN_TEMP_A(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		IN_TEMP_B(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		IN_TEMP_C(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		OUT_TEMP_A(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		OUT_TEMP_B(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		OUT_TEMP_C(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		MBA_TEMP_A(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		MBA_TEMP_B(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		MBA_TEMP_C(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),

		// --- Environmental sensors ---
		ENVIRONMENTAL_TEMPERATURE(
				Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),
		ENVIRONMENTAL_HUMIDITY(Doc.of(OpenemsType.FLOAT).unit(Unit.PERCENT).persistencePriority(PersistencePriority.HIGH)),
		ENVIRONMENTAL_DEWPOINT(
				Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.HIGH)),

		// --- PD data ---
		IN_PD_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		IN_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_PD_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		OUT_PD_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		OUT_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_PD_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		MBA_PD_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		MBA_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_PD_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		// --- PD presence ---
		IN_PD_PRESENCE_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_PRESENCE_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		IN_PRESENCE_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_PRESENCE_PD_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		OUT_PD_PRESENCE_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_PRESENCE_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		OUT_PRESENCE_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_PD_PRESENCE_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		MBA_PD_PRESENCE_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_PRESENCE_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		MBA_PRESENCE_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_PD_PRESENCE_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		// --- PD early ---
		IN_PD_EARLY_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_EARLY_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		IN_EARLY_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		IN_PD_EARLY_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		OUT_PD_EARLY_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_EARLY_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		OUT_EARLY_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		OUT_PD_EARLY_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),

		MBA_PD_EARLY_CODE(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_EARLY_RATIO(Doc.of(OpenemsType.FLOAT).unit(Unit.DECIBEL).persistencePriority(PersistencePriority.HIGH)),
		MBA_EARLY_EPPC(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH)),
		MBA_PD_EARLY_LEVEL(Doc.of(OpenemsType.FLOAT).persistencePriority(PersistencePriority.HIGH));

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
