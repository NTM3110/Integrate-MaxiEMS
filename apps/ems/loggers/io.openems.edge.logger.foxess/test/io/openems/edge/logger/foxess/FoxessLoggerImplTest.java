package io.openems.edge.logger.foxess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.gson.JsonElement;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.ReflectionUtils;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.test.DummyTimedata;

public class FoxessLoggerImplTest {

	private static final long INITIAL_TOTAL_PRODUCTION_WH = 123_000L;
	private static final long LATER_TOTAL_PRODUCTION_WH = 128_000L;
	private static final int ACTIVE_POWER_W = 5_432;

	@Test
	public void mapsProductionAndActivePowerToStandardChannels() throws Exception {
		var modbus = new DummyModbusBridge("modbus0") //
				.withRegisters(40323, registers());
		var sut = new FoxessLoggerImpl();
		sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL).setNextValue(INITIAL_TOTAL_PRODUCTION_WH);

		var test = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", modbus) //
				.activate(MyConfig.create().build());

		// The first cycle registers the Modbus task; the second evaluates its values.
		test.next(new TestCase()) //
				.next(new TestCase() //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL, LATER_TOTAL_PRODUCTION_WH) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY, 5F) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, ACTIVE_POWER_W));
	}

	@Test
	public void restoresBaselineWhenFirstTotalAfterMidnightFollowsNull() throws Exception {
		var sut = new FoxessLoggerImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create().build());

		var address = new ChannelAddress(sut.id(), SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL.id());
		var historicDataRequested = new CountDownLatch(1);
		var timedata = new DummyTimedata("timedata0") {
			@Override
			public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
					ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
					throws OpenemsNamedException {
				historicDataRequested.countDown();
				return super.queryHistoricData(edgeId, fromDate, toDate, channels, resolution);
			}
		};
		var now = ZonedDateTime.now();
		timedata.add(now.toLocalDate().atStartOfDay(now.getZone()), address, (int) INITIAL_TOTAL_PRODUCTION_WH);
		ReflectionUtils.setAttributeViaReflection(sut, "timedata", timedata);
		ReflectionUtils.setAttributeViaReflection(sut, "energyProductionTodayDay", LocalDate.now().minusDays(1));

		var restoredDailyEnergy = new CountDownLatch(1);
		sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY).onSetNextValue(value -> {
			if (Float.valueOf(5F).equals(value.get())) {
				restoredDailyEnergy.countDown();
			}
		});
		sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL).setNextValue(null);
		assertFalse("Null total must not trigger Timedata restore", historicDataRequested.await(200, TimeUnit.MILLISECONDS));
		sut.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL).setNextValue(LATER_TOTAL_PRODUCTION_WH);

		assertTrue("Timedata baseline was not requested", historicDataRequested.await(1, TimeUnit.SECONDS));
		assertTrue("Timedata baseline was not restored", restoredDailyEnergy.await(1, TimeUnit.SECONDS));
		assertEquals(Float.valueOf(5F), sut.getEnergyProductionTodayChannel().getNextValue().get());
	}

	private static int[] registers() {
		var registers = new int[61];
		var total = toRegisters(LATER_TOTAL_PRODUCTION_WH / 100);
		var activePower = toRegisters(ACTIVE_POWER_W);
		registers[40378 - 40323] = total[0];
		registers[40379 - 40323] = total[1];
		registers[40382 - 40323] = activePower[0];
		registers[40383 - 40323] = activePower[1];
		return registers;
	}

	private static int[] toRegisters(long value) {
		return new int[] { //
				(int) (value >>> 16) & 0xFFFF, //
				(int) value & 0xFFFF //
		};
	}
}
