package io.openems.edge.meter.elecnova.PD194Z;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterElecnovaPD194Z extends ElectricityMeter, ModbusComponent, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		// Current per phase - Line 1
		L1_IA(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L1_IB(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L1_IC(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L1_I(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		// Current per phase - Line 2
		L2_IA(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L2_IB(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L2_IC(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L2_I(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		// Current per phase - Line 3
		L3_IA(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L3_IB(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L3_IC(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L3_I(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		// Current per phase - Line 4
		L4_IA(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L4_IB(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L4_IC(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		L4_I(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),

		// Active Power per phase - Line 1
		L1_PA(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L1_PB(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L1_PC(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L1_P(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		// Active Power per phase - Line 2
		L2_PA(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L2_PB(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L2_PC(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L2_P(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		// Active Power per phase - Line 3
		L3_PA(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L3_PB(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L3_PC(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L3_P(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		// Active Power per phase - Line 4
		L4_PA(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L4_PB(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L4_PC(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		L4_P(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),

		// Reactive Power per phase - Line 1
		L1_QA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L1_QB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L1_QC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L1_Q(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		// Reactive Power per phase - Line 2
		L2_QA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L2_QB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L2_QC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L2_Q(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		// Reactive Power per phase - Line 3
		L3_QA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L3_QB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L3_QC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L3_Q(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		// Reactive Power per phase - Line 4
		L4_QA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L4_QB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L4_QC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),
		L4_Q(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE_REACTIVE)),

		// Apparent Power per phase - Line 1
		L1_SA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L1_SB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L1_SC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L1_S(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		// Apparent Power per phase - Line 2
		L2_SA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L2_SB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L2_SC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L2_S(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		// Apparent Power per phase - Line 3
		L3_SA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L3_SB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L3_SC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L3_S(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		// Apparent Power per phase - Line 4
		L4_SA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L4_SB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L4_SC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),
		L4_S(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT_AMPERE)),

		// Power Factor per phase - Line 1
		L1_PFA(Doc.of(OpenemsType.FLOAT)),
		L1_PFB(Doc.of(OpenemsType.FLOAT)),
		L1_PFC(Doc.of(OpenemsType.FLOAT)),
		L1_PF(Doc.of(OpenemsType.FLOAT)),
		// Power Factor per phase - Line 2
		L2_PFA(Doc.of(OpenemsType.FLOAT)),
		L2_PFB(Doc.of(OpenemsType.FLOAT)),
		L2_PFC(Doc.of(OpenemsType.FLOAT)),
		L2_PF(Doc.of(OpenemsType.FLOAT)),
		// Power Factor per phase - Line 3
		L3_PFA(Doc.of(OpenemsType.FLOAT)),
		L3_PFB(Doc.of(OpenemsType.FLOAT)),
		L3_PFC(Doc.of(OpenemsType.FLOAT)),
		L3_PF(Doc.of(OpenemsType.FLOAT)),
		// Power Factor per phase - Line 4
		L4_PFA(Doc.of(OpenemsType.FLOAT)),
		L4_PFB(Doc.of(OpenemsType.FLOAT)),
		L4_PFC(Doc.of(OpenemsType.FLOAT)),
		L4_PF(Doc.of(OpenemsType.FLOAT)),
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