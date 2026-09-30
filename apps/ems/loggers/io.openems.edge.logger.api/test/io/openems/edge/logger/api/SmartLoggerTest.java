package io.openems.edge.logger.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.test.AbstractDummyElectricityMeter;

public class SmartLoggerTest {

	@Test
	public void exposesStandardMeterAndEnergyChannels() {
		var sut = new DummySmartLogger();

		assertTrue(ElectricityMeter.class.isAssignableFrom(SmartLogger.class));
		assertSame(sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL), sut.getEnergyProductionTotalChannel());
		assertSame(sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY), sut.getEnergyProductionTodayChannel());
		assertSame(sut.channel(ElectricityMeter.ChannelId.ACTIVE_POWER), sut.getActivePowerChannel());
	}

	@Test
	public void definesEnergyChannelMetadata() {
		assertEquals(OpenemsType.LONG, SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL.doc().getType());
		assertEquals(Unit.WATT_HOURS, SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL.doc().getUnit());
		assertEquals(PersistencePriority.HIGH,
				SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL.doc().getPersistencePriority());
		assertEquals(OpenemsType.FLOAT, SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY.doc().getType());
		assertEquals(Unit.KILOWATT_HOURS, SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY.doc().getUnit());
		assertEquals(PersistencePriority.HIGH,
				SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY.doc().getPersistencePriority());
	}

	private static class DummySmartLogger extends AbstractDummyElectricityMeter<DummySmartLogger> implements SmartLogger {

		private DummySmartLogger() {
			super("smartLogger0", OpenemsComponent.ChannelId.values(), ElectricityMeter.ChannelId.values(),
					SmartLogger.ChannelId.values());
		}

		@Override
		protected DummySmartLogger self() {
			return this;
		}

		@Override
		public MeterType getMeterType() {
			return MeterType.PRODUCTION;
		}
	}
}
