package io.openems.edge.controller.alarm.threshold;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.Test;

public class AlarmEvaluatorTest {
	private static final Duration DEBOUNCE = Duration.ofSeconds(10);
	private static final Instant START = Instant.parse("2026-08-24T00:00:00Z");

	@Test
	public void testInitialActiveAlarmAndNoRepeat() {
		var evaluator = new AlarmEvaluator();
		assertFalse(evaluator.evaluate(1, START, DEBOUNCE).isPresent());
		var transition = evaluator.evaluate(1, START.plusSeconds(10), DEBOUNCE).orElseThrow();
		assertEquals(0, transition.oldLevel());
		assertEquals(1, transition.newLevel());
		assertFalse(evaluator.evaluate(1, START.plusSeconds(20), DEBOUNCE).isPresent());
	}

	@Test
	public void testDebounceResetsWhenValueChanges() {
		var evaluator = new AlarmEvaluator();
		evaluator.evaluate(0, START, DEBOUNCE);
		evaluator.evaluate(1, START.plusSeconds(1), DEBOUNCE);
		evaluator.evaluate(2, START.plusSeconds(5), DEBOUNCE);
		assertFalse(evaluator.evaluate(2, START.plusSeconds(14), DEBOUNCE).isPresent());
		var transition = evaluator.evaluate(2, START.plusSeconds(15), DEBOUNCE).orElseThrow();
		assertEquals(0, transition.oldLevel());
		assertEquals(2, transition.newLevel());
	}

	@Test
	public void testEscalationDowngradeAndRecovery() {
		var evaluator = new AlarmEvaluator();
		evaluator.evaluate(0, START, DEBOUNCE);
		assertChange(evaluator, 1, START.plusSeconds(1), 0, 1);
		assertChange(evaluator, 3, START.plusSeconds(20), 1, 3);
		assertChange(evaluator, 2, START.plusSeconds(40), 3, 2);
		var recovery = assertChange(evaluator, 0, START.plusSeconds(60), 2, 0);
		assertEquals(START.plusSeconds(11), recovery.alarmStartedAt());
		assertEquals(Duration.ofSeconds(59), recovery.alarmDuration());
		assertEquals(0, evaluator.confirmedLevel());
		assertFalse(evaluator.isPending());
	}

	private static AlarmEvaluator.LevelChange assertChange(AlarmEvaluator evaluator, int level, Instant start, int oldLevel,
			int newLevel) {
		assertFalse(evaluator.evaluate(level, start, DEBOUNCE).isPresent());
		var transition = evaluator.evaluate(level, start.plus(DEBOUNCE), DEBOUNCE).orElseThrow();
		assertEquals(oldLevel, transition.oldLevel());
		assertEquals(newLevel, transition.newLevel());
		assertTrue(evaluator.confirmedLevel() == newLevel);
		return transition;
	}
}
