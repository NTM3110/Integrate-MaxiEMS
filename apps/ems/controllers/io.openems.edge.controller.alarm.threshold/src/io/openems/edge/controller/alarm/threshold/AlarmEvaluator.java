package io.openems.edge.controller.alarm.threshold;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

final class AlarmEvaluator {
	private Integer confirmedLevel;
	private Integer pendingLevel;
	private Instant pendingSince;
	private Instant activeSince;

	public Optional<LevelChange> evaluate(int level, Instant now, Duration debounce) {
		if (this.confirmedLevel == null && level == 0) {
			this.confirmedLevel = 0;
			this.clearPending();
			return Optional.empty();
		}
		if (this.confirmedLevel != null && this.confirmedLevel == level) {
			this.clearPending();
			return Optional.empty();
		}
		if (this.pendingLevel == null || this.pendingLevel != level) {
			this.pendingLevel = level;
			this.pendingSince = now;
			return Optional.empty();
		}
		if (Duration.between(this.pendingSince, now).compareTo(debounce) < 0) {
			return Optional.empty();
		}
		var oldLevel = this.confirmedLevel == null ? 0 : this.confirmedLevel;
		this.confirmedLevel = level;
		Duration alarmDuration = null;
		if (oldLevel == 0 && level > 0) {
			this.activeSince = now;
		} else if (oldLevel > 0 && level == 0) {
			if (this.activeSince != null) {
				alarmDuration = Duration.between(this.activeSince, now);
			}
		}
		var alarmStartedAt = this.activeSince;
		if (level == 0) {
			this.activeSince = null;
		}
		this.clearPending();
		return Optional.of(new LevelChange(oldLevel, level, alarmStartedAt, alarmDuration));
	}

	public int confirmedLevel() {
		return this.confirmedLevel == null ? 0 : this.confirmedLevel;
	}

	public boolean isPending() {
		return this.pendingLevel != null;
	}

	private void clearPending() {
		this.pendingLevel = null;
		this.pendingSince = null;
	}

	public record LevelChange(int oldLevel, int newLevel, Instant alarmStartedAt, Duration alarmDuration) {
	}
}
