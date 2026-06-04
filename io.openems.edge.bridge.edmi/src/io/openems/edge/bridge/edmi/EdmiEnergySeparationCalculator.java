package io.openems.edge.bridge.edmi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

public class EdmiEnergySeparationCalculator {
	private static final String MEASUREMENT = "edmi_separated_energy_interval";
	private static final List<String> PROFILE_FIELDS = List.of("record_status", "total_energy_tot_imp_wh",
			"total_energy_tot_exp_wh");
	private static final List<String> K_FIELD = List.of("k_factor");

	private final EdmiBridge bridge;

	public EdmiEnergySeparationCalculator(EdmiBridge bridge) {
		this.bridge = bridge;
	}

	public void process(Instant timestamp, Collection<EdmiEnergyMeter> meters) throws Exception {
		if (timestamp == null || meters == null || meters.isEmpty()) {
			return;
		}

		var settings = this.bridge.getProfileIngestionSettings();
		var previousTimestamp = timestamp.minusMillis(settings.intervalMillis());
		var rows = new ArrayList<MeterProfilePair>();

		for (var meter : meters) {
			var current = this.readProfileRow(meter.meterId(), timestamp);
			if (current == null) {
				continue;
			}
			var previous = this.readProfileRow(meter.meterId(), previousTimestamp);
			rows.add(new MeterProfilePair(meter, current, previous));
		}

		if (rows.isEmpty()) {
			return;
		}

		var state = this.buildState(rows, meters);
		var formula = formulaFor(state);
		var lastK = this.loadLastK(timestamp);
		var inputs = this.buildInputs(rows);
		var result = applyFormula(formula, inputs, lastK);

		this.bridge.writeToInflux(Point.measurement(MEASUREMENT) //
				.time(timestamp, WritePrecision.MS) //
				.addTag("scope", "site") //
				.addField("interval_minutes", settings.intervalMinutes()) //
				.addField("self_available", state.selfAvailable()) //
				.addField("grid_available", state.gridAvailable()) //
				.addField("interconnect_available", state.interconnectAvailable()) //
				.addField("bess_available", state.bessAvailable()) //
				.addField("rts_available", state.rtsAvailable()) //
				.addField("bess_missing_count", state.bessMissingCount()) //
				.addField("rts_missing_count", state.rtsMissingCount()) //
				.addField("scenario_code", state.scenarioCode()) //
				.addField("formula_code", formula) //
				.addField("bess_discharge_kwh", inputs.bessDischargeKwh()) //
				.addField("bess_charge_kwh", inputs.bessChargeKwh()) //
				.addField("rts_export_kwh", inputs.rtsExportKwh()) //
				.addField("self_use_kwh", inputs.selfUseKwh()) //
				.addField("grid_export_kwh", inputs.gridExportKwh()) //
				.addField("interconnect_import_kwh", inputs.interconnectImportKwh()) //
				.addField("k_factor", result.k()) //
				.addField("rts_to_lmv_kwh", result.rtsToLmvKwh()) //
				.addField("bess_to_lmv_kwh", result.bessToLmvKwh()) //
				.addField("total_to_lmv_kwh", result.rtsToLmvKwh() + result.bessToLmvKwh()));
	}

	private ProfileRow readProfileRow(String meterId, Instant timestamp) throws Exception {
		JsonArray rows = this.bridge.queryProfileValuesFromInflux(meterId, timestamp, timestamp, PROFILE_FIELDS);
		if (rows == null || rows.size() == 0) {
			return null;
		}
		var row = rows.get(rows.size() - 1).getAsJsonObject();
		return new ProfileRow(number(row, "total_energy_tot_imp_wh"), number(row, "total_energy_tot_exp_wh"));
	}

	private IntervalState buildState(List<MeterProfilePair> rows, Collection<EdmiEnergyMeter> meters) {
		int expectedBess = 0;
		int expectedRts = 0;
		for (var meter : meters) {
			if (meter.isBessSource()) {
				expectedBess++;
			} else if (meter.isRtsSource()) {
				expectedRts++;
			}
		}

		int bessCount = 0;
		int rtsCount = 0;
		boolean self = false;
		boolean grid = false;
		boolean interconnect = false;

		for (var row : rows) {
			var meter = row.meter();
			var current = row.current();
			if (meter.isBessSource() && current.expWh() > 0) {
				bessCount++;
			} else if (meter.isRtsSource() && current.expWh() > 0) {
				rtsCount++;
			} else if (meter.isSelfUse() && current.impWh() > 0) {
				self = true;
			} else if (meter.isGridPoint() && current.expWh() > 0) {
				grid = true;
			} else if (meter.isInterconnect() && current.impWh() > 0) {
				interconnect = true;
			}
		}

		boolean bessAvailable = bessCount > 0;
		boolean rtsAvailable = rtsCount > 0;
		int bessMissing = bessAvailable ? expectedBess - bessCount : expectedBess;
		int rtsMissing = rtsAvailable ? expectedRts - rtsCount : expectedRts;
		bessMissing = Math.max(0, Math.min(bessMissing, expectedBess));
		rtsMissing = Math.max(0, Math.min(rtsMissing, expectedRts));

		var scenario = detectScenario(bessMissing, rtsMissing, self, grid, interconnect);
		return new IntervalState(self, grid, interconnect, bessAvailable, rtsAvailable, bessMissing, rtsMissing,
				scenario);
	}

