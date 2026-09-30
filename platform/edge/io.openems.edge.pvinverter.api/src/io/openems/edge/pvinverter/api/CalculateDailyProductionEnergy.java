package io.openems.edge.pvinverter.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Calculates daily PV production with deterministic source priority.
 *
 * <p>
 * Device daily energy is preferred over a total-counter delta, which is
 * preferred over integrating positive active power. Use {@link #snapshot()} and
 * the restoring constructor to persist calculation state across restarts.
 */
public final class CalculateDailyProductionEnergy {

	public enum Source {
		DEVICE_TODAY, TOTAL_COUNTER, ACTIVE_POWER, UNAVAILABLE
	}

	public static record UpdateResult(Long energyWh, Source source, boolean invalidData) {
	}

	/** Serializable state required to continue an in-progress day after restart. */
	public static record Snapshot(//
			LocalDate date, //
			Long lastDeviceTodayEnergyWh, //
			Long totalBaselineWh, //
			boolean totalBaselineTrusted, //
			Long lastTotalEnergyWh, //
			Instant lastTotalTimestamp, //
			Instant lastTimestamp, //
			Integer lastActivePowerW, //
			long powerEnergyWh, //
			long continuousWattMillis, //
			boolean powerEnergyTrusted) {
	}

	private final ZoneId zoneId;
	private final Duration maximumIntegrationGap;

	private LocalDate date;
	private Long lastDeviceTodayEnergyWh;
	private Long totalBaselineWh;
	private boolean totalBaselineTrusted;
	private Long lastTotalEnergyWh;
	private Instant lastTotalTimestamp;
	private Instant lastTimestamp;
	private Integer lastActivePowerW;
	private long powerEnergyWh;
	private long continuousWattMillis;
	private boolean powerEnergyTrusted;

	public CalculateDailyProductionEnergy(ZoneId zoneId, Duration maximumIntegrationGap) {
		this(zoneId, maximumIntegrationGap, null);
	}

	public CalculateDailyProductionEnergy(ZoneId zoneId, Duration maximumIntegrationGap, Snapshot snapshot) {
		this.zoneId = Objects.requireNonNull(zoneId);
		this.maximumIntegrationGap = Objects.requireNonNull(maximumIntegrationGap);
		if (maximumIntegrationGap.isNegative() || maximumIntegrationGap.isZero()) {
			throw new IllegalArgumentException("Maximum integration gap must be positive");
		}
		if (snapshot != null) {
			this.restore(snapshot);
		}
	}

	/**
	 * Updates the calculation.
	 *
	 * @param timestamp           timestamp of this sample
	 * @param deviceTodayEnergyWh optional device daily counter in Wh
	 * @param totalEnergyWh       optional lifetime production counter in Wh
	 * @param activePowerW        optional active production power in W
	 * @return daily energy, selected source and invalid-data flag
	 */
	public UpdateResult update(Instant timestamp, Long deviceTodayEnergyWh, Long totalEnergyWh,
			Integer activePowerW) {
		Objects.requireNonNull(timestamp);
		var currentDate = timestamp.atZone(this.zoneId).toLocalDate();
		if (this.lastTimestamp != null && timestamp.isBefore(this.lastTimestamp)) {
			this.resetUntrusted(currentDate, timestamp, totalEnergyWh, activePowerW);
			return new UpdateResult(null, Source.UNAVAILABLE, true);
		}

		var invalidData = false;
		var firstSample = this.date == null;
		var dayChanged = !firstSample && !this.date.equals(currentDate);
		if (firstSample) {
			this.date = currentDate;
			this.totalBaselineWh = validEnergy(totalEnergyWh) ? totalEnergyWh : null;
			this.totalBaselineTrusted = false;
			this.powerEnergyTrusted = false;
		} else if (dayChanged) {
			this.startNewDay(currentDate, timestamp);
		}

		Long validDeviceToday = null;
		if (deviceTodayEnergyWh != null) {
			if (!validEnergy(deviceTodayEnergyWh)
					|| this.lastDeviceTodayEnergyWh != null && deviceTodayEnergyWh < this.lastDeviceTodayEnergyWh) {
				invalidData = true;
			} else {
				validDeviceToday = deviceTodayEnergyWh;
				this.lastDeviceTodayEnergyWh = deviceTodayEnergyWh;
			}
		}

		Long validTotal = null;
		if (totalEnergyWh != null) {
			if (!validEnergy(totalEnergyWh)
					|| this.lastTotalEnergyWh != null && totalEnergyWh < this.lastTotalEnergyWh) {
				invalidData = true;
				this.totalBaselineTrusted = false;
			} else {
				validTotal = totalEnergyWh;
				this.lastTotalEnergyWh = totalEnergyWh;
				this.lastTotalTimestamp = timestamp;
			}
		}

		if (!dayChanged) {
			this.integratePower(timestamp);
		}

		UpdateResult result;
		if (validDeviceToday != null) {
			result = new UpdateResult(validDeviceToday, Source.DEVICE_TODAY, invalidData);
		} else if (validTotal != null && this.totalBaselineTrusted && this.totalBaselineWh != null) {
			var dailyEnergy = validTotal - this.totalBaselineWh;
			if (dailyEnergy < 0) {
				this.totalBaselineTrusted = false;
				result = new UpdateResult(null, Source.UNAVAILABLE, true);
			} else {
				result = new UpdateResult(dailyEnergy, Source.TOTAL_COUNTER, invalidData);
			}
		} else if (activePowerW != null && this.powerEnergyTrusted) {
			result = new UpdateResult(this.powerEnergyWh, Source.ACTIVE_POWER, invalidData);
		} else {
			result = new UpdateResult(null, Source.UNAVAILABLE, invalidData);
		}

		this.lastTimestamp = timestamp;
		this.lastActivePowerW = activePowerW;
		return result;
	}

	public Snapshot snapshot() {
		return new Snapshot(this.date, this.lastDeviceTodayEnergyWh, this.totalBaselineWh,
				this.totalBaselineTrusted, this.lastTotalEnergyWh, this.lastTotalTimestamp, this.lastTimestamp,
				this.lastActivePowerW, this.powerEnergyWh, this.continuousWattMillis, this.powerEnergyTrusted);
	}

	private void restore(Snapshot snapshot) {
		if (snapshot.powerEnergyWh() < 0 || snapshot.continuousWattMillis() < 0
				|| snapshot.continuousWattMillis() >= 3_600_000L) {
			throw new IllegalArgumentException("Invalid daily energy snapshot");
		}
		this.date = snapshot.date();
		this.lastDeviceTodayEnergyWh = snapshot.lastDeviceTodayEnergyWh();
		this.totalBaselineWh = snapshot.totalBaselineWh();
		this.totalBaselineTrusted = snapshot.totalBaselineTrusted();
		this.lastTotalEnergyWh = snapshot.lastTotalEnergyWh();
		this.lastTotalTimestamp = snapshot.lastTotalTimestamp();
		this.lastTimestamp = snapshot.lastTimestamp();
		this.lastActivePowerW = snapshot.lastActivePowerW();
		this.powerEnergyWh = snapshot.powerEnergyWh();
		this.continuousWattMillis = snapshot.continuousWattMillis();
		this.powerEnergyTrusted = snapshot.powerEnergyTrusted();
	}

	private void startNewDay(LocalDate currentDate, Instant timestamp) {
		var previousDate = this.date;
		var gapIsTrusted = this.lastTimestamp != null && previousDate.plusDays(1).equals(currentDate)
				&& !Duration.between(this.lastTimestamp, timestamp).isNegative()
				&& Duration.between(this.lastTimestamp, timestamp).compareTo(this.maximumIntegrationGap) <= 0;

		this.date = currentDate;
		this.lastDeviceTodayEnergyWh = null;
		this.totalBaselineWh = gapIsTrusted && this.lastTotalTimestamp != null
				&& this.lastTotalTimestamp.equals(this.lastTimestamp) ? this.lastTotalEnergyWh : null;
		this.totalBaselineTrusted = this.totalBaselineWh != null;
		this.powerEnergyWh = 0L;
		this.continuousWattMillis = 0L;
		this.powerEnergyTrusted = gapIsTrusted && this.lastActivePowerW != null;
		if (this.powerEnergyTrusted) {
			var startOfDay = currentDate.atStartOfDay(this.zoneId).toInstant();
			var duration = Duration.between(startOfDay, timestamp);
			if (!duration.isNegative()) {
				this.addPower(this.lastActivePowerW, duration.toMillis());
			}
		}
	}

	private void integratePower(Instant timestamp) {
		if (this.lastTimestamp == null || this.lastActivePowerW == null || !this.powerEnergyTrusted) {
			return;
		}
		var duration = Duration.between(this.lastTimestamp, timestamp);
		if (duration.isNegative() || duration.compareTo(this.maximumIntegrationGap) > 0) {
			this.powerEnergyTrusted = false;
			return;
		}
		this.addPower(this.lastActivePowerW, duration.toMillis());
	}

	private void addPower(int activePowerW, long durationMillis) {
		var positivePower = Math.max(0, activePowerW);
		try {
			this.continuousWattMillis = Math.addExact(this.continuousWattMillis,
					Math.multiplyExact((long) positivePower, durationMillis));
			this.powerEnergyWh = Math.addExact(this.powerEnergyWh, this.continuousWattMillis / 3_600_000L);
			this.continuousWattMillis %= 3_600_000L;
		} catch (ArithmeticException e) {
			this.powerEnergyTrusted = false;
		}
	}

	private void resetUntrusted(LocalDate currentDate, Instant timestamp, Long totalEnergyWh, Integer activePowerW) {
		this.date = currentDate;
		this.lastDeviceTodayEnergyWh = null;
		this.totalBaselineWh = validEnergy(totalEnergyWh) ? totalEnergyWh : null;
		this.totalBaselineTrusted = false;
		this.lastTotalEnergyWh = this.totalBaselineWh;
		this.lastTotalTimestamp = this.totalBaselineWh == null ? null : timestamp;
		this.lastTimestamp = timestamp;
		this.lastActivePowerW = activePowerW;
		this.powerEnergyWh = 0L;
		this.continuousWattMillis = 0L;
		this.powerEnergyTrusted = false;
	}

	private static boolean validEnergy(Long energyWh) {
		return energyWh != null && energyWh >= 0;
	}
}
