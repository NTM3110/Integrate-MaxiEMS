package io.openems.edge.bridge.edmi.csvinjector;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.slf4j.Logger;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import io.openems.edge.bridge.edmi.EdmiBridge;

public final class CsvProfileInjectionService {

	private static final DateTimeFormatter CSV_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private CsvProfileInjectionService() {
	}

	public static InjectionResult inject(EdmiBridge bridge, Options options, Logger log) throws Exception {
		var rowsByTimestamp = readCsvFile(options.csvPath(), options.timestampOffsetMinutes(), log);
		if (rowsByTimestamp.isEmpty()) {
			return new InjectionResult(0, 0, null, null);
		}

		var sortedRows = new TreeMap<>(rowsByTimestamp);
		var intervalCount = 0;
		var rowCount = 0;
		Instant firstTimestamp = null;
		Instant lastTimestamp = null;

		for (var entry : sortedRows.entrySet()) {
			if (options.stopSignal().stopRequested()) {
				break;
			}

			var timestamp = entry.getKey();
			var meterRows = entry.getValue();
			if (firstTimestamp == null) {
				firstTimestamp = timestamp;
			}
			lastTimestamp = timestamp;

			for (var row : meterRows.values()) {
				bridge.registerEnergyMeter(row.meterId(), row.role(), row.sourceType());
			}

			for (var row : meterRows.values()) {
				if (log != null && log.isInfoEnabled()) {
					log.info("Injecting EDMI profile row: meter={}, role={}, sourceType={}, timestamp={}, impWh={}, expWh={}",
							row.meterId(), row.role(), row.sourceType(), timestamp, row.impWh(), row.expWh());
				}
				bridge.writeToInfluxSync(Point.measurement("edmi_profile")
						.addTag("meter_id", row.meterId())
						.addTag("meter_role", row.role())
						.addTag("source_type", row.sourceType())
						.time(timestamp, WritePrecision.MS)
						.addField("record_status", row.recordStatus())
						.addField("total_energy_tot_imp_wh", row.impWh())
						.addField("total_energy_tot_exp_wh", row.expWh())
						.addField("total_energy_tot_imp_va", row.impVa())
						.addField("total_energy_tot_exp_va", row.expVa()));
				rowCount++;
			}

			if (options.queryDelayMs() > 0) {
				Thread.sleep(options.queryDelayMs());
			}

			options.profileTimestampCallback().accept(timestamp);

			intervalCount++;
			var remainingDelay = options.injectionDelayMs() - options.queryDelayMs();
			if (remainingDelay > 0) {
				Thread.sleep(remainingDelay);
			}
		}

		return new InjectionResult(intervalCount, rowCount, firstTimestamp, lastTimestamp);
	}

	private record CsvRowWithTimestamp(Instant timestamp, CsvRow row) {
	}

	private static CsvRowWithTimestamp parseCsvRow(String[] parts, long timestampOffsetMinutes) {
		if (parts.length == 7) {
			// Reformatted profile format:
			// meter_id,time_stamp,record_status,total_energy_tot_imp_wh,total_energy_tot_exp_wh,
			// total_energy_tot_imp_va,total_energy_tot_exp_va
			var meterId = parts[0].trim();
			var localDateTime = LocalDateTime.parse(parts[1].trim(), CSV_TIMESTAMP_FORMATTER)
					.plusMinutes(timestampOffsetMinutes);
			var timestamp = localDateTime.toInstant(java.time.ZoneOffset.UTC);
			var row = new CsvRow(meterId, roleForMeter(meterId), sourceTypeForMeter(meterId),
					Integer.parseInt(parts[2].trim()), Double.parseDouble(parts[3].trim()),
					Double.parseDouble(parts[4].trim()), Double.parseDouble(parts[5].trim()),
					Double.parseDouble(parts[6].trim()));
			return new CsvRowWithTimestamp(timestamp, row);
		}

		if (parts.length == 4) {
			// Raw demo format: ts,meter_serial,import_kwh,export_kwh
			// The timestamp and meter-id columns may be swapped; detect by parsing.
			var timestampCandidate = parts[0].trim();
			var meterIdCandidate = parts[1].trim();
			LocalDateTime localDateTime;
			try {
				localDateTime = LocalDateTime.parse(timestampCandidate, CSV_TIMESTAMP_FORMATTER);
			} catch (Exception e) {
				timestampCandidate = parts[1].trim();
				meterIdCandidate = parts[0].trim();
				localDateTime = LocalDateTime.parse(timestampCandidate, CSV_TIMESTAMP_FORMATTER);
			}
			var timestamp = localDateTime.plusMinutes(timestampOffsetMinutes).toInstant(java.time.ZoneOffset.UTC);
			var row = new CsvRow(meterIdCandidate, roleForMeter(meterIdCandidate), sourceTypeForMeter(meterIdCandidate),
					0, Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()), 0.0, 0.0);
			return new CsvRowWithTimestamp(timestamp, row);
		}

