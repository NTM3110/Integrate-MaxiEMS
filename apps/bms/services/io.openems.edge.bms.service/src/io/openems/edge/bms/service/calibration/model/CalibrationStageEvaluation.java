package io.openems.edge.bms.service.calibration.model;

import java.util.List;

/** Pure stage result. It recommends but never performs a stage transition. */
public record CalibrationStageEvaluation<C>(C checkpoint, List<CalibrationBlocker> blockers,
		List<Milestone> milestones, List<PersistenceReason> persistenceReasons, Recommendation recommendation) {

	public CalibrationStageEvaluation {
		blockers = List.copyOf(blockers);
		milestones = List.copyOf(milestones);
		persistenceReasons = List.copyOf(persistenceReasons);
	}

	public enum Recommendation {
		CONTINUE,
		BLOCKED,
		READY_TO_COMPLETE
	}

	public enum PersistenceReason {
		PERIODIC_SAMPLE, FINAL_SNAPSHOT,
		QUALITY_CHANGE,
		CURRENT_PEAK,
		DOD_MILESTONE_5_PERCENT,
		TARGET_DOD,
		CUTOFF,
		SAFETY_FLOOR
	}

	public record Milestone(MilestoneType type, double value) {
	}

	public enum MilestoneType {
		DOD_PERCENT,
		CUTOFF,
		TARGET_DOD
	}
}
