package io.openems.edge.pvinverter.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.ChannelId.ChannelIdImpl;
import io.openems.edge.common.channel.Doc;

/** Creates stable dynamic Channel-IDs for RTS inverter DC inputs. */
public final class RtsPvInverterChannelIdFactory {

	private static final Map<Integer, ChannelId> VOLTAGE_CHANNELS = new ConcurrentHashMap<>();
	private static final Map<Integer, ChannelId> CURRENT_CHANNELS = new ConcurrentHashMap<>();
	private static final Map<Integer, ChannelId> POWER_CHANNELS = new ConcurrentHashMap<>();

	private RtsPvInverterChannelIdFactory() {
	}

	public static ChannelId dcVoltage(int inputIndex) {
		validateInputIndex(inputIndex);
		if (inputIndex <= RtsPvInverter.PREDEFINED_DC_INPUT_COUNT) {
			return RtsPvInverter.ChannelId.valueOf("DC" + inputIndex + "_VOLTAGE");
		}
		return VOLTAGE_CHANNELS.computeIfAbsent(inputIndex,
				i -> channelId("DC" + i + "_VOLTAGE", Unit.MILLIVOLT));
	}

	public static ChannelId dcCurrent(int inputIndex) {
		validateInputIndex(inputIndex);
		if (inputIndex <= RtsPvInverter.PREDEFINED_DC_INPUT_COUNT) {
			return RtsPvInverter.ChannelId.valueOf("DC" + inputIndex + "_CURRENT");
		}
		return CURRENT_CHANNELS.computeIfAbsent(inputIndex,
				i -> channelId("DC" + i + "_CURRENT", Unit.MILLIAMPERE));
	}

	public static ChannelId dcPower(int inputIndex) {
		validateInputIndex(inputIndex);
		return POWER_CHANNELS.computeIfAbsent(inputIndex, i -> channelId("DC" + i + "_POWER", Unit.WATT));
	}

	/**
	 * Gets voltage, current and power Channel-IDs for inputs {@code 1..count}.
	 *
	 * @param count number of physical DC inputs
	 * @return immutable, deterministic list
	 */
	public static List<ChannelId> dcInputChannels(int count) {
		if (count < 0) {
			throw new IllegalArgumentException("DC input count must not be negative");
		}
		var result = new ArrayList<ChannelId>(count * 3);
		for (var i = 1; i <= count; i++) {
			result.add(dcVoltage(i));
			result.add(dcCurrent(i));
			result.add(dcPower(i));
		}
		return Collections.unmodifiableList(result);
	}

	private static ChannelId channelId(String name, Unit unit) {
		return new ChannelIdImpl(name, Doc.of(OpenemsType.INTEGER) //
				.unit(unit) //
				.persistencePriority(PersistencePriority.HIGH));
	}

	private static void validateInputIndex(int inputIndex) {
		if (inputIndex < 1) {
			throw new IllegalArgumentException("DC input index starts at 1");
		}
	}
}
