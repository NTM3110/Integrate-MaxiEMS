package io.openems.edge.bms.controllers.monitoring;

import java.time.Instant;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

/** Stateful resolver that prevents one telemetry sample from declaring a mode. */
final class BmsOperatingModeResolver {

	private OperatingMode resolved = OperatingMode.UNKNOWN;
	private OperatingMode candidate = OperatingMode.UNKNOWN;
	private long candidateSince;
	private UUID lastGeneration;
	private long lastSequence = -1;

	public synchronized ModeEvidence resolve(BmsCalculationSnapshot snapshot, double domainCurrentA,
			ActiveCalibrationContext context, double referenceFloatVoltageV, BmsMonitoringPolicy policy,
			long nowMillis) {
		var now = Instant.ofEpochMilli(nowMillis);
		if (isStale(snapshot, policy, nowMillis)) {
			this.reset();
			return new ModeEvidence(OperatingMode.UNKNOWN, ModeSource.NONE, now, snapshot.generation(),
					snapshot.sequence(), ModeQuality.STALE, "Current or snapshot evidence is stale");
		}

		var processMode = processMode(context == null ? null : context.processType());
		if (processMode != null && processMode != OperatingMode.FLOAT) {
			if (!directionConsistent(processMode, domainCurrentA, policy.currentDeadbandA())) {
				this.reset();
				return candidate(snapshot, now, ModeSource.ACTIVE_CALIBRATION,
						"Active process and current direction are inconsistent");
			}
			this.resolved = processMode;
			this.candidate = processMode;
			this.candidateSince = snapshot.capturedAt();
			this.remember(snapshot);
			return evidence(snapshot, now, processMode, ModeSource.ACTIVE_CALIBRATION, ModeQuality.VALID,
					"Resolved from active calibration process and fresh current");
		}

		OperatingMode inferred = infer(snapshot, domainCurrentA, referenceFloatVoltageV, processMode, policy);
		ModeSource source = processMode == OperatingMode.FLOAT ? ModeSource.ACTIVE_CALIBRATION
				: ModeSource.TELEMETRY_INFERENCE;
		if (!isNewSnapshot(snapshot)) {
			return this.pendingOrResolved(snapshot, now, inferred, source, policy);
		}
		this.remember(snapshot);
		if (inferred != this.candidate) {
			this.candidate = inferred;
			this.candidateSince = snapshot.capturedAt();
		}
		return this.pendingOrResolved(snapshot, now, inferred, source, policy);
	}

	public synchronized void reset() {
		this.resolved = OperatingMode.UNKNOWN;
		this.candidate = OperatingMode.UNKNOWN;
		this.candidateSince = 0;
		this.lastGeneration = null;
		this.lastSequence = -1;
	}

	private ModeEvidence pendingOrResolved(BmsCalculationSnapshot snapshot, Instant now, OperatingMode inferred,
			ModeSource source, BmsMonitoringPolicy policy) {
		long stableMillis = Math.max(0, snapshot.capturedAt() - this.candidateSince);
		if (inferred != OperatingMode.UNKNOWN && stableMillis >= policy.modeStableSeconds() * 1_000L) {
			this.resolved = inferred;
			return evidence(snapshot, now, inferred, source, ModeQuality.VALID,
					"Mode candidate passed the stable window");
		}
		if (this.resolved != OperatingMode.UNKNOWN && inferred != OperatingMode.UNKNOWN) {
			return evidence(snapshot, now, this.resolved, source, ModeQuality.VALID,
					"Holding resolved mode during hysteresis window");
		}
		return candidate(snapshot, now, source, "Mode candidate has not passed the stable window");
	}

	private static OperatingMode infer(BmsCalculationSnapshot snapshot, double current, double floatVoltage,
			OperatingMode processMode, BmsMonitoringPolicy policy) {
		double deadband = policy.currentDeadbandA();
		if (current > deadband) return OperatingMode.DISCHARGING;
		boolean nearFloat = finite(snapshot.stringVoltage()) && Double.isFinite(floatVoltage)
				&& Math.abs(snapshot.stringVoltage() - floatVoltage) <= policy.floatVoltageToleranceV();
		if (processMode == OperatingMode.FLOAT) {
			return current <= deadband && nearFloat ? OperatingMode.FLOAT : OperatingMode.UNKNOWN;
		}
		if (current < -deadband) return nearFloat ? OperatingMode.FLOAT : OperatingMode.CHARGING;
		if (!Double.isFinite(floatVoltage)) return OperatingMode.UNKNOWN;
		return nearFloat ? OperatingMode.FLOAT : OperatingMode.IDLE;
	}

	private static OperatingMode processMode(String processType) {
		if (processType == null) return null;
		return switch (processType) {
		case "BOOST_CHARGE_TO_VBOOST", "RECOVERY_CHARGE_TO_VBOOST" -> OperatingMode.CHARGING;
		case "FLOAT_SETTLEMENT_TO_VFLOAT", "RECOVERY_FLOAT_STABILIZATION" -> OperatingMode.FLOAT;
		case "CONTROLLED_DISCHARGE" -> OperatingMode.DISCHARGING;
		case "VOC_BASELINE_CAPTURE", "COOL_DOWN_TO_AMBIENT" -> OperatingMode.IDLE;
		default -> null;
		};
	}

	private static boolean directionConsistent(OperatingMode mode, double current, double deadband) {
		return switch (mode) {
		case CHARGING -> current < -deadband;
		case DISCHARGING -> current > deadband;
		case IDLE -> Math.abs(current) <= deadband;
		default -> true;
		};
	}

	private static boolean isStale(BmsCalculationSnapshot snapshot, BmsMonitoringPolicy policy, long nowMillis) {
		long freshness = policy.snapshotFreshnessSeconds() * 1_000L;
		return snapshot.currentMeasuredAt() == null || nowMillis - snapshot.capturedAt() > freshness
				|| nowMillis - snapshot.currentMeasuredAt() > freshness || !finite(snapshot.stringCurrent())
				|| snapshot.currentWarning() != null
				|| snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.MISSING
				|| snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED
				|| snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.TIMESTAMP_MISSING;
	}

	private boolean isNewSnapshot(BmsCalculationSnapshot snapshot) {
		return !snapshot.generation().equals(this.lastGeneration) || snapshot.sequence() != this.lastSequence;
	}

	private void remember(BmsCalculationSnapshot snapshot) {
		this.lastGeneration = snapshot.generation();
		this.lastSequence = snapshot.sequence();
	}

	private static ModeEvidence candidate(BmsCalculationSnapshot snapshot, Instant now, ModeSource source, String reason) {
		return evidence(snapshot, now, OperatingMode.UNKNOWN, source, ModeQuality.CANDIDATE, reason);
	}

	private static ModeEvidence evidence(BmsCalculationSnapshot snapshot, Instant now, OperatingMode mode,
			ModeSource source, ModeQuality quality, String reason) {
		return new ModeEvidence(mode, source, now, snapshot.generation(), snapshot.sequence(), quality, reason);
	}

	private static boolean finite(Number value) {
		return value != null && Double.isFinite(value.doubleValue());
	}
}
