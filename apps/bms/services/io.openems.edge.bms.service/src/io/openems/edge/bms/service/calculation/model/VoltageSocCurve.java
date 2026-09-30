package io.openems.edge.bms.service.calculation.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/** Immutable, monotonic voltage-to-SoC lookup curve. Values are percentages. */
public final class VoltageSocCurve {

	public record Point(double voltage, double socPercent) {}

	private final List<Point> points;

	public VoltageSocCurve(Collection<Point> source) {
		var sorted = source.stream()
				.filter(point -> Double.isFinite(point.voltage()) && Double.isFinite(point.socPercent()))
				.sorted(Comparator.comparingDouble(Point::voltage))
				.toList();
		this.points = List.copyOf(new ArrayList<>(sorted));
	}

	public boolean isUsable() {
		return this.points.size() >= 2;
	}

	public List<Point> points() {
		return this.points;
	}

	/**
	 * Looks up a voltage using linear interpolation. Values outside the calibrated
	 * range are clamped to the nearest endpoint instead of extrapolated.
	 */
	public OptionalDouble estimateSocPercent(double voltage) {
		if (!Double.isFinite(voltage) || !this.isUsable()) return OptionalDouble.empty();
		var first = this.points.get(0);
		var last = this.points.get(this.points.size() - 1);
		if (voltage <= first.voltage()) return OptionalDouble.of(first.socPercent());
		if (voltage >= last.voltage()) return OptionalDouble.of(last.socPercent());
		for (var index = 1; index < this.points.size(); index++) {
			var high = this.points.get(index);
			if (voltage > high.voltage()) continue;
			var low = this.points.get(index - 1);
			var span = high.voltage() - low.voltage();
			if (span <= 0) return OptionalDouble.of(low.socPercent());
			var ratio = (voltage - low.voltage()) / span;
			return OptionalDouble.of(low.socPercent() + ratio * (high.socPercent() - low.socPercent()));
		}
		return OptionalDouble.of(last.socPercent());
	}
}
