package io.openems.edge.bms.service.alarms.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Observation;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.State;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Status;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Transition;

public class AlarmLifecycleStateMachineTest {

	@Test
	public void policyCoversAllStableAlarmBits() {
		var policy = AlarmLifecyclePolicy.defaults();
		assertEquals(2, AlarmLifecyclePolicy.VERSION);
		assertEquals(12, Alarm.values().length);
		assertEquals(12, policy.rules().size());
		for (var alarm : Alarm.values()) {
			assertEquals(alarm.ordinal(), alarm.bit());
			assertTrue(policy.rule(alarm).raiseSamples() > 0);
			assertTrue(policy.rule(alarm).clearSamples() > 0);
		}
		assertRule(policy, Alarm.LOW_CELL_VOLTAGE, 3, 3, 0.01, false);
		assertRule(policy, Alarm.HIGH_CELL_VOLTAGE, 3, 3, 0.01, false);
		assertRule(policy, Alarm.BATTERY_VOLTAGE_LOW, 3, 3, 2.0, false);
		assertRule(policy, Alarm.BATTERY_VOLTAGE_HIGH, 3, 3, 2.0, false);
		assertRule(policy, Alarm.CELL_RESISTANCE, 3, 3, 0.05, false);
		assertRule(policy, Alarm.CELL_TEMPERATURE, 3, 3, 1.0, false);
		assertRule(policy, Alarm.FLOAT_CHARGE_OVERCURRENT, 3, 3, 0.05, false);
		assertRule(policy, Alarm.CHARGE_OVERCURRENT, 3, 3, 0.05, false);
		assertRule(policy, Alarm.LOW_SOC, 3, 3, 2.0, false);
		assertRule(policy, Alarm.HIGH_AMBIENT_TEMPERATURE, 3, 3, 2.0, false);
		assertRule(policy, Alarm.MIDPOINT_VOLTAGE, 3, 3, 0.5, false);
		assertRule(policy, Alarm.SENSOR_MODULE_FAILURE, 1, 3, 0.0, false);
	}

	@Test
	public void nonLatchingAlarmDebouncesRaiseAndClear() {
		var rule = AlarmLifecyclePolicy.defaults().rule(Alarm.LOW_SOC);
		var state = State.inactive();
		state = apply(state, Observation.ACTIVE, rule).state();
		assertEquals(Status.PENDING_RAISE, state.status());
		state = apply(state, Observation.ACTIVE, rule).state();
		var raised = apply(state, Observation.ACTIVE, rule);
		assertEquals(Status.ACTIVE, raised.state().status());
		assertEquals(java.util.List.of(Transition.RAISED), raised.transitions());

		var suspended = apply(raised.state(), Observation.SUSPENDED, rule);
		assertEquals(raised.state(), suspended.state());
		var firstClear = apply(suspended.state(), Observation.CLEAR, rule);
		var secondClear = apply(firstClear.state(), Observation.CLEAR, rule);
		var cleared = apply(secondClear.state(), Observation.CLEAR, rule);
		assertEquals(Status.INACTIVE, cleared.state().status());
		assertEquals(java.util.List.of(Transition.CONDITION_CLEARED, Transition.CLEARED),
				cleared.transitions());
	}

	@Test
	public void defaultRuleAutomaticallyClearsWithoutAcknowledge() {
		var rule = AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT);
		var state = State.inactive();
		for (int i = 0; i < rule.raiseSamples(); i++) state = apply(state, Observation.ACTIVE, rule).state();
		for (int i = 0; i < rule.clearSamples() - 1; i++) state = apply(state, Observation.CLEAR, rule).state();
		var cleared = apply(state, Observation.CLEAR, rule);
		assertEquals(Status.INACTIVE, cleared.state().status());
		assertEquals(java.util.List.of(Transition.CONDITION_CLEARED, Transition.CLEARED),
				cleared.transitions());
	}

	@Test
	public void legacyLatchedStateClosesUnderNonLatchingPolicy() {
		var rule = AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT);
		var cleared = apply(new State(Status.LATCHED, 0, 0, false), Observation.CLEAR, rule);
		assertEquals(Status.INACTIVE, cleared.state().status());
		assertEquals(java.util.List.of(Transition.CLEARED), cleared.transitions());
	}

	private static AlarmLifecycleStateMachine.Result apply(State state, Observation observation,
			AlarmLifecyclePolicy.Rule rule) {
		return AlarmLifecycleStateMachine.apply(state, observation, rule);
	}

	private static void assertRule(AlarmLifecyclePolicy policy, Alarm alarm, int raiseSamples, int clearSamples,
			double hysteresis, boolean latching) {
		var rule = policy.rule(alarm);
		assertEquals(raiseSamples, rule.raiseSamples());
		assertEquals(clearSamples, rule.clearSamples());
		assertEquals(hysteresis, rule.hysteresis(), 0.000001);
		assertEquals(latching, rule.latching());
	}
}
