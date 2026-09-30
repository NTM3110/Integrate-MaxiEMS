package io.openems.edge.bms.service.alarms.implement;

import java.util.EnumMap;
import java.util.Map;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;

/** Versioned lifecycle policy applied after instantaneous alarm evaluation. */
public final class AlarmLifecyclePolicy {

	public static final int VERSION = 2;

	public enum HysteresisUnit {
		ABSOLUTE, THRESHOLD_FRACTION
	}

	public record Rule(int raiseSamples, int clearSamples, double hysteresis,
			HysteresisUnit hysteresisUnit, boolean latching) {
		public Rule {
			if (raiseSamples < 1 || clearSamples < 1) {
				throw new IllegalArgumentException("Alarm debounce samples must be positive");
			}
			if (!Double.isFinite(hysteresis) || hysteresis < 0) {
				throw new IllegalArgumentException("Alarm hysteresis must be finite and non-negative");
			}
		}
	}

	private final Map<Alarm, Rule> rules;

	private AlarmLifecyclePolicy(Map<Alarm, Rule> rules) {
		this.rules = Map.copyOf(rules);
		if (this.rules.size() != Alarm.values().length) {
			throw new IllegalArgumentException("Every stable alarm must have one lifecycle rule");
		}
	}

	public static AlarmLifecyclePolicy defaults() {
		var rules = new EnumMap<Alarm, Rule>(Alarm.class);
		put(rules, Alarm.LOW_CELL_VOLTAGE, 3, 3, 0.01, HysteresisUnit.THRESHOLD_FRACTION, false);
		put(rules, Alarm.HIGH_CELL_VOLTAGE, 3, 3, 0.01, HysteresisUnit.THRESHOLD_FRACTION, false);
		put(rules, Alarm.BATTERY_VOLTAGE_LOW, 3, 3, 2.0, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.BATTERY_VOLTAGE_HIGH, 3, 3, 2.0, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.CELL_RESISTANCE, 3, 3, 0.05, HysteresisUnit.THRESHOLD_FRACTION, false);
		put(rules, Alarm.CELL_TEMPERATURE, 3, 3, 1.0, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.FLOAT_CHARGE_OVERCURRENT, 3, 3, 0.05, HysteresisUnit.THRESHOLD_FRACTION, false);
		put(rules, Alarm.CHARGE_OVERCURRENT, 3, 3, 0.05, HysteresisUnit.THRESHOLD_FRACTION, false);
		put(rules, Alarm.LOW_SOC, 3, 3, 2.0, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.HIGH_AMBIENT_TEMPERATURE, 3, 3, 2.0, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.MIDPOINT_VOLTAGE, 3, 3, 0.5, HysteresisUnit.ABSOLUTE, false);
		put(rules, Alarm.SENSOR_MODULE_FAILURE, 1, 3, 0, HysteresisUnit.ABSOLUTE, false);
		return new AlarmLifecyclePolicy(rules);
	}

	private static void put(EnumMap<Alarm, Rule> rules, Alarm alarm, int raise, int clear,
			double hysteresis, HysteresisUnit unit, boolean latching) {
		rules.put(alarm, new Rule(raise, clear, hysteresis, unit, latching));
	}

	public Rule rule(Alarm alarm) {
		return this.rules.get(alarm);
	}

	public Map<Alarm, Rule> rules() {
		return this.rules;
	}
}