		return null;
	}

	static TreeMap<Instant, Map<String, CsvRow>> readCsvFile(Path csvPath, long timestampOffsetMinutes, Logger log)
			throws Exception {
		var result = new TreeMap<Instant, Map<String, CsvRow>>();
		try (var reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
			var header = reader.readLine();
			if (header == null) {
				throw new IllegalStateException("CSV file is empty: " + csvPath);
			}

			String line;
			var lineCount = 0;
			while ((line = reader.readLine()) != null) {
				lineCount++;
				var parts = line.split(",");

				try {
					var parsed = parseCsvRow(parts, timestampOffsetMinutes);
					if (parsed == null) {
						if (log != null) {
							log.warn("Skipping invalid CSV line {}: {}", lineCount, line);
						}
						continue;
					}

					result.computeIfAbsent(parsed.timestamp(), ignored -> new HashMap<>()).put(parsed.row().meterId(),
							parsed.row());
				} catch (Exception e) {
					if (log != null) {
						log.warn("Error parsing CSV line {}: {} - {}", lineCount, line, e.getMessage());
					}
				}
			}
		}
		return result;
	}

	static String roleForMeter(String meterId) {
		if (meterId.startsWith("BESS_") || meterId.startsWith("SOLAR_")) {
			return "SOURCE";
		}
		if (meterId.startsWith("SELF_")) {
			return "SELF_USE";
		}
		if (meterId.startsWith("GRID_")) {
			return "GRID_POINT";
		}
		if (meterId.startsWith("DEST_")) {
			return "INTERCONNECT";
		}
		return "UNKNOWN";
	}

	static String sourceTypeForMeter(String meterId) {
		if (meterId.startsWith("BESS_")) {
			return "BESS";
		}
		if (meterId.startsWith("SOLAR_")) {
			return "RTS";
		}
		return "";
	}

	public record Options(Path csvPath, int injectionDelayMs, int queryDelayMs, long timestampOffsetMinutes,
			StopSignal stopSignal, Consumer<Instant> profileTimestampCallback) {
		public Options(Path csvPath, int injectionDelayMs, int queryDelayMs, long timestampOffsetMinutes,
				StopSignal stopSignal) {
			this(csvPath, injectionDelayMs, queryDelayMs, timestampOffsetMinutes, stopSignal, null);
		}
		public Options {
			stopSignal = stopSignal == null ? () -> false : stopSignal;
			profileTimestampCallback = profileTimestampCallback == null ? ignored -> { } : profileTimestampCallback;
		}
	}

	@FunctionalInterface
	public interface StopSignal {
		boolean stopRequested();
	}

	public record InjectionResult(int intervalCount, int rowCount, Instant firstTimestamp, Instant lastTimestamp) {
	}

	record CsvRow(String meterId, String role, String sourceType, int recordStatus, double impWh, double expWh,
			double impVa, double expVa) {
	}
}
