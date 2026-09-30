package io.openems.edge.controller.energy.calculator;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.common.component.ComponentManager;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.InfluxDBClientOptions;
import com.influxdb.client.domain.InfluxQLQuery;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.InfluxQLQueryResult;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.energy.calculator.api.EnergyCalculator;
import io.openems.shared.influxdb.InfluxConnector;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.Energy.Calculator", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerEnergyCalculatorImpl extends AbstractOpenemsComponent implements OpenemsComponent, Controller, EnergyCalculator {

	private static final String PROFILE_MEASUREMENT = "edmi_profile";
	private static final String INTERVAL_STATE_MEASUREMENT = "energy_interval_state";
	private static final String PERIOD_MEASUREMENT = "energy_calculation_period";
	private static final String BREAKDOWN_MEASUREMENT = "energy_monthly_calculation_breakdown";
	private static final String SUMMARY_MEASUREMENT = "energy_monthly_summary";
	private static final List<String> PROFILE_FIELDS = List.of("record_status", "total_energy_tot_imp_wh",
			"total_energy_tot_exp_wh");

	private final Logger log = LoggerFactory.getLogger(ControllerEnergyCalculatorImpl.class);

	private Config config;
	private int intervalMinutes;
	private InfluxConnector influxConnector;
	private PostgreSqlConnector postgreSqlConnector;

	@Reference
	private ComponentManager componentManager;

	/**
	 * Get total meter count across all EdmiBridge components by role and source type.
	 */
	private int getTotalMeterCount(String role, String sourceType) {
		int count = 0;
		for (OpenemsComponent component : this.componentManager.getEnabledComponents()) {
			// Check if component is an EdmiBridge by looking for the method
			if (component.getClass().getName().contains("EdmiBridge")) {
				try {
					var method = component.getClass().getMethod("getEnergyMeterCount", String.class, String.class);
					count += (Integer) method.invoke(component, role, sourceType);
				} catch (Exception e) {
					// Component doesn't have the method, skip
				}
			}
		}
		return count;
	}

	public ControllerEnergyCalculatorImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values());
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.intervalMinutes = config.intervalMinutes();
		this.influxConnector = new InfluxConnector(config.id(), config.queryLanguage(), URI.create(config.influxUrl()),
				config.influxOrg(), config.influxApiKey(), config.influxBucket(), "maximeter-energy-calculator",
				config.isReadOnly(), 5, config.maxQueueSize(), e -> this.log.error("InfluxDB error: {}", e.toString()));
		
		// Initialize PostgreSQL connector if enabled
		if (config.enablePostgreSql()) {
			this.postgreSqlConnector = new PostgreSqlConnector(config.id(), config.postgreSqlHost(), 
					config.postgreSqlPort(), config.postgreSqlDatabase(), config.postgreSqlUser(), 
					config.postgreSqlPassword(), config.postgreSqlReadOnly());
			this.log.info("PostgreSQL connector initialized for controller [{}]", config.id());
		}
		
		this.log.info("Energy Calculator Controller activated.");
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
		if (this.influxConnector != null) {
			this.influxConnector.deactivate();
		}
		if (this.postgreSqlConnector != null) {
			this.postgreSqlConnector.deactivate();
		}
		this.log.info("Energy Calculator Controller deactivated.");
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.calculateForIntervalEnding(this.alignToInterval(Instant.now()));
	}

	@Override
	public void calculateForIntervalEnding(Instant intervalEnd) throws OpenemsNamedException {
		if (intervalEnd == null) {
			return;
		}
		var intervalStart = intervalEnd.minus(this.intervalMinutes, ChronoUnit.MINUTES);
		var readings = this.queryProfileReadings(intervalStart, intervalEnd);

		if (readings.isEmpty()) {
			this.log.info("No EDMI profile readings found for {} -> {}", intervalStart, intervalEnd);
			return;
		}

		var state = this.buildIntervalState(readings, intervalEnd);
		this.writeIntervalState(state);
		
		// Also write to PostgreSQL if enabled
		if (this.postgreSqlConnector != null) {
			this.postgreSqlConnector.writeIntervalState(
				state.timestamp(), state.year(), state.month(), 
				state.selfAvailable(), state.mainAvailable(), 
				state.backupAvailable(), state.bessAvailable(), 
				state.rtsAvailable(), state.bessMissingCount(), 
				state.rtsMissingCount(), state.mainMissingCount(),
				state.backupMissingCount(), state.scenarioCode()
			);
		}

		var monthStart = monthStart(intervalEnd);
		var allStates = this.queryIntervalStates(monthStart, intervalEnd);
		var periods = this.buildPeriods(allStates);
		this.writePeriods(periods);
		
		// Also write periods to PostgreSQL if enabled
		if (this.postgreSqlConnector != null) {
			for (var period : periods) {
				this.postgreSqlConnector.writeCalculationPeriod(
					period.periodStart, period.periodEnd, period.year, period.month,
					period.scenarioCode, period.formulaCode, period.intervalCount
				);
			}
		}
		
		var summary = this.buildMonthlySummary(periods, monthStart, intervalEnd);
		this.writeMonthlySummary(summary);
		
		// Also write summary to PostgreSQL if enabled
		if (this.postgreSqlConnector != null) {
			this.postgreSqlConnector.writeMonthlySummary(
				summary.year, summary.month,
				summary.bessToLmvKwh, summary.rtsToLmvKwh, summary.totalToLmvKwh,
				summary.kFactor, summary.start, summary.end, summary.inqualifiedIntervals
			);
			// Write breakdowns
			for (var breakdown : summary.breakdowns) {
				this.postgreSqlConnector.writeMonthlyBreakdown(
					breakdown.period.periodStart, breakdown.period.periodEnd,
					breakdown.period.intervalCount, breakdown.period.scenarioCode,
					breakdown.period.formulaCode, breakdown.inputs.bessDischarge,
					breakdown.inputs.rtsExports, breakdown.inputs.selfUse,
					breakdown.inputs.grid, breakdown.inputs.interconnect,
					breakdown.result.k, breakdown.result.rtsToLmv,
					breakdown.result.bessToLmv
				);
			}
		}

		this.log.info("Energy calculations completed for interval ending at {}", intervalEnd);
	}

	private Instant alignToInterval(Instant timestamp) {
		var epochSeconds = timestamp.getEpochSecond();
		var intervalSeconds = this.intervalMinutes * 60L;
		return Instant.ofEpochSecond((epochSeconds / intervalSeconds) * intervalSeconds);
	}

	private static Instant monthStart(Instant timestamp) {
		return timestamp.atZone(ZoneId.systemDefault()).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
	}

	private List<ProfileReading> queryProfileReadings(Instant start, Instant end) {
		try (var client = this.createInfluxClient()) {
			return switch (this.config.queryLanguage()) {
			case FLUX -> this.queryProfileReadingsByFlux(client, start, end);
			case INFLUX_QL -> this.queryProfileReadingsByInfluxQl(client, start, end);
			};
		} catch (Exception e) {
			this.log.error("Error querying profile readings", e);
			return List.of();
		}
	}

	private List<ProfileReading> queryProfileReadingsByFlux(InfluxDBClient client, Instant start, Instant end) {
		var query = """
				from(bucket: "%s")
				  |> range(start: time(v: "%s"), stop: time(v: "%s"))
				  |> filter(fn: (r) => r._measurement == "%s")
				  |> filter(fn: (r) => r._field == "record_status" or r._field == "total_energy_tot_imp_wh" or r._field == "total_energy_tot_exp_wh")
				  |> keep(columns: ["_time", "_field", "_value", "meter_id", "meter_role", "source_type"])
				  |> sort(columns: ["_time", "meter_id"])
				""".formatted(escapeFlux(this.config.influxBucket()), start.toString(), end.plusMillis(1).toString(),
				PROFILE_MEASUREMENT);
		this.log.debug("Energy calculator Flux query:\n{}", query);

		var rows = new TreeMap<ProfileKey, ProfileReadingBuilder>();
		for (var table : client.getQueryApi().query(query)) {
			for (FluxRecord record : table.getRecords()) {
				var timestamp = record.getTime();
				var meterId = stringValue(record.getValueByKey("meter_id"));
				if (timestamp == null || meterId == null) {
					continue;
				}
				var key = new ProfileKey(timestamp, meterId);
				var builder = rows.computeIfAbsent(key,
						ignored -> new ProfileReadingBuilder(meterId, timestamp, stringValue(record.getValueByKey("meter_role")),
								stringValue(record.getValueByKey("source_type"))));
				builder.set(record.getField(), record.getValue());
			}
		}
		return rows.values().stream().map(ProfileReadingBuilder::build).toList();
	}

	private List<ProfileReading> queryProfileReadingsByInfluxQl(InfluxDBClient client, Instant start, Instant end) {
		var query = "SELECT \"record_status\", \"total_energy_tot_imp_wh\", \"total_energy_tot_exp_wh\"" //
				+ " FROM \"" + PROFILE_MEASUREMENT + "\"" //
				+ " WHERE time >= " + start.toEpochMilli() + "ms" //
				+ " AND time <= " + end.toEpochMilli() + "ms" //
				+ " GROUP BY \"meter_id\", \"meter_role\", \"source_type\" ORDER BY time ASC";
		this.log.debug("Energy calculator InfluxQL query: {}", query);

		var result = client.getInfluxQLQueryApi()
				.query(new InfluxQLQuery(query, this.database()).setPrecision(InfluxQLQuery.InfluxQLPrecision.MILLISECONDS));
		var readings = new ArrayList<ProfileReading>();
		if (result == null || result.getResults() == null) {
			return readings;
		}
		for (var queryResult : result.getResults()) {
			appendInfluxQlProfileReadings(readings, queryResult);
		}
		readings.sort(Comparator.comparing(ProfileReading::timestamp).thenComparing(ProfileReading::meterId));
		return readings;
	}

	private static void appendInfluxQlProfileReadings(List<ProfileReading> readings, InfluxQLQueryResult.Result result) {
		if (result == null || result.getSeries() == null) {
			return;
		}
		for (var series : result.getSeries()) {
			var tags = series.getTags();
			var meterId = tags == null ? null : tags.get("meter_id");
			if (meterId == null || series.getValues() == null) {
				continue;
			}
			var role = tags.get("meter_role");
			var source = tags.get("source_type");
			for (var row : series.getValues()) {
				var timeValue = row.getValueByKey("time");
				if (timeValue == null) {
					continue;
				}
				readings.add(new ProfileReading(meterId, Instant.ofEpochMilli(Long.parseLong(timeValue.toString())),
						role, source, numberValue(row.getValueByKey("total_energy_tot_imp_wh")).doubleValue(),
						numberValue(row.getValueByKey("total_energy_tot_exp_wh")).doubleValue(),
						numberValue(row.getValueByKey("record_status")).doubleValue()));
			}
		}
	}

	private IntervalState buildIntervalState(List<ProfileReading> readings, Instant ts) {
		// Get expected counts from ALL registered bridges dynamically
		var expectedBess = getTotalMeterCount("SOURCE", "BESS");
		var expectedRts = getTotalMeterCount("SOURCE", "RTS");
		var expectedMain = getTotalMeterCount("MAIN", null);
		var expectedBackup = getTotalMeterCount("BACKUP", null);
		
		var bessCount = 0;
		var rtsCount = 0;
		var mainCount = 0;
		var backupCount = 0;
		var selfPresent = false;

		for (var reading : readings) {
			if ("SOURCE".equals(reading.meterRole) && "BESS".equals(reading.sourceType)) {
				// BESS source: count if data is present (even if 0 energy - no change from last interval is valid)
				if (reading.totalEnergyTotExpWh != null) {
					bessCount++;
				}
			} else if ("SOURCE".equals(reading.meterRole) && "RTS".equals(reading.sourceType)) {
				// RTS source: count if data is present (even if 0 energy)
				if (reading.totalEnergyTotExpWh != null) {
					rtsCount++;
				}
			} else if ("SELF_USE".equals(reading.meterRole)) {
				// SELF_USE: count if data is present (even if 0 energy)
				if (reading.totalEnergyTotImpWh != null) {
					selfPresent = true;
				}
			} else if ("MAIN".equals(reading.meterRole)) {
				// MAIN meter: count if data is present (even if 0 energy)
				if (reading.totalEnergyTotExpWh != null) {
					mainCount++;
				}
			} else if ("BACKUP".equals(reading.meterRole)) {
				// BACKUP meter: count if data is present (even if 0 energy)
				if (reading.totalEnergyTotImpWh != null) {
					backupCount++;
				}
			}
		}

		// Use dynamic expected counts from all bridges
		var bessMissing = expectedBess > 0 ? clamp(expectedBess - bessCount, 0, expectedBess) : 0;
		var rtsMissing = expectedRts > 0 ? clamp(expectedRts - rtsCount, 0, expectedRts) : 0;
		var mainMissing = expectedMain > 0 ? clamp(expectedMain - mainCount, 0, expectedMain) : 0;
		var backupMissing = expectedBackup > 0 ? clamp(expectedBackup - backupCount, 0, expectedBackup) : 0;
		
		// New scenario detection with MAIN/BACKUP
		var mainAvailable = mainCount > 0;
		var backupAvailable = backupCount > 0;
		var scenario = detectScenario(bessMissing, rtsMissing, selfPresent, mainAvailable, backupAvailable);
		var zdt = ts.minus(this.intervalMinutes, ChronoUnit.MINUTES).atZone(ZoneId.systemDefault());
		return new IntervalState(ts, zdt.getYear(), zdt.getMonthValue(), selfPresent, 
				mainAvailable, backupAvailable,
				bessCount > 0, rtsCount > 0, 
				bessMissing, rtsMissing, mainMissing, backupMissing, scenario);
	}

	private List<IntervalState> queryIntervalStates(Instant start, Instant end) {
		try (var client = this.createInfluxClient()) {
			return switch (this.config.queryLanguage()) {
			case FLUX -> this.queryIntervalStatesByFlux(client, start, end);
			case INFLUX_QL -> this.queryIntervalStatesByInfluxQl(client, start, end);
			};
		} catch (Exception e) {
			this.log.error("Error querying interval states", e);
			return List.of();
		}
	}

	private List<IntervalState> queryIntervalStatesByFlux(InfluxDBClient client, Instant start, Instant end) {
		var query = """
				from(bucket: "%s")
				  |> range(start: time(v: "%s"), stop: time(v: "%s"))
				  |> filter(fn: (r) => r._measurement == "%s")
				  |> filter(fn: (r) => r.controller_id == "%s")
				  |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
				  |> sort(columns: ["_time"])
				""".formatted(escapeFlux(this.config.influxBucket()), start.toString(), end.plusMillis(1).toString(),
				INTERVAL_STATE_MEASUREMENT, escapeFlux(this.config.id()));
		return client.getQueryApi().query(query).stream().flatMap(t -> t.getRecords().stream()).map(this::intervalStateFromFlux)
				.sorted(Comparator.comparing(IntervalState::timestamp)).toList();
	}

	private List<IntervalState> queryIntervalStatesByInfluxQl(InfluxDBClient client, Instant start, Instant end) {
		var query = "SELECT * FROM \"" + INTERVAL_STATE_MEASUREMENT + "\" WHERE \"controller_id\" = '"
				+ escapeInfluxQl(this.config.id()) + "' AND time >= " + start.toEpochMilli() + "ms AND time <= "
				+ end.toEpochMilli() + "ms ORDER BY time ASC";
		var result = client.getInfluxQLQueryApi()
				.query(new InfluxQLQuery(query, this.database()).setPrecision(InfluxQLQuery.InfluxQLPrecision.MILLISECONDS));
		var states = new ArrayList<IntervalState>();
		if (result == null || result.getResults() == null) {
			return states;
		}
		for (var queryResult : result.getResults()) {
			if (queryResult.getSeries() == null) {
				continue;
			}
			for (var series : queryResult.getSeries()) {
				if (series.getValues() == null) {
					continue;
				}
				for (var row : series.getValues()) {
					states.add(intervalStateFromInfluxQl(row));
				}
			}
		}
		states.sort(Comparator.comparing(IntervalState::timestamp));
		return states;
	}

	private List<CalculationPeriod> buildPeriods(List<IntervalState> states) {
		var periods = new ArrayList<CalculationPeriod>();
		CalculationPeriod current = null;
		for (var state : states) {
			var formula = formulaFor(state);
			var start = state.timestamp.minus(this.intervalMinutes, ChronoUnit.MINUTES);
			if (current == null || !current.scenarioCode.equals(state.scenarioCode) || !current.formulaCode.equals(formula)) {
				if (current != null) {
					periods.add(current);
				}
				current = new CalculationPeriod(start, state.timestamp, state.year, state.month, state.scenarioCode, formula, 1);
			} else {
				current.periodEnd = state.timestamp;
				current.intervalCount++;
			}
		}
		if (current != null) {
			periods.add(current);
		}
		return periods;
	}

	private MonthlySummary buildMonthlySummary(List<CalculationPeriod> periods, Instant monthStart, Instant cutoff) {
		var readings = this.queryProfileReadings(monthStart, cutoff);
		var byMeter = new TreeMap<String, List<ProfileReading>>();
		for (var reading : readings) {
			byMeter.computeIfAbsent(reading.meterId, ignored -> new ArrayList<>()).add(reading);
		}
		byMeter.values().forEach(list -> list.sort(Comparator.comparing(ProfileReading::timestamp)));

		var summary = new MonthlySummary();
		summary.year = cutoff.atZone(ZoneId.systemDefault()).getYear();
		summary.month = cutoff.atZone(ZoneId.systemDefault()).getMonthValue();
		summary.start = periods.isEmpty() ? monthStart : periods.get(0).periodStart;
		summary.end = periods.isEmpty() ? cutoff : periods.get(periods.size() - 1).periodEnd;

		double lastK = 0.0;
		double sumK = 0.0;
		for (var period : periods) {
			var inputs = sumPeriodInputs(period, byMeter);
			var result = applyFormula(period.formulaCode, inputs, lastK);
			if (lastK == 0.0 && result.k > 0.0) {
				lastK = result.k;
			}
			if ("INVALID".equals(period.scenarioCode)) {
				summary.inqualifiedIntervals += period.intervalCount;
			}
			summary.bessToLmvKwh += result.bessToLmv;
			summary.rtsToLmvKwh += result.rtsToLmv;
			summary.totalToLmvKwh += result.bessToLmv + result.rtsToLmv;
			sumK += lastK;
			summary.breakdowns.add(new MonthlyBreakdown(period, inputs, result));
		}
		summary.kFactor = periods.isEmpty() ? 0.0 : sumK / periods.size();
		return summary;
	}

	private PeriodInputs sumPeriodInputs(CalculationPeriod period, Map<String, List<ProfileReading>> byMeter) {
		var inputs = new PeriodInputs();
		for (var readings : byMeter.values()) {
			var first = readings.get(0);
			var field = switch (first.meterRole) {
			case "SOURCE" -> "BESS".equals(first.sourceType) ? "bess" : "rts";
			case "SELF_USE" -> "self";
			case "GRID_POINT" -> "grid";
			case "INTERCONNECT" -> "inter";
			default -> "";
			};
			var start = readingAt(readings, period.periodStart.plus(this.intervalMinutes, ChronoUnit.MINUTES));
			var end = readingAt(readings, period.periodEnd);
			if (start == null || end == null) {
				continue;
			}
			switch (field) {
			case "bess" -> {
				inputs.bessDischarge += positiveDelta(start.totalEnergyTotExpWh, end.totalEnergyTotExpWh);
				inputs.bessCharge += positiveDelta(start.totalEnergyTotImpWh, end.totalEnergyTotImpWh);
			}
			case "rts" -> inputs.rtsExports += positiveDelta(start.totalEnergyTotExpWh, end.totalEnergyTotExpWh);
			case "self" -> inputs.selfUse += positiveDelta(start.totalEnergyTotImpWh, end.totalEnergyTotImpWh);
			case "grid" -> inputs.grid += positiveDelta(start.totalEnergyTotExpWh, end.totalEnergyTotExpWh);
			case "inter" -> inputs.interconnect += positiveDelta(start.totalEnergyTotImpWh, end.totalEnergyTotImpWh);
			default -> {
			}
			}
		}
		return inputs;
	}

	private void writeIntervalState(IntervalState state) {
		this.influxConnector.write(Point.measurement(INTERVAL_STATE_MEASUREMENT).addTag("controller_id", this.config.id())
				.time(state.timestamp, WritePrecision.MS).addField("year", state.year).addField("month", state.month)
				.addField("self_available", state.selfAvailable).addField("main_available", state.mainAvailable)
				.addField("backup_available", state.backupAvailable)
				.addField("bess_available", state.bessAvailable).addField("rts_available", state.rtsAvailable)
				.addField("bess_missing_count", state.bessMissingCount)
				.addField("rts_missing_count", state.rtsMissingCount)
				.addField("main_missing_count", state.mainMissingCount)
				.addField("backup_missing_count", state.backupMissingCount)
				.addField("scenario_code", state.scenarioCode));
	}

	private void writePeriods(List<CalculationPeriod> periods) {
		for (var period : periods) {
			this.influxConnector.write(Point.measurement(PERIOD_MEASUREMENT).addTag("controller_id", this.config.id())
					.addTag("scenario_code", period.scenarioCode).addTag("formula_code", period.formulaCode)
					.time(period.periodEnd, WritePrecision.MS).addField("period_start", period.periodStart.toString())
					.addField("period_end", period.periodEnd.toString()).addField("year", period.year)
					.addField("month", period.month).addField("interval_count", period.intervalCount));
		}
	}

	private void writeMonthlySummary(MonthlySummary summary) {
		for (var breakdown : summary.breakdowns) {
			this.influxConnector.write(Point.measurement(BREAKDOWN_MEASUREMENT).addTag("controller_id", this.config.id())
					.addTag("scenario_code", breakdown.period.scenarioCode).addTag("formula_code", breakdown.period.formulaCode)
					.time(breakdown.period.periodEnd, WritePrecision.MS)
					.addField("period_start", breakdown.period.periodStart.toString())
					.addField("period_end", breakdown.period.periodEnd.toString())
					.addField("interval_count", breakdown.period.intervalCount)
					.addField("bess_energy_kwh", round4(breakdown.inputs.bessDischarge))
					.addField("rts_energy_kwh", round4(breakdown.inputs.rtsExports))
					.addField("self_use_energy_kwh", round4(breakdown.inputs.selfUse))
					.addField("grid_energy_kwh", round4(breakdown.inputs.grid))
					.addField("interconnect_energy_kwh", round4(breakdown.inputs.interconnect))
					.addField("k_factor", round6(breakdown.result.k))
					.addField("rts_to_lmv_kwh", round4(breakdown.result.rtsToLmv))
					.addField("bess_to_lmv_kwh", round4(breakdown.result.bessToLmv)));
		}
		this.influxConnector.write(Point.measurement(SUMMARY_MEASUREMENT).addTag("controller_id", this.config.id())
				.addTag("year", Integer.toString(summary.year)).addTag("month", Integer.toString(summary.month))
				.time(summary.end, WritePrecision.MS).addField("start_date_time", summary.start.toString())
				.addField("end_date_time", summary.end.toString())
				.addField("bess_to_lmv_energy_kwh", round4(summary.bessToLmvKwh))
				.addField("rts_to_lmv_energy_kwh", round4(summary.rtsToLmvKwh))
				.addField("total_energy_to_lmv_kwh", round4(summary.totalToLmvKwh))
				.addField("k_factor", round6(summary.kFactor))
				.addField("number_of_inqualified_intervals", summary.inqualifiedIntervals));
	}

	private InfluxDBClient createInfluxClient() {
		var options = InfluxDBClientOptions.builder().url(this.config.influxUrl()).org(this.config.influxOrg())
				.bucket(this.config.influxBucket());
		if (this.config.influxApiKey() != null && !this.config.influxApiKey().isBlank()) {
			options.authenticateToken(this.config.influxApiKey().toCharArray());
		}
		return InfluxDBClientFactory.create(options.build()).enableGzip();
	}

	private String database() {
		return this.config.influxBucket().split("/")[0];
	}

	private IntervalState intervalStateFromFlux(FluxRecord record) {
		var ts = record.getTime();
		return new IntervalState(ts, numberValue(record.getValueByKey("year")).intValue(),
				numberValue(record.getValueByKey("month")).intValue(), boolValue(record.getValueByKey("self_available")),
				boolValue(record.getValueByKey("main_available")), boolValue(record.getValueByKey("backup_available")),
				boolValue(record.getValueByKey("bess_available")), boolValue(record.getValueByKey("rts_available")),
				numberValue(record.getValueByKey("bess_missing_count")).intValue(),
				numberValue(record.getValueByKey("rts_missing_count")).intValue(),
				numberValue(record.getValueByKey("main_missing_count")).intValue(),
				numberValue(record.getValueByKey("backup_missing_count")).intValue(),
				stringValue(record.getValueByKey("scenario_code")));
	}

	private static IntervalState intervalStateFromInfluxQl(InfluxQLQueryResult.Series.Record row) {
		return new IntervalState(Instant.ofEpochMilli(Long.parseLong(row.getValueByKey("time").toString())),
				numberValue(row.getValueByKey("year")).intValue(), numberValue(row.getValueByKey("month")).intValue(),
				boolValue(row.getValueByKey("self_available")), boolValue(row.getValueByKey("main_available")),
				boolValue(row.getValueByKey("backup_available")), boolValue(row.getValueByKey("bess_available")),
				boolValue(row.getValueByKey("rts_available")),
				numberValue(row.getValueByKey("bess_missing_count")).intValue(),
				numberValue(row.getValueByKey("rts_missing_count")).intValue(),
				numberValue(row.getValueByKey("main_missing_count")).intValue(),
				numberValue(row.getValueByKey("backup_missing_count")).intValue(),
				stringValue(row.getValueByKey("scenario_code")));
	}

	private static ProfileReading readingAt(List<ProfileReading> readings, Instant ts) {
		ProfileReading candidate = null;
		for (var reading : readings) {
			if (!reading.timestamp.isAfter(ts)) {
				candidate = reading;
			} else {
				break;
			}
		}
		return candidate;
	}

	private static PeriodResult applyFormula(String formulaCode, PeriodInputs inputs, double lastK) {
		var e = inputs.grid;
		var eLmv = inputs.interconnect;
		var eSelf = inputs.selfUse;
		var eRts = inputs.rtsExports;
		var eBessCharge = inputs.bessCharge;
		var eBessDis = inputs.bessDischarge;
		var k = e > 0 && eLmv > 0 ? Math.max(0.0, (e - eLmv) / e) : lastK;
		var rtsToLmv = 0.0;
		var bessToLmv = 0.0;
		switch (formulaCode) {
		case "F01_NORMAL", "F03_NO_BACKUP" -> {
			rtsToLmv = (eRts - eBessCharge - eSelf) * (1 - k);
			bessToLmv = e * (1 - k) - rtsToLmv;
		}
		case "F02_NO_MAIN" -> {
			rtsToLmv = (eRts - eBessCharge - eSelf) * (1 - k);
			bessToLmv = eLmv * (1 - k) - rtsToLmv;
		}
		case "F04_ONLY_RTS_FAULTY", "F05_NO_SELF", "F06_ONLY_BESS_FAULTY", "F07_BOTH_BESS_RTS_FAULTY" -> {
			rtsToLmv = (e - eBessDis) * (1 - k);
			bessToLmv = eBessDis * (1 - k);
		}
		case "F99_INQUALIFIED" -> {
			// No calculation for inqualified intervals
			rtsToLmv = 0.0;
			bessToLmv = 0.0;
		}
		default -> {
		}
		}
		return new PeriodResult(k, Math.max(0.0, rtsToLmv), Math.max(0.0, bessToLmv));
	}

	private static boolean isIntervalQualified(int bessMissing, int rtsMissing, boolean selfAvailable, boolean mainAvailable, boolean backupAvailable) {
		if (mainAvailable) {
			return true;
		}
		var sourcesComplete = bessMissing == 0 && rtsMissing == 0;
		return backupAvailable && selfAvailable && sourcesComplete;
	}

	private static String detectScenario(int bessMissing, int rtsMissing, boolean selfAvailable, boolean mainAvailable, boolean backupAvailable) {
		if (!isIntervalQualified(bessMissing, rtsMissing, selfAvailable, mainAvailable, backupAvailable)) {
			return "INQUALIFIED";
		}
		if (!mainAvailable) {
			return "NO_MAIN";
		}
		if (!backupAvailable) {
			return "NO_BACKUP";
		}
		if (!selfAvailable) {
			return "NO_SELF";
		}
		var bessFaulty = bessMissing > 0;
		var rtsFaulty = rtsMissing > 0;
		if (bessFaulty && rtsFaulty) {
			return "BESS_RTS_FAULTY";
		}
		if (bessFaulty) {
			return "BESS_FAULTY";
		}
		if (rtsFaulty) {
			return "RTS_FAULTY";
		}
		return "ALL_OK";
	}

	private static String formulaFor(IntervalState state) {
		if (!isIntervalQualified(state.bessMissingCount, state.rtsMissingCount, state.selfAvailable, state.mainAvailable, state.backupAvailable)) {
			return "F99_INQUALIFIED";
		}
		if (!state.mainAvailable) {
			return "F02_NO_MAIN";
		}
		// Self meter has priority
		if (!state.selfAvailable) {
			return "F05_NO_SELF";
		}
		var bessFaulty = state.bessMissingCount > 0;
		var rtsFaulty = state.rtsMissingCount > 0;
		if (bessFaulty && rtsFaulty) {
			return "F07_BOTH_BESS_RTS_FAULTY";
		}
		if (bessFaulty) {
			return "F06_ONLY_BESS_FAULTY";
		}
		if (rtsFaulty) {
			return "F04_ONLY_RTS_FAULTY";
		}
		// BACKUP only affects K (reuse last_K)
		if (!state.backupAvailable) {
			return "F03_NO_BACKUP";
		}
		return "F01_NORMAL";
	}

	private static Number numberValue(Object value) {
		if (value instanceof Number number) {
			return number;
		}
		if (value == null) {
			return 0.0;
		}
		return Double.parseDouble(value.toString());
	}

	private static boolean boolValue(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		return value != null && Boolean.parseBoolean(value.toString());
	}

	private static String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private static boolean positive(Double value) {
		return value != null && value > 0.0;
	}

	private static double positiveDelta(Double start, Double end) {
		if (start == null || end == null) {
			return 0.0;
		}
		return Math.max(0.0, end - start);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private static double round6(double value) {
		return Math.round(value * 1_000_000.0) / 1_000_000.0;
	}

	private static String escapeFlux(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String escapeInfluxQl(String value) {
		return value.replace("\\", "\\\\").replace("'", "\\'");
	}

	private record ProfileKey(Instant timestamp, String meterId) implements Comparable<ProfileKey> {
		@Override
		public int compareTo(ProfileKey other) {
			var time = this.timestamp.compareTo(other.timestamp);
			return time != 0 ? time : this.meterId.compareTo(other.meterId);
		}
	}

	private static final class ProfileReadingBuilder {
		private final String meterId;
		private final Instant timestamp;
		private final String meterRole;
		private final String sourceType;
		private Double imp;
		private Double exp;
		private Double status;

		private ProfileReadingBuilder(String meterId, Instant timestamp, String meterRole, String sourceType) {
			this.meterId = meterId;
			this.timestamp = timestamp;
			this.meterRole = meterRole;
			this.sourceType = sourceType;
		}

		private void set(String field, Object value) {
			switch (field) {
			case "total_energy_tot_imp_wh" -> this.imp = numberValue(value).doubleValue();
			case "total_energy_tot_exp_wh" -> this.exp = numberValue(value).doubleValue();
			case "record_status" -> this.status = numberValue(value).doubleValue();
			default -> {
			}
			}
		}

		private ProfileReading build() {
			return new ProfileReading(this.meterId, this.timestamp, this.meterRole, this.sourceType, this.imp, this.exp,
					this.status);
		}
	}

	private record ProfileReading(String meterId, Instant timestamp, String meterRole, String sourceType,
			Double totalEnergyTotImpWh, Double totalEnergyTotExpWh, Double recordStatus) {
	}

	private record IntervalState(Instant timestamp, int year, int month, boolean selfAvailable, boolean mainAvailable,
			boolean backupAvailable, boolean bessAvailable, boolean rtsAvailable, int bessMissingCount,
			int rtsMissingCount, int mainMissingCount, int backupMissingCount, String scenarioCode) {
	}

	private static final class CalculationPeriod {
		private final Instant periodStart;
		private Instant periodEnd;
		private final int year;
		private final int month;
		private final String scenarioCode;
		private final String formulaCode;
		private int intervalCount;

		private CalculationPeriod(Instant periodStart, Instant periodEnd, int year, int month, String scenarioCode,
				String formulaCode, int intervalCount) {
			this.periodStart = periodStart;
			this.periodEnd = periodEnd;
			this.year = year;
			this.month = month;
			this.scenarioCode = scenarioCode;
			this.formulaCode = formulaCode;
			this.intervalCount = intervalCount;
		}
	}

	private static final class PeriodInputs {
		private double grid;
		private double interconnect;
		private double selfUse;
		private double rtsExports;
		private double bessCharge;
		private double bessDischarge;
	}

	private record PeriodResult(double k, double rtsToLmv, double bessToLmv) {
	}

	private record MonthlyBreakdown(CalculationPeriod period, PeriodInputs inputs, PeriodResult result) {
	}

	private static final class MonthlySummary {
		private int year;
		private int month;
		private Instant start;
		private Instant end;
		private double bessToLmvKwh;
		private double rtsToLmvKwh;
		private double totalToLmvKwh;
		private double kFactor;
		private int inqualifiedIntervals;
		private final List<MonthlyBreakdown> breakdowns = new ArrayList<>();
	}
}
