package io.openems.edge.logger.huawei.common;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;

public class HuaweiSmartLoggerProtocolTest {

	@Test
	public void createsAggregateAndBatchedInverterReadTasks() {
		var host = new CapturingHost();
		var protocol = new HuaweiSmartLoggerProtocol(host, List.of(1, 2));

		var tasks = protocol.createReadTasks(new HuaweiSmartLoggerProtocol.AggregateChannels(//
				channel("ACTIVE_POWER"), channel("ENERGY_PRODUCTION_TOTAL"), channel("ENERGY_PRODUCTION_TODAY")));

		assertEquals(3, tasks.size());
		assertTask(tasks.get(0), 40525, 2);
		assertTask(tasks.get(1), 40560, 4);
		assertTask(tasks.get(2), 51000, 43);
		assertEquals(List.of("_PV_INVERTER1/ACTIVE_POWER", "_PV_INVERTER2/ACTIVE_POWER"),
				host.channelNames.stream().filter(name -> name.endsWith("/ACTIVE_POWER")).toList());
		assertEquals(24, host.channelNames.size());
	}

	@Test
	public void splitsInverterReadsAtTheMaximumRegisterCount() {
		var protocol = new HuaweiSmartLoggerProtocol(new CapturingHost(), List.of(1, 2, 3, 4, 5, 6));

		var tasks = protocol.createReadTasks(aggregateChannels());

		assertEquals(4, tasks.size());
		assertTask(tasks.get(2), 51000, 118);
		assertTask(tasks.get(3), 51125, 18);
	}

	@Test
	public void createsReadOnlyDynamicChannelsWithRegisterUnits() {
		var host = new CapturingHost();
		var protocol = new HuaweiSmartLoggerProtocol(host, List.of(1));

		protocol.createReadTasks(aggregateChannels());

		var activePower = host.channelId("_PV_INVERTER1/ACTIVE_POWER");
		assertEquals(AccessMode.READ_ONLY, activePower.doc().getAccessMode());
		assertEquals(Unit.WATT, activePower.doc().getUnit());
	}

	private static void assertTask(FC3ReadRegistersTask task, int startAddress, int length) {
		assertEquals(startAddress, task.getStartAddress());
		assertEquals(length, task.getLength());
	}

	private static ChannelId channel(String name) {
		return new ChannelId() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public Doc doc() {
				return new IntegerDoc().accessMode(AccessMode.READ_ONLY).unit(Unit.NONE);
			}
		};
	}

	private static HuaweiSmartLoggerProtocol.AggregateChannels aggregateChannels() {
		return new HuaweiSmartLoggerProtocol.AggregateChannels(//
				channel("ACTIVE_POWER"), channel("ENERGY_PRODUCTION_TOTAL"), channel("ENERGY_PRODUCTION_TODAY"));
	}

	private static class CapturingHost implements HuaweiSmartLoggerProtocol.Host {
		private final List<String> channelNames = new ArrayList<>();
		private final List<ChannelId> channelIds = new ArrayList<>();

		@Override
		public void addChannel(ChannelId channelId) {
			this.channelNames.add(channelId.name());
			this.channelIds.add(channelId);
		}

		private ChannelId channelId(String name) {
			return this.channelIds.stream().filter(channelId -> channelId.name().equals(name)).findFirst().orElseThrow();
		}

		@Override
		public ModbusElement createElement(ChannelId channelId, int address, InverterRegister.DataType dataType,
				int scaleExponent) {
			var length = switch (dataType) {
			case U16, I16 -> 1;
			case U32, I32 -> 2;
			};
			return new DummyRegisterElement(address, address + length - 1);
		}
	}
}
