package io.openems.edge.bridge.dlms.api.task;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import gurux.dlms.GXDateTime;

public class DlmsProfileCsvStore {

	private static final int INTERVALS_PER_DAY = 48;
	private static final double PROFILE_VALUE_SCALE = 1_000d;
	private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("ddMM");
	private static final DateTimeFormatter ROW_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final String[] PROFILE_KEYS = { "kwhgiao", "kwhnhan", "kvarhgiao", "kvarhnhan" };
	private static final int KWH_GIAO_INDEX = 2;
	private static final int KWH_NHAN_INDEX = 3;
	private static final int KVARH_GIAO_INDEX = 4;
	private static final int KVARH_NHAN_INDEX = 5;

	private final Path dataDir;

	public DlmsProfileCsvStore(String dataDir) {
		var configured = dataDir == null || dataDir.isBlank() ? "data" : dataDir.trim();
		this.dataDir = Path.of(configured);
	}

	public Path getFile(LocalDate date, String outstation) {
		var yearDiff = date.getYear() - 2020;
		return this.dataDir.resolve(date.format(FILE_DATE_FORMAT) + yearDiff + outstation + ".csv");
	}

	public boolean exists(LocalDate date, String outstation) {
		return Files.exists(this.getFile(date, outstation));
	}

	public List<List<String>> read(LocalDate date, String outstation) throws IOException {
		var file = this.getFile(date, outstation);
		if (!Files.exists(file)) {
			return List.of();
		}
		var result = new ArrayList<List<String>>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				result.add(parseCsvLine(line));
			}
		}
		return result;
	}

	public boolean isComplete(LocalDate date, String outstation, int startIndex, int endIndex) throws IOException {
		if (endIndex < startIndex || endIndex < 0) {
			return false;
		}
		var firstInterval = Math.max(0, Math.min(startIndex, INTERVALS_PER_DAY - 1));
		var lastInterval = Math.max(0, Math.min(endIndex, INTERVALS_PER_DAY - 1));
		if (lastInterval < firstInterval) {
			return false;
		}
		var rows = this.read(date, outstation);
		if (rows.size() < PROFILE_KEYS.length) {
			return false;
		}
		var found = new HashMap<String, Boolean>();
		for (var row : rows) {
			if (row.size() < 2) {
				continue;
			}
			var key = row.get(1).trim().toLowerCase(Locale.ROOT);
			if (!Arrays.asList(PROFILE_KEYS).contains(key)) {
				continue;
			}
			var complete = true;
			for (int i = firstInterval; i <= lastInterval; i++) {
				var col = i + 2;
				if (col >= row.size() || isMissingIntervalCell(row.get(col))) {
					complete = false;
					break;
				}
			}
			if (complete) {
				found.put(key, true);
			}
		}
		return found.size() == PROFILE_KEYS.length;
	}

	public List<List<String>> mergeAndSave(LocalDate date, String outstation, Object[] profileRows) throws IOException {
		Files.createDirectories(this.dataDir);
		var oldRows = this.read(date, outstation);
		var newRows = this.formatRows(date, profileRows);
		var merged = this.merge(oldRows, newRows);
		this.write(this.getFile(date, outstation), merged);
		return merged;
	}

	public List<List<String>> filter(List<List<String>> rows, int startIndex, int endIndex) {
		var filtered = new ArrayList<List<String>>();
		var firstInterval = Math.max(0, Math.min(startIndex, INTERVALS_PER_DAY - 1));
		var lastInterval = Math.max(0, Math.min(endIndex, INTERVALS_PER_DAY - 1));
		for (var row : rows) {
			if (row.size() < 2) {
				filtered.add(row);
				continue;
			}
			var out = new ArrayList<String>();
			out.add(row.get(0));
			out.add(row.get(1));
			for (int i = firstInterval; i <= lastInterval; i++) {
				var col = i + 2;
				out.add(col < row.size() ? row.get(col) : "");
			}
			filtered.add(out);
		}
		return filtered;
	}

	public List<String> findMissingIntervals(LocalDate date, String outstation, int startIndex, int endIndex)
			throws IOException {
		var rows = this.read(date, outstation);
		if (rows.isEmpty()) {
			return List.of();
		}
		var firstInterval = Math.max(0, Math.min(startIndex, INTERVALS_PER_DAY - 1));
		var lastInterval = Math.max(0, Math.min(endIndex, INTERVALS_PER_DAY - 1));
		var missing = new ArrayList<String>();
		for (int i = firstInterval; i <= lastInterval; i++) {
			if (isMissingInterval(rows, i)) {
				missing.add(intervalLabel(i));
			}
		}
		return missing;
	}

	public static String toCsv(List<List<String>> rows) {
		var result = new StringBuilder();
		for (var row : rows) {
			result.append(toCsvLine(row)).append("\r\n");
		}
		return result.toString();
	}

	private static boolean isMissingInterval(List<List<String>> rows, int intervalIndex) {
		var col = intervalIndex + 2;
		var foundProfileRows = 0;
		for (var row : rows) {
			if (row.size() < 2) {
				continue;
			}
			var key = row.get(1).trim().toLowerCase(Locale.ROOT);
			if (!Arrays.asList(PROFILE_KEYS).contains(key)) {
				continue;
			}
			foundProfileRows++;
			if (col >= row.size() || isMissingIntervalCell(row.get(col))) {
				return true;
			}
		}
		return foundProfileRows < PROFILE_KEYS.length;
	}

	private static String intervalLabel(int intervalIndex) {
		var startMinutes = intervalIndex * 30;
		var endMinutes = (intervalIndex + 1) * 30;
		return formatTime(startMinutes) + "-" + formatTime(endMinutes);
	}

	private static String formatTime(int minutes) {
		var normalized = minutes % (24 * 60);
		var hour = normalized / 60;
		var minute = normalized % 60;
		return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
	}

	private static boolean isMissingIntervalCell(String value) {
		return value == null || value.isBlank() || "NaN".equalsIgnoreCase(value.trim());
	}

	public static int intervalIndex(LocalDateTime timestamp) {
		return timestamp.getHour() * 2 + timestamp.getMinute() / 30;
	}

	private List<List<String>> formatRows(LocalDate date, Object[] profileRows) {
		var values = new HashMap<String, String[]>();
		var previousValues = new HashMap<String, String>();
		for (var key : PROFILE_KEYS) {
			var boundaries = new String[INTERVALS_PER_DAY + 1];
			Arrays.fill(boundaries, "");
			values.put(key, boundaries);
			previousValues.put(key, "");
		}
		var maxBoundaryIndex = -1;
		var nextDayStart = date.plusDays(1).atStartOfDay();

		for (Object rowObject : profileRows) {
			if (!(rowObject instanceof Object[] row) || row.length < 2) {
				continue;
			}
			var timestamp = parseDateTime(row[0]);
			if (timestamp == null) {
				continue;
			}
			var rowValues = profileValues(row);
			if (timestamp.toLocalDate().isBefore(date)) {
				for (var key : PROFILE_KEYS) {
					var value = rowValues.get(key);
					if (value != null && !value.isBlank()) {
						previousValues.put(key, value);
					}
				}
				continue;
			}
			if (!timestamp.toLocalDate().equals(date) && !timestamp.equals(nextDayStart)) {
				continue;
			}
			var boundaryIndex = timestamp.equals(nextDayStart) ? INTERVALS_PER_DAY : intervalIndex(timestamp);
			if (boundaryIndex < 0 || boundaryIndex > INTERVALS_PER_DAY) {
				continue;
			}
			maxBoundaryIndex = Math.max(maxBoundaryIndex, boundaryIndex);
			for (var key : PROFILE_KEYS) {
				values.get(key)[boundaryIndex] = rowValues.get(key);
			}
		}
		for (var key : PROFILE_KEYS) {
			if (values.get(key)[0].isBlank()) {
				values.get(key)[0] = previousValues.get(key);
			}
			values.put(key, toIntervalDifferences(values.get(key), previousValues.get(key)));
		}
		if (maxBoundaryIndex >= 1) {
			for (var key : PROFILE_KEYS) {
				var intervals = values.get(key);
				for (int i = 0; i < maxBoundaryIndex; i++) {
					if (intervals[i].isBlank()) {
						intervals[i] = "NaN";
					}
				}
			}
		}
		var formatted = new ArrayList<List<String>>();
		for (var key : PROFILE_KEYS) {
			var row = new ArrayList<String>();
			row.add(date.format(ROW_DATE_FORMAT));
			row.add(key);
			row.addAll(Arrays.asList(values.get(key)));
			formatted.add(row);
		}
		return formatted;
	}

	private static String[] toIntervalDifferences(String[] cumulativeValues, String previousBeforeRange) {
		var result = new String[INTERVALS_PER_DAY];
		Arrays.fill(result, "");
		for (int i = 0; i < INTERVALS_PER_DAY; i++) {
			var previous = cumulativeValues[i];
			var current = i + 1 < cumulativeValues.length ? cumulativeValues[i + 1] : "";
			if (current.isBlank() || previous == null || previous.isBlank()) {
				result[i] = current.isBlank() ? "" : "NaN";
				continue;
			}
			try {
				var currentValue = Double.parseDouble(current);
				var previousValue = Double.parseDouble(previous);
				var diff = (currentValue - previousValue) / PROFILE_VALUE_SCALE;
				result[i] = diff < 0 ? "NaN" : formatNumber(diff);
			} catch (NumberFormatException e) {
				result[i] = "NaN";
			}
		}
		return result;
	}

	private List<List<String>> merge(List<List<String>> oldRows, List<List<String>> newRows) {
		if (oldRows.isEmpty()) {
			return newRows;
		}
		var oldByKey = new HashMap<String, List<String>>();
		for (var row : oldRows) {
			if (row.size() >= 2) {
				oldByKey.put(row.get(1), row);
			}
		}
		var mergedRows = new ArrayList<List<String>>();
		for (var newRow : newRows) {
			var oldRow = oldByKey.get(newRow.get(1));
			if (oldRow == null) {
				mergedRows.add(newRow);
				continue;
			}
			var merged = new ArrayList<String>();
			merged.add(newRow.get(0));
			merged.add(newRow.get(1));
			for (int i = 0; i < INTERVALS_PER_DAY; i++) {
				var col = i + 2;
				var newValue = col < newRow.size() ? newRow.get(col) : "";
				var oldValue = col < oldRow.size() ? oldRow.get(col) : "";
				merged.add(!newValue.isBlank() && !"NaN".equals(newValue) ? newValue : oldValue);
			}
			mergedRows.add(merged);
		}
		return mergedRows;
	}

	private void write(Path file, List<List<String>> rows) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (var row : rows) {
				writer.write(toCsvLine(row));
				writer.newLine();
			}
		}
	}

	private static LocalDateTime parseDateTime(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof java.util.Date date) {
			return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		}
		if (value instanceof GXDateTime dateTime) {
			Calendar calendar = dateTime.getLocalCalendar();
			return calendar.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		}
		var raw = value.toString().trim();
		for (var format : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "M/d/yyyy H:m:s",
				"M/d/yyyy, h:mm:ss a", "M/d/yy H:m:s", "M/d/yy, h:mm:ss a", "d/M/yyyy H:m:s",
				"d/M/yyyy, h:mm:ss a", "d/M/yy H:m:s", "d/M/yy, h:mm:ss a")) {
			try {
				return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(format, Locale.ENGLISH));
			} catch (Exception e) {
				// Try next format.
			}
		}
		return null;
	}

	private static HashMap<String, String> profileValues(Object[] row) {
		var result = new HashMap<String, String>();
		result.put("kwhgiao", formatProfileValue(row, KWH_GIAO_INDEX));
		result.put("kwhnhan", formatProfileValue(row, KWH_NHAN_INDEX));
		result.put("kvarhnhan", formatProfileValue(row, KVARH_NHAN_INDEX));
		result.put("kvarhgiao", formatProfileValue(row, KVARH_GIAO_INDEX));
		return result;
	}

	private static String formatProfileValue(Object[] row, int sourceIndex) {
		if (sourceIndex >= row.length || row[sourceIndex] == null) {
			return "";
		}
		var raw = row[sourceIndex].toString().trim();
		if (raw.isBlank()) {
			return "";
		}
		try {
			var value = Double.parseDouble(raw);
			return formatNumber(value);
		} catch (NumberFormatException e) {
			return raw;
		}
	}

	private static String formatNumber(double value) {
		if (value == Math.rint(value)) {
			return Long.toString(Math.round(value));
		}
		return java.math.BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
				.toPlainString();
	}

	private static List<String> parseCsvLine(String line) {
		var result = new ArrayList<String>();
		var current = new StringBuilder();
		var quoted = false;
		for (int i = 0; i < line.length(); i++) {
			var c = line.charAt(i);
			if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
				current.append('"');
				i++;
			} else if (c == '"') {
				quoted = !quoted;
			} else if (c == ',' && !quoted) {
				result.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		result.add(current.toString());
		return result;
	}

	private static String toCsvLine(List<String> row) {
		var result = new StringBuilder();
		for (int i = 0; i < row.size(); i++) {
			if (i > 0) {
				result.append(',');
			}
			var value = row.get(i) == null ? "" : row.get(i);
			if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
				result.append('"').append(value.replace("\"", "\"\"")).append('"');
			} else {
				result.append(value);
			}
		}
		return result.toString();
	}
}
