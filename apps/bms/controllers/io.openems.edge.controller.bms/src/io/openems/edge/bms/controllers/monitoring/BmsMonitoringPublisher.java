package io.openems.edge.bms.controllers.monitoring;

import java.util.Objects;
import java.util.UUID;

import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

/** Coalesces scheduler ticks while retaining changes independent of acquisition. */
final class BmsMonitoringPublisher {

	private final BmsOperatingModeResolver modeResolver = new BmsOperatingModeResolver();
	private UUID generation = UUID.randomUUID();
	private long revision;
	private BmsMonitoringSnapshot latest;
	private InitialBaselineReferenceDataset lastBaseline;
	private BmsMonitoringPolicy lastPolicy;

	BmsMonitoringSnapshot evaluate(String stringKey, BmsCalculationSnapshot source,
			InitialBaselineReferenceDataset baseline, ActiveCalibrationContext context,
			BmsMonitoringPolicy policy, BmsCurrentDirectionMapping direction, long nowMillis) {
		if (context == null) context = ActiveCalibrationContext.none();
		boolean sourceUnavailable = source == null;
		if (sourceUnavailable) {
			source = this.latest == null ? null : this.latest.calculationSnapshot();
			if (source == null) return null;
		}
		boolean stale = sourceUnavailable || source.currentMeasuredAt() == null
				|| nowMillis - source.capturedAt() > policy.snapshotFreshnessSeconds() * 1_000L
				|| nowMillis - source.currentMeasuredAt() > policy.snapshotFreshnessSeconds() * 1_000L;
		var previous = this.latest;
		boolean contextChanged = previous == null || !Objects.equals(previous.activeCalibration(), context);
		boolean baselineChanged = !Objects.equals(this.lastBaseline, baseline);
		boolean policyChanged = !Objects.equals(this.lastPolicy, policy);
		if (previous != null && Objects.equals(previous.calculationSnapshot(), source)
				&& previous.stale() == stale && !contextChanged && !baselineChanged && !policyChanged
				&& previous.currentDirectionMapping() == direction && previous.stringKey().equals(stringKey)) {
			return previous;
		}
		if (contextChanged || baselineChanged || policyChanged
				|| previous != null && previous.currentDirectionMapping() != direction) this.modeResolver.reset();
		double current = source.stringCurrent() == null ? Double.NaN : direction.toDomainCurrent(source.stringCurrent());
		Double floatVoltage = baseline == null ? null : baseline.recoveryFloatSnapshot().stringVoltageV();
		if (sourceUnavailable) this.modeResolver.reset();
		var mode = sourceUnavailable ? new BmsMonitoringSnapshot.ModeEvidence(
				BmsMonitoringSnapshot.OperatingMode.UNKNOWN, BmsMonitoringSnapshot.ModeSource.NONE,
				java.time.Instant.ofEpochMilli(nowMillis), source.generation(), source.sequence(),
				BmsMonitoringSnapshot.ModeQuality.STALE, "Calculator source is unavailable")
				: this.modeResolver.resolve(source, current, context,
				floatVoltage == null ? Double.NaN : floatVoltage, policy, nowMillis);
		var candidate = BmsMonitoringEvaluator.evaluate(stringKey, source, baseline, context, policy, direction, mode,
				nowMillis, sourceUnavailable).withPublication(this.generation, ++this.revision);
		this.lastBaseline = baseline;
		this.lastPolicy = policy;
		this.latest = candidate;
		return candidate;
	}

	void reset() {
		this.generation = UUID.randomUUID();
		this.revision = 0;
		this.latest = null;
		this.lastBaseline = null;
		this.lastPolicy = null;
		this.modeResolver.reset();
	}
}
