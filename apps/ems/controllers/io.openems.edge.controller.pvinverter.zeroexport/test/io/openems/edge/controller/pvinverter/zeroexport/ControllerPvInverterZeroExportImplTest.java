package io.openems.edge.controller.pvinverter.zeroexport;

import static io.openems.edge.meter.api.ElectricityMeter.ChannelId.ACTIVE_POWER;
import static io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT;
import static io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER;

import org.junit.Test;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.api.modbus.evn.ControllerApiModbusEvn;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.meter.test.DummyElectricityMeter;
import io.openems.edge.pvinverter.test.DummyManagedSymmetricPvInverter;

public class ControllerPvInverterZeroExportImplTest {

	private static final class DummyEvnController extends AbstractDummyOpenemsComponent<DummyEvnController>
			implements ControllerApiModbusEvn {

		private DummyEvnController(String id) {
			super(id, OpenemsComponent.ChannelId.values(), Controller.ChannelId.values(),
					ControllerApiModbusEvn.ChannelId.values());
		}

		@Override
		protected DummyEvnController self() {
			return this;
		}

		@Override
		public void run() {
		}
	}

	@Test
	public void test() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setPowerLimit(10000) //
						.build()) //
				.deactivate();
	}

	@Test
	public void exportReducesPvProduction() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setPowerLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void missingGridMeasurementStopsPvProduction() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setPowerLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void unavailableEvnFallsBackToLocalZeroExport() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setAllowEvnControl(true) //
						.setPowerLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void evnPOutWattIsConvertedToNegativeGridTarget() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.addComponent(new DummyEvnController("ctrlEvnModbus0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setAllowEvnControl(true) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -10_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_ENABLED, true) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_SETPOINT_WATT, 5_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void evnPOutPercentIsConvertedToNegativeGridTarget() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.addComponent(new DummyEvnController("ctrlEvnModbus0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setAllowEvnControl(true) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -10_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_ENABLED, true) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_SETPOINT_PERCENT, 25f) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void evnZeroPOutOverridesNonZeroLocalTarget() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.addComponent(new DummyEvnController("ctrlEvnModbus0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setAllowEvnControl(true) //
						.setPowerLimit(5_000) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_ENABLED, true) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_SETPOINT_WATT, 0) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void negativeEvnPOutCannotRequestGridImport() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.addComponent(new DummyEvnController("ctrlEvnModbus0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setAllowEvnControl(true) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_ENABLED, true) //
						.input("ctrlEvnModbus0", ControllerApiModbusEvn.ChannelId.P_OUT_SETPOINT_WATT, -5_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void localPOutWattIsConvertedToNegativeGridTarget() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setPowerLimit(5_000) //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -10_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 10_750)) //
				.deactivate();
	}

	@Test
	public void missingInverterCapacityStopsPvProduction() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", ACTIVE_POWER, 12_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void missingInverterMeasurementStopsPvProduction() throws Exception {
		new ControllerTest(new ControllerPvInverterZeroExportImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyElectricityMeter("meter0")) //
				.addComponent(new DummyManagedSymmetricPvInverter("pvInverter0")) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.build()) //
				.next(new TestCase() //
						.input("meter0", ACTIVE_POWER, -5_000) //
						.input("pvInverter0", MAX_APPARENT_POWER, 20_000) //
						.output("pvInverter0", ACTIVE_POWER_LIMIT, 0)) //
				.deactivate();
	}

}
