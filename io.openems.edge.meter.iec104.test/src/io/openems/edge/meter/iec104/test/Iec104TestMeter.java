package io.openems.edge.meter.iec104.test;

import io.openems.common.channel.Unit;
import io.openems.common.channel.AccessMode;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.bridge.iec104.api.Iec104Component;

/**
 * Interface for IEC104 Test Meter.
 * 
 * <p>
 * Demonstrates IEC104 bridge usage with various measurement types:
 * <ul>
 * <li>IOA 1001: Single Point (M_SP_NA_1)
 * <li>IOA 1003: Double Point (M_DP_NA_1)
 * <li>IOA 1005: Step Position (M_ST_NA_1)
 * <li>IOA 1009: Normalized Value (M_ME_NA_1)
 * <li>IOA 1011: Scaled Value (M_ME_NB_1)
 * <li>IOA 1013: Short Float (M_ME_NC_1)
 * </ul>
 */
public interface Iec104TestMeter extends Iec104Component {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Single Point Information (IOA 1001).
		 * 
		 * <p>
		 * ASDU Type: M_SP_NA_1 (Type ID 1)
		 */
		IOA_1001_SINGLE_POINT(Doc.of(OpenemsType.BOOLEAN).unit(Unit.NONE)),

		/**
		 * Double Point Information (IOA 1003).
		 * 
		 * <p>
		 * ASDU Type: M_DP_NA_1 (Type ID 3)
		 * 
		 * <p>
		 * Values: 0=Intermediate, 1=OFF, 2=ON, 3=Indeterminate
		 */
		IOA_1003_DOUBLE_POINT(Doc.of(OpenemsType.INTEGER).unit(Unit.NONE)),

		/**
		 * Step Position Information (IOA 1005).
		 * 
		 * <p>
		 * ASDU Type: M_ST_NA_1 (Type ID 5)
		 * 
		 * <p>
		 * Value range: -64..63
		 */
		IOA_1005_STEP_POSITION(Doc.of(OpenemsType.INTEGER).unit(Unit.NONE)),

		/**
		 * Normalized Value (IOA 1009).
		 * 
		 * <p>
		 * ASDU Type: M_ME_NA_1 (Type ID 9)
		 * 
		 * <p>
		 * Value range: -1.0 to +1.0
		 */
		IOA_1009_NORMALIZED_VALUE(Doc.of(OpenemsType.FLOAT).unit(Unit.NONE)),

		/**
		 * Scaled Value (IOA 1011).
		 * 
		 * <p>
		 * ASDU Type: M_ME_NB_1 (Type ID 11)
		 * 
		 * <p>
		 * 16-bit signed integer.
		 */
		IOA_1011_SCALED_VALUE(Doc.of(OpenemsType.INTEGER).unit(Unit.NONE)),

		/**
		 * Short Floating Point (IOA 1013).
		 * 
		 * <p>
		 * ASDU Type: M_ME_NC_1 (Type ID 13)
		 */
		IOA_1013_FLOAT_VALUE(Doc.of(OpenemsType.FLOAT).unit(Unit.NONE)),

		/**
		 * Single Point Information with Time Tag (IOA 1030).
		 * 
		 * <p>
		 * ASDU Type: M_SP_TB_1 (Type ID 30)
		 */
		IOA_1030_SINGLE_POINT_TIME(Doc.of(OpenemsType.BOOLEAN).unit(Unit.NONE)),

		/**
		 * Double Point Information with Time Tag (IOA 1031).
		 * 
		 * <p>
		 * ASDU Type: M_DP_TB_1 (Type ID 31)
		 * 
		 * <p>
		 * Values: 0=Intermediate, 1=OFF, 2=ON, 3=Indeterminate
		 */
		IOA_1031_DOUBLE_POINT_TIME(Doc.of(OpenemsType.INTEGER).unit(Unit.NONE)),

		/**
		 * Single command (IOA 2045, C_SC_NA_1, Type ID 45).
		 */
		IOA_2045_SINGLE_COMMAND(Doc.of(OpenemsType.BOOLEAN).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE)),

		/**
		 * Double command (IOA 2046, C_DC_NA_1, Type ID 46).
		 */
		IOA_2046_DOUBLE_COMMAND(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE)),

		/**
		 * Regulating step command (IOA 2047, C_RC_NA_1, Type ID 47).
		 */
		IOA_2047_REGULATING_STEP(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE)),

		/**
		 * Normalized setpoint command (IOA 2048, C_SE_NA_1, Type ID 48).
		 */
		IOA_2048_NORMALIZED_SETPOINT(Doc.of(OpenemsType.FLOAT).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE)),

		/**
		 * Float setpoint command (IOA 2050, C_SE_NC_1, Type ID 50).
		 */
		IOA_2050_FLOAT_SETPOINT(Doc.of(OpenemsType.FLOAT).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE)),

		/**
		 * Bitstring command (IOA 2051, C_BO_NA_1, Type ID 51).
		 */
		IOA_2051_BITSTRING_COMMAND(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE).unit(Unit.NONE));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the IEC104 Common Address configured for this meter.
	 * 
	 * @return the common address
	 */
	int getCommonAddress();

}
