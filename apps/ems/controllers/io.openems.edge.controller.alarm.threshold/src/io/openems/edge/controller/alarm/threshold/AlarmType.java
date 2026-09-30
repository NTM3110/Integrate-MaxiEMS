package io.openems.edge.controller.alarm.threshold;

enum AlarmType {
	TEMPERATURE("nhiệt độ", new String[] { "normal", "warning", "alarm" }),
	PD("PD", new String[] { "normal", "low activity", "medium activity", "high activity" });

	private final String displayName;
	private final String[] levels;

	private AlarmType(String displayName, String[] levels) {
		this.displayName = displayName;
		this.levels = levels;
	}

	public boolean isValid(int level) {
		return level >= 0 && level < this.levels.length;
	}

	public String displayName() {
		return this.displayName;
	}

	public String levelName(int level) {
		return this.levels[level];
	}
}
