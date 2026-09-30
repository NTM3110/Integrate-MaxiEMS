package io.openems.edge.logger.huawei.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.openems.common.channel.AccessMode;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.taskmanager.Priority;

/**
 * Builds the read side of the Huawei SmartLogger Modbus protocol for a host
 * component. The host owns channel registration and element-to-channel mapping.
 */
public class HuaweiSmartLoggerProtocol {

	private static final int INVERTER_REGISTER_GAP = 25;
	private static final int INVERTER_BASE_ADDRESS = 51000;
	private static final int MAX_REGISTERS_PER_READ = 120;

	private final Host host;
	private final List<Integer> inverterAddresses;

	public HuaweiSmartLoggerProtocol(Host host, List<Integer> inverterAddresses) {
		this.host = host;
		this.inverterAddresses = inverterAddresses.stream() //
				.filter(address -> address > 0) //
				.distinct() //
				.sorted() //
				.toList();
	}

	/**
	 * Creates SmartLogger aggregate reads followed by batched per-inverter reads.
	 *
	 * @param aggregateChannels the target channels for the aggregate registers
	 * @return read tasks in protocol order
	 */
	public List<FC3ReadRegistersTask> createReadTasks(AggregateChannels aggregateChannels) {
		var tasks = new ArrayList<FC3ReadRegistersTask>();
		tasks.add(new FC3ReadRegistersTask(40525, Priority.HIGH, this.host.createElement(//
				aggregateChannels.activePower(), 40525, InverterRegister.DataType.I32, 0)));
		tasks.add(new FC3ReadRegistersTask(40560, Priority.HIGH, //
				this.host.createElement(aggregateChannels.energyProductionTotal(), 40560, InverterRegister.DataType.U32, 2), //
				this.host.createElement(aggregateChannels.energyProductionToday(), 40562, InverterRegister.DataType.U32, 2)));
		tasks.addAll(this.createInverterReadTasks());
		return tasks;
	}

	private List<FC3ReadRegistersTask> createInverterReadTasks() {
		var points = new ArrayList<InverterRegisterPoint>();
		for (var address : this.inverterAddresses) {
			var baseAddress = INVERTER_BASE_ADDRESS + (address - 1) * INVERTER_REGISTER_GAP;
			for (var register : InverterRegister.values()) {
				var channelId = this.createInverterChannelId(address, register);
				this.host.addChannel(channelId);
				points.add(new InverterRegisterPoint(baseAddress + register.offset, register, channelId));
			}
		}
		points.sort(Comparator.comparingInt(InverterRegisterPoint::address));

		var tasks = new ArrayList<FC3ReadRegistersTask>();
		var index = 0;
		while (index < points.size()) {
			var blockStart = points.get(index).address();
			var nextAddress = blockStart;
			var elements = new ArrayList<ModbusElement>();
			while (index < points.size()) {
				var point = points.get(index);
				var gap = point.address() - nextAddress;
				var length = getRegisterLength(point.register().dataType);
				var projectedLength = point.address() - blockStart + length;
				if (!elements.isEmpty() && projectedLength > MAX_REGISTERS_PER_READ) {
					break;
				}
				if (gap > 0) {
					elements.add(new DummyRegisterElement(nextAddress, point.address() - 1));
				}
				elements.add(this.host.createElement(point.channelId(), point.address(), point.register().dataType,
						point.register().scaleExponent));
				nextAddress = point.address() + length;
				index++;
			}
			tasks.add(new FC3ReadRegistersTask(blockStart, Priority.HIGH, elements.toArray(ModbusElement[]::new)));
		}
		return tasks;
	}

	private ChannelId createInverterChannelId(int address, InverterRegister register) {
		var channelName = "_PV_INVERTER" + address + "/" + register.name;
		return new ChannelId() {
			@Override
			public String name() {
				return channelName;
			}

			@Override
			public Doc doc() {
				return new IntegerDoc().accessMode(AccessMode.READ_ONLY).unit(register.unit);
			}
		};
	}

	private static int getRegisterLength(InverterRegister.DataType dataType) {
		return switch (dataType) {
		case U16, I16 -> 1;
		case U32, I32 -> 2;
		};
	}

	public record AggregateChannels(ChannelId activePower, ChannelId energyProductionTotal,
			ChannelId energyProductionToday) {
	}

	public interface Host {
		void addChannel(ChannelId channelId);

		ModbusElement createElement(ChannelId channelId, int address, InverterRegister.DataType dataType,
				int scaleExponent);
	}

	private record InverterRegisterPoint(int address, InverterRegister register, ChannelId channelId) {
	}
}