	private EnergyInputs buildInputs(List<MeterProfilePair> rows) {
		double bessDischarge = 0;
		double bessCharge = 0;
		double rtsExport = 0;
		double selfUse = 0;
		double gridExport = 0;
		double interconnectImport = 0;

		for (var row : rows) {
			var previous = row.previous();
			if (previous == null) {
				continue;
			}
			var meter = row.meter();
			var current = row.current();
			if (meter.isBessSource()) {
				bessDischarge += deltaKwh(previous.expWh(), current.expWh());
				bessCharge += deltaKwh(previous.impWh(), current.impWh());
			} else if (meter.isRtsSource()) {
				rtsExport += deltaKwh(previous.expWh(), current.expWh());
			} else if (meter.isSelfUse()) {
				selfUse += deltaKwh(previous.impWh(), current.impWh());
			} else if (meter.isGridPoint()) {
				gridExport += deltaKwh(previous.expWh(), current.expWh());
			} else if (meter.isInterconnect()) {
				interconnectImport += deltaKwh(previous.impWh(), current.impWh());
			}
		}

		return new EnergyInputs(bessDischarge, bessCharge, rtsExport, selfUse, gridExport, interconnectImport);
	}

	private double loadLastK(Instant timestamp) {
		try {
			var start = timestamp.minusSeconds(370L * 24 * 60 * 60);
			JsonArray rows = this.bridge.querySeparatedEnergyFromInflux(start, timestamp.minusMillis(1), K_FIELD);
			for (int i = rows.size() - 1; i >= 0; i--) {
				var k = number(rows.get(i).getAsJsonObject(), "k_factor");
				if (k > 0) {
					return k;
				}
			}
		} catch (Exception e) {
			// Missing historical K is expected during first calculation.
		}
		return 0;
	}

	private static String detectScenario(int bessMissing, int rtsMissing, boolean self, boolean grid,
			boolean interconnect) {
		if (!grid) {
			return "INQUALIFIED";
		}
		if (!interconnect) {
			return "NO_INTERCONNECT";
		}
		if (!self) {
			return "NO_SELF";
		}
		if (bessMissing > 0 && rtsMissing > 0) {
			return "BESS_RTS_FAULTY";
		}
		if (bessMissing > 0) {
			return "BESS_FAULTY";
		}
		if (rtsMissing > 0) {
			return "RTS_FAULTY";
		}
		return "ALL_OK";
	}

	private static String formulaFor(IntervalState state) {
		if (!state.gridAvailable()) {
			return "F99_INQUALIFIED";
		}
		if (!state.selfAvailable()) {
			return "F05_NO_SELF";
		}
		if (state.bessMissingCount() > 0 && state.rtsMissingCount() > 0) {
			return "F07_BOTH_BESS_RTS_FAULTY";
		}
		if (state.bessMissingCount() > 0) {
			return "F06_ONLY_BESS_FAULTY";
		}
		if (state.rtsMissingCount() > 0) {
			return "F04_ONLY_RTS_FAULTY";
		}
		if (!state.interconnectAvailable()) {
			return "F03_NO_INTERCONNECT";
		}
		return "F01_NORMAL";
	}

	private static EnergyResult applyFormula(String formula, EnergyInputs inputs, double lastK) {
		double e = inputs.gridExportKwh();
		double eLmv = inputs.interconnectImportKwh();
		double k = e > 0 && eLmv > 0 ? Math.max(0, (e - eLmv) / e) : lastK;
		double rtsToLmv;
		double bessToLmv;

		switch (formula) {
		case "F01_NORMAL", "F03_NO_INTERCONNECT" -> {
			rtsToLmv = (inputs.rtsExportKwh() - inputs.bessChargeKwh() - inputs.selfUseKwh()) * (1 - k);
			bessToLmv = e * (1 - k) - rtsToLmv;
		}
		case "F02_NO_GRID" -> {
			rtsToLmv = (inputs.rtsExportKwh() - inputs.bessChargeKwh() - inputs.selfUseKwh()) * (1 - k);
			bessToLmv = eLmv - rtsToLmv;
		}
		case "F04_ONLY_RTS_FAULTY", "F05_NO_SELF", "F06_ONLY_BESS_FAULTY", "F07_BOTH_BESS_RTS_FAULTY" -> {
			rtsToLmv = (e - inputs.bessDischargeKwh()) * (1 - k);
			bessToLmv = inputs.bessDischargeKwh() * (1 - k);
		}
		default -> {
			rtsToLmv = 0;
			bessToLmv = 0;
		}
		}
		return new EnergyResult(k, Math.max(0, rtsToLmv), Math.max(0, bessToLmv));
	}

	private static double deltaKwh(double startWh, double endWh) {
		return Math.max(0, endWh - startWh) / 1000.0;
	}

	private static double number(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			return 0;
		}
		try {
			return value.getAsDouble();
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private record MeterProfilePair(EdmiEnergyMeter meter, ProfileRow current, ProfileRow previous) {
	}

	private record ProfileRow(double impWh, double expWh) {
	}

	private record IntervalState(boolean selfAvailable, boolean gridAvailable, boolean interconnectAvailable,
			boolean bessAvailable, boolean rtsAvailable, int bessMissingCount, int rtsMissingCount,
			String scenarioCode) {
	}

	private record EnergyInputs(double bessDischargeKwh, double bessChargeKwh, double rtsExportKwh, double selfUseKwh,
			double gridExportKwh, double interconnectImportKwh) {
	}

	private record EnergyResult(double k, double rtsToLmvKwh, double bessToLmvKwh) {
	}
}
