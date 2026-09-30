package io.openems.edge.bridge.dlms.api.task;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.common.taskmanager.Priority;

public class ReadProfileTask extends AbstractDlmsTask {

	private static final ZoneId DEVICE_ZONE = ZoneId.systemDefault();

	private final Logger log = LoggerFactory.getLogger(ReadProfileTask.class);
	private final String meterId;
	private final String profileObis;
	private final LocalTime readTime;
	private final Supplier<String> csvDataDirSupplier;
	private LocalDate lastSuccessfulDate = LocalDate.MIN;
	private LocalDate nextRunDate;
	private LocalDateTime nextRetryAfter = LocalDateTime.MIN;

	public ReadProfileTask(Priority priority, String meterId, String profileObis, LocalTime readTime, String csvDataDir) {
		this(priority, meterId, profileObis, readTime, () -> csvDataDir);
	}

	public ReadProfileTask(Priority priority, String meterId, String profileObis, LocalTime readTime,
			Supplier<String> csvDataDirSupplier) {
		super(priority);
		this.meterId = meterId;
		this.profileObis = profileObis;
		this.readTime = readTime;
		this.csvDataDirSupplier = csvDataDirSupplier;
		var today = LocalDate.now(DEVICE_ZONE);
		this.nextRunDate = LocalTime.now(DEVICE_ZONE).isBefore(readTime) ? today : today.plusDays(1);
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		if (!this.isDue()) {
			return ExecuteState.OK;
		}
		var targetDate = this.nextRunDate.minusDays(1);
		try {
			var csvStore = new DlmsProfileCsvStore(this.csvDataDirSupplier.get());
			var csvFile = csvStore.getFile(targetDate, this.meterId).toAbsolutePath().normalize();
			if (csvStore.isComplete(targetDate, this.meterId, 0, 47)) {
				this.log.info("Skipping DLMS profile [{}] for meter [{}] date [{}]; CSV is already complete at [{}]",
						this.profileObis, this.meterId, targetDate, csvFile);
				
				this.lastSuccessfulDate = targetDate;
				this.nextRunDate = this.nextRunDate.plusDays(1);
				return ExecuteState.OK;
			}
			var start = Date
					.from(LocalDateTime.of(targetDate, LocalTime.MIN).minusMinutes(30).atZone(DEVICE_ZONE).toInstant());
			var end = Date.from(LocalDateTime.of(targetDate.plusDays(1), LocalTime.MIN).atZone(DEVICE_ZONE).toInstant());
			this.log.info("Reading DLMS profile [{}] for meter [{}] date [{}]", this.profileObis, this.meterId,
					targetDate);
			var rows = bridge.readProfileForTarget(this.getParent(), this.profileObis, start, end);
			if (rows == null || rows.length == 0) {
				throw new IllegalStateException("Meter returned no DLMS profile rows for date [" + targetDate + "]");
			}
			var csvRows = csvStore.mergeAndSave(targetDate, this.meterId, rows);
			this.log.info("Saved DLMS profile CSV for meter [{}] date [{}] to [{}]; raw rows [{}], CSV rows [{}]",
					this.meterId, targetDate, csvFile, rows.length, csvRows.size());
			this.lastSuccessfulDate = targetDate;
			this.nextRunDate = this.nextRunDate.plusDays(1);
			return ExecuteState.OK;
		} catch (Exception e) {
			this.nextRetryAfter = LocalDateTime.now(DEVICE_ZONE).plusMinutes(10);
			this.log.error("[ReadProfileTask] Read failed for meter [{}]: {}", this.meterId, e.getMessage(), e);
			return new ExecuteState.Error(e);
		}
	}

	public boolean isDue() {
		var nowDate = LocalDate.now(DEVICE_ZONE);
		var nowTime = LocalTime.now(DEVICE_ZONE);
		if (LocalDateTime.now(DEVICE_ZONE).isBefore(this.nextRetryAfter)) {
			return false;
		}
		if (nowDate.isBefore(this.nextRunDate)
				|| (nowDate.equals(this.nextRunDate) && nowTime.isBefore(this.readTime))) {
			return false;
		}
		return true;
	}
}
