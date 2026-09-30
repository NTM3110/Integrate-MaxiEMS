package io.openems.edge.logger.api;

import java.time.LocalDate;

public class EnergyProductionTodayCalculator {

	private LocalDate day;
	private Long baselineTotalWh;
	private Long latestTotalWh;

	public synchronized Float update(final LocalDate day, final Long totalWh) {
		if (day == null || totalWh == null) {
			return null;
		}
		if (!day.equals(this.day)) {
			this.day = day;
			this.baselineTotalWh = totalWh;
			this.latestTotalWh = totalWh;
			return 0F;
		}

		this.latestTotalWh = totalWh;
		if (totalWh < this.baselineTotalWh) {
			this.baselineTotalWh = totalWh;
			return 0F;
		}
		return this.currentResult();
	}

	public synchronized Float restore(final LocalDate day, final Long firstTotalWh) {
		if (day != null && day.equals(this.day) && firstTotalWh != null && this.latestTotalWh != null
				&& firstTotalWh <= this.latestTotalWh) {
			this.baselineTotalWh = firstTotalWh;
		}
		return this.currentResult();
	}

	private Float currentResult() {
		if (this.baselineTotalWh == null || this.latestTotalWh == null) {
			return null;
		}
		return (this.latestTotalWh - this.baselineTotalWh) / 1_000F;
	}
}
