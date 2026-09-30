package io.openems.edge.logger.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.time.LocalDate;

import org.junit.Test;

public class EnergyProductionTodayCalculatorTest {

	private static final LocalDate DAY_ONE = LocalDate.of(2026, 7, 29);
	private static final LocalDate DAY_TWO = LocalDate.of(2026, 7, 30);

	@Test
	public void updatesNormalIncrements() {
		final var calculator = new EnergyProductionTodayCalculator();

		assertEquals(0F, calculator.update(DAY_ONE, 1_000L), 0F);
		assertEquals(0.25F, calculator.update(DAY_ONE, 1_250L), 0F);
	}

	@Test
	public void returnsNullForMissingTotal() {
		final var calculator = new EnergyProductionTodayCalculator();

		assertNull(calculator.update(DAY_ONE, null));
	}

	@Test
	public void resetsBaselineOnDayRollover() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);

		assertEquals(0F, calculator.update(DAY_TWO, 2_000L), 0F);
		assertEquals(0.5F, calculator.update(DAY_TWO, 2_500L), 0F);
	}

	@Test
	public void resetsBaselineWhenTotalDecreases() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);
		calculator.update(DAY_ONE, 1_500L);

		assertEquals(0F, calculator.update(DAY_ONE, 100L), 0F);
		assertEquals(0.1F, calculator.update(DAY_ONE, 200L), 0F);
	}

	@Test
	public void restoresValidHistoricBaseline() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);
		calculator.update(DAY_ONE, 1_500L);

		assertEquals(1F, calculator.restore(DAY_ONE, 500L), 0F);
	}

	@Test
	public void rejectsRestoreBeforeInitialization() {
		final var calculator = new EnergyProductionTodayCalculator();

		assertNull(calculator.restore(DAY_ONE, 500L));
	}

	@Test
	public void rejectsRestoreWithNullInputs() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);
		calculator.update(DAY_ONE, 1_500L);

		assertEquals(0.5F, calculator.restore(null, 500L), 0F);
		assertEquals(0.5F, calculator.restore(DAY_ONE, null), 0F);
	}

	@Test
	public void rejectsRestoreForAnotherDay() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);
		calculator.update(DAY_ONE, 1_500L);

		assertEquals(0.5F, calculator.restore(DAY_TWO, 500L), 0F);
	}

	@Test
	public void rejectsRestoreAboveLatestTotal() {
		final var calculator = new EnergyProductionTodayCalculator();
		calculator.update(DAY_ONE, 1_000L);
		calculator.update(DAY_ONE, 1_500L);

		assertEquals(0.5F, calculator.restore(DAY_ONE, 1_600L), 0F);
	}

	@Test
	public void synchronizesStateTransitions() throws NoSuchMethodException {
		assertTrue(Modifier.isSynchronized(
				EnergyProductionTodayCalculator.class.getDeclaredMethod("update", LocalDate.class, Long.class).getModifiers()));
		assertTrue(Modifier.isSynchronized(
				EnergyProductionTodayCalculator.class.getDeclaredMethod("restore", LocalDate.class, Long.class).getModifiers()));
	}
}
