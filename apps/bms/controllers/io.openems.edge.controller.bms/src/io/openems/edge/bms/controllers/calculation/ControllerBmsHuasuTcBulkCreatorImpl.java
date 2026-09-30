package io.openems.edge.bms.controllers.calculation;

import io.openems.edge.bms.controllers.calculation.legacy.Helper;
import io.openems.edge.bms.controllers.calculation.legacy.SoCEngine;
import io.openems.edge.bms.controllers.calculation.legacy.SoHEngine;
import io.openems.edge.bms.service.calculation.model.EstimatorMode;
import io.openems.edge.bms.controllers.calculation.estimator.PerCellEstimatorState;
import io.openems.edge.bms.controllers.calculation.estimator.PerCellSocEstimator;
import io.openems.edge.bms.controllers.calculation.estimator.PerCellSohResolver;
import io.openems.edge.bms.controllers.calculation.estimator.SocResult;
import io.openems.edge.bms.controllers.calculation.estimator.SohResult;
import io.openems.edge.bms.controllers.calculation.estimator.StringEstimatorAggregation;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bms.drivers.huasu.ta.BmsHuasuTa;
import io.openems.edge.bms.drivers.huasu.tc.BmsHuasuTc;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.calculation.contracts.SocEstimatorCheckpointStore;
import io.openems.edge.bms.controllers.calculation.checkpoint.SocEstimatorCheckpointValidator;
import io.openems.edge.bms.service.reference.contracts.ActiveCalibrationEstimatorContextProvider;
import io.openems.edge.bms.service.reference.contracts.InitialBaselineReferenceDatasetProvider;
import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;
import io.openems.edge.bms.service.strings.model.SocCheckpointPolicy;

/**
 * Calculates one existing Huasu string. TA and TC component configurations are
 * deliberately owned by the BMS core lifecycle service; this controller never
 * creates, replaces, or deletes them.
 */
@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.BmsHuasuTc.BulkCreator", immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerBmsHuasuTcBulkCreatorImpl extends AbstractOpenemsComponent
		implements OpenemsComponent, Controller, BmsStringCalculator {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		;
		@Override
		public Doc doc() {
			return null;
		}
	}

	@Reference
	private ComponentManager componentManager;
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile InitialBaselineReferenceDatasetProvider baselineProvider;
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile ActiveCalibrationEstimatorContextProvider activeEstimatorContextProvider;
	private volatile SocEstimatorCheckpointStore checkpointStore;

	private final Map<Integer, SoCEngine> socEngines = new HashMap<>();
	private final Map<Integer, PerCellEstimatorState> estimatorStates = new HashMap<>();
	private final Map<Integer, Long> floatCandidateSince = new HashMap<>();
	/*
	 * Do not use the TA SoC channel itself as the initialization flag. OpenEMS
	 * channels may start at 0.0, which would skip the voltage based initial SoC
	 * and immediately run the EKF from its default state. MaxiMUC tracks this
	 * independently for every cell, so do the same here.
	 */
	private final Set<Integer> initializedSocCells = ConcurrentHashMap.newKeySet();
	private final Set<Integer> calculatedSocCells = ConcurrentHashMap.newKeySet();
	private final Set<Integer> calculatedSohCells = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Double> calculatedSocValues = new ConcurrentHashMap<>();
	private final Map<Integer, Double> calculatedSohValues = new ConcurrentHashMap<>();
	private final Map<Integer, EstimatorProvenance> calculatedSocProvenance = new ConcurrentHashMap<>();
	private final Map<Integer, EstimatorProvenance> calculatedSohProvenance = new ConcurrentHashMap<>();
	/* Read by BmsStringDataReader to distinguish channel default 0.0 from data
	 * that this controller actually calculated. */
	private volatile boolean hasCalculatedSoc;
	private volatile boolean hasCalculatedSoh;
	private volatile Double calculatedStringSoc;
	private volatile Double calculatedStringSoh;
	private volatile String lastCalculationError;
	private volatile BmsCalculationSnapshot latestSnapshot;
	private volatile int consecutiveSnapshotFailures;
	private long lastSuccessfulCaptureAt;
	private long firstFailedCycleAt;
	private long snapshotSequence;
	private UUID snapshotGeneration;
	private long snapshotGenerationStartedAt;
	private long lastScheduledCalculationAt;
	private String lastEstimatorReferenceIdentity;
	private String lastCheckpointRestoreIdentity;
	private Config config;

	public ControllerBmsHuasuTcBulkCreatorImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values(), ChannelId.values());
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.apply(config);
		this.resetCalculationState();
	}

	void resetCalculationState() {
		this.socEngines.clear();
		this.estimatorStates.clear();
		this.floatCandidateSince.clear();
		this.initializedSocCells.clear();
		this.calculatedSocCells.clear();
		this.calculatedSohCells.clear();
		this.calculatedSocValues.clear();
		this.calculatedSohValues.clear();
		this.calculatedSocProvenance.clear();
		this.calculatedSohProvenance.clear();
		this.hasCalculatedSoc = false;
		this.hasCalculatedSoh = false;
		this.calculatedStringSoc = null;
		this.calculatedStringSoh = null;
		this.lastCalculationError = null;
		this.latestSnapshot = null;
		this.consecutiveSnapshotFailures = 0;
		this.lastSuccessfulCaptureAt = 0;
		this.firstFailedCycleAt = 0;
		this.snapshotSequence = 0;
		this.snapshotGeneration = UUID.randomUUID();
		this.snapshotGenerationStartedAt = System.currentTimeMillis();
		this.lastScheduledCalculationAt = 0;
		this.lastEstimatorReferenceIdentity = null;
		this.lastCheckpointRestoreIdentity = null;
	}

	@Modified
	protected void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.apply(config);
		this.resetCalculationState();
	}

	@Deactivate
	protected void deactivate() {
		this.socEngines.clear();
		this.estimatorStates.clear();
		this.floatCandidateSince.clear();
		this.initializedSocCells.clear();
		this.calculatedSocCells.clear();
		this.calculatedSohCells.clear();
		this.calculatedSocValues.clear();
		this.calculatedSohValues.clear();
		this.calculatedSocProvenance.clear();
		this.calculatedSohProvenance.clear();
		this.hasCalculatedSoc = false;
		this.hasCalculatedSoh = false;
		this.calculatedStringSoc = null;
		this.calculatedStringSoh = null;
		this.lastCalculationError = null;
		this.latestSnapshot = null;
		this.consecutiveSnapshotFailures = 0;
		this.lastSuccessfulCaptureAt = 0;
		this.firstFailedCycleAt = 0;
		this.snapshotSequence = 0;
		this.snapshotGeneration = null;
		this.snapshotGenerationStartedAt = 0;
		this.lastScheduledCalculationAt = 0;
		this.lastEstimatorReferenceIdentity = null;
		this.lastCheckpointRestoreIdentity = null;
		super.deactivate();
	}

	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	protected void bindCheckpointStore(SocEstimatorCheckpointStore store) {
		this.checkpointStore = store;
		var activeConfig = this.config;
		if (activeConfig != null && SocCheckpointPolicy.parse(activeConfig.socCheckpointPolicy())
				== SocCheckpointPolicy.DISABLED) {
			store.clear(activeConfig.stringKey());
		}
	}

	protected void unbindCheckpointStore(SocEstimatorCheckpointStore store) {
		if (this.checkpointStore == store) this.checkpointStore = null;
	}

	private void apply(Config config) {
		EstimatorMode.parse(config.estimatorMode());
		BmsCurrentDirectionMapping.parse(config.dischargeCurrentDirection());
		var checkpointPolicy = SocCheckpointPolicy.parse(config.socCheckpointPolicy());
		if (config.stringKey() == null || config.stringKey().isBlank()
				|| config.estimatorMaximumGapSeconds() <= 0 || config.baselineReferenceFreshnessSeconds() <= 0
				|| config.activeCalibrationContextFreshnessSeconds() <= 0
				|| !Double.isFinite(config.estimatorTimeScale()) || config.estimatorTimeScale() <= 0
				|| config.estimatorFloatCurrentDeadbandA() < 0 || config.estimatorFloatVoltageToleranceV() <= 0
				|| config.estimatorFloatStableSeconds() < 0) {
			throw new IllegalArgumentException("Invalid BMS estimator configuration");
		}
		var previous = this.config;
		this.config = config;
		if (checkpointPolicy == SocCheckpointPolicy.DISABLED) {
			var store = this.checkpointStore;
			if (store != null) store.clear(config.stringKey());
			if (store != null && previous != null && !previous.stringKey().equals(config.stringKey())) {
				store.clear(previous.stringKey());
			}
		}
	}

	@Override
	public void run() throws OpenemsNamedException {
		/*
		 * Older installations used the default stringId="string" instance only to
		 * force controller.bms activation. The API is now a separate immediate
		 * bundle service, so keep that legacy instance idle and avoid cycle-log spam.
		 * Real per-string calculators always use the numeric string index.
		 */
		if (!this.config.stringId().matches("\\d+")) {
			return;
		}
		var now = System.currentTimeMillis();
		if (!shouldRunScheduledCalculation(this.lastScheduledCalculationAt, now,
				this.config.snapshotIntervalSeconds())) {
			return;
		}
		try {
			this.calculateNow();
			this.lastScheduledCalculationAt = now;
		} catch (Exception e) {
			throw new OpenemsNamedException(OpenemsError.GENERIC,
					"Unable to calculate BMS string [" + this.config.stringId() + "]: " + exceptionSummary(e));
		}
	}

	static boolean shouldRunScheduledCalculation(long lastScheduledAt, long now, int intervalSeconds) {
		return lastScheduledAt == 0 || now - lastScheduledAt >= Math.max(1, intervalSeconds) * 1_000L;
	}

	/**
	 * Runs one scheduled calculation. The JSON API never invokes this method; it
	 * reads only the last immutable snapshot published at the end of this method.
	 */
	public synchronized void calculateNow() throws Exception {
		var cycleAt = System.currentTimeMillis();
		try {
			var hasMeasurements = this.calculateExistingString(cycleAt);
			if (hasMeasurements) {
				this.lastCalculationError = null;
				this.consecutiveSnapshotFailures = 0;
			} else {
				this.lastCalculationError = "No usable TA/TC measurement";
				this.consecutiveSnapshotFailures++;
			}
		} catch (Exception e) {
			this.lastCalculationError = exceptionSummary(e);
			this.consecutiveSnapshotFailures++;
			if (this.firstFailedCycleAt == 0) {
				this.firstFailedCycleAt = cycleAt;
			}
			throw e;
		}
	}

	private static String exceptionSummary(Exception exception) {
		var className = exception.getClass().getSimpleName();
		if (className.isBlank()) {
			className = exception.getClass().getName();
		}
		var message = exception.getMessage();
		return message == null || message.isBlank() ? className : className + ": " + message;
	}

	private boolean calculateExistingString(long cycleAt) throws Exception {
		this.hasCalculatedSoc = false;
		this.hasCalculatedSoh = false;
		/* These describe the current calculation cycle, not historic channel data. */
		this.calculatedSocCells.clear();
		this.calculatedSohCells.clear();
		this.calculatedSocValues.clear();
		this.calculatedSohValues.clear();
		this.calculatedSocProvenance.clear();
		this.calculatedSohProvenance.clear();
		this.calculatedStringSoc = null;
		this.calculatedStringSoh = null;
		var tc = this.tc();
		var currentValue = tc.getStringCurrent();
		var rawCurrent = currentValue.get();
		var ambientRaw = tc.getAmbientTemperature().get();
		var tcCommunicationFailed = tc instanceof ModbusComponent modbusComponent
				&& modbusComponent.getModbusCommunicationFailed();
		var previousSnapshot = this.latestSnapshot;
		var currentProvenance = adaptCurrent(currentValue, tcCommunicationFailed, previousSnapshot,
				this.snapshotGenerationStartedAt);
		/*
		 * BmsHuasuTcImpl already converts the Modbus signed-magnitude word to a
		 * signed value in milliamperes. Decode it exactly once in the driver; here
		 * only scale milliamperes to amperes.
		 */
		var hasCurrent = currentProvenance.current() != null
				&& currentProvenance.quality() == BmsCalculationSnapshot.CurrentQuality.FRESH;
		var current = hasCurrent ? currentProvenance.current() : 0d;
		var estimatorMode = EstimatorMode.parse(this.config.estimatorMode());
		var estimatorInputs = estimatorMode.baselineAware()
				? this.estimatorInputs(cycleAt) : EstimatorInputs.empty();
		var domainCurrent = hasCurrent
				? BmsCurrentDirectionMapping.parse(this.config.dischargeCurrentDirection()).toDomainCurrent(current) : null;
		var count = this.config.numberOfCells();
		this.prepareEstimatorState(estimatorInputs, count, currentProvenance.measuredAt());
		var voltages = new Double[count];
		var temperatures = new Double[count];
		var resistances = new Double[count];
		var socs = new Double[count];
		var sohs = new Double[count];
		var irs = new Double[count];
		var availabilityWarnings = new String[count];
		/*
		 * A TC ambient/current value alone is not a successful BMS interval: if all
		 * TA modules fail, refreshing the snapshot timestamp from TC would keep
		 * stale cell values live forever. A cycle is successful only when at least
		 * one TA channel supplied data; a single missing TA therefore remains a
		 * partial interval and does not count as a whole-interval failure.
		 */
		var hasTaMeasurements = false;
		var calculatedSocCount = 0;
		var calculatedSohCount = 0;

		for (var offset = 0; offset < count; offset++) {
			var cell = offset + 1;
			BmsHuasuTa ta;
			try {
				ta = this.ta(cell);
			} catch (OpenemsNamedException missingCell) {
				// One missing TA must not prevent the remaining cells from being
				// calculated and included in the string aggregate.
				availabilityWarnings[offset] = "MISSING_COMPONENT";
				copyPreviousCell(previousSnapshot, cell, offset, voltages, temperatures, resistances, socs, sohs, irs);
				continue;
			}
			if (ta instanceof ModbusComponent modbusComponent && modbusComponent.getModbusCommunicationFailed()) {
				availabilityWarnings[offset] = "MODBUS_COMMUNICATION_FAILED";
				copyPreviousCell(previousSnapshot, cell, offset, voltages, temperatures, resistances, socs, sohs, irs);
				continue;
			}
			var voltageRaw = ta.getBatteryVoltage().get();
			var temperatureRaw = ta.getBatteryTemperature().get();
			var resistanceRaw = ta.getBatteryInternalResistance().get();
			if (voltageRaw == null && temperatureRaw == null && resistanceRaw == null) {
				availabilityWarnings[offset] = "DATA_UNAVAILABLE";
				copyPreviousCell(previousSnapshot, cell, offset, voltages, temperatures, resistances, socs, sohs, irs);
				continue;
			}
			hasTaMeasurements = true;
			if (voltageRaw == null || temperatureRaw == null || resistanceRaw == null) {
				availabilityWarnings[offset] = "PARTIAL_DATA";
			}
			socs[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::soc);
			sohs[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::soh);

			if (voltageRaw != null) {
				voltages[offset] = voltageRaw / 1000d;
			} else {
				voltages[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::voltage);
			}
			if (temperatureRaw != null) {
				temperatures[offset] = temperatureRaw / 10d;
			} else {
				temperatures[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::temperature);
			}
			if (resistanceRaw != null) {
				resistances[offset] = resistanceRaw.doubleValue();
			} else {
				resistances[offset] = previousCell(previousSnapshot, cell, value -> value.internalResistance() == null
						? null : value.internalResistance().doubleValue());
			}
			Double sohPercent;
			EstimatorProvenance sohProvenance;
			if (estimatorMode.baselineAware()) {
				var result = PerCellSohResolver.resolve(cell, this.config.cellCapacityAh(),
						this.config.ratedResistanceUohm(), resistances[offset], estimatorInputs.baseline(),
						estimatorInputs.active());
				sohPercent = result.valuePct();
				sohProvenance = provenance(result, estimatorInputs);
			} else {
				sohPercent = resistanceRaw == null ? null
						: SoHEngine.updatedSoHRegular(resistances[offset], this.config.ratedResistanceUohm()) * 100d;
				sohProvenance = legacyProvenance("LEGACY_RESISTANCE", sohPercent,
						"Legacy resistance-based SoH estimator");
			}
			this.calculatedSohProvenance.put(cell, sohProvenance);
			if (sohPercent != null && Double.isFinite(sohPercent)) {
				ta._setBatterySoH(sohPercent);
				sohs[offset] = sohPercent;
				this.calculatedSohCells.add(cell);
				this.calculatedSohValues.put(cell, sohPercent);
				calculatedSohCount++;
			} else {
				sohs[offset] = estimatorMode.baselineAware() ? null
						: previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::soh);
			}

			/*
			 * A valid voltage alone gives a useful voltage-based SoC. Current and
			 * temperature improve it through the EKF, but neither may prevent a cell
			 * with valid voltage from being reported. This also keeps a missing TC
			 * current from invalidating an otherwise healthy string.
			 */
			var estimator = this.socEngines.computeIfAbsent(cell, ignored -> new SoCEngine(
					this.config.cellCapacityAh(), this.config.cellCutoffVoltage(), this.config.cellFloatVoltage()));
			if (estimatorMode.baselineAware()) {
				Double voltageFallback = voltageRaw != null && voltages[offset] != null
						&& estimator.isUsableVoltage(voltages[offset])
								? estimator.initialSoCFromVoltage(voltages[offset]) * 100d : null;
				var capacity = estimatorInputs.capacityAh(cell);
				var stableFloat = this.stableFloat(cell, voltages[offset], domainCurrent,
						currentProvenance.measuredAt(), hasCurrent, capacity != null);
				var result = PerCellSocEstimator.update(this.estimatorStates.get(cell),
						estimatorInputs.referenceIdentity(), capacity, domainCurrent,
						currentProvenance.measuredAt(), hasCurrent, voltageFallback, stableFloat,
						this.config.estimatorTimeScale(),
						this.config.estimatorMaximumGapSeconds() * 1_000L);
				var socPercent = result.valuePct();
				this.calculatedSocProvenance.put(cell, provenance(result, estimatorInputs));
				if (result.state() == null) this.estimatorStates.remove(cell);
				else this.estimatorStates.put(cell, result.state());
				if (socPercent != null && Double.isFinite(socPercent)) {
					ta._setBatterySoC(socPercent);
					socs[offset] = socPercent;
					this.initializedSocCells.add(cell);
					this.calculatedSocCells.add(cell);
					this.calculatedSocValues.put(cell, socPercent);
					calculatedSocCount++;
				} else {
					socs[offset] = null;
				}
			} else if (voltageRaw != null && voltages[offset] != null && estimator.isUsableVoltage(voltages[offset])) {
				var useEkf = this.initializedSocCells.contains(cell) && hasCurrent
						&& temperatures[offset] != null
						&& estimator.isUsableMeasurement(voltages[offset], current, temperatures[offset]);
				var socPercent = useEkf
						? estimator.updatedSoCEKF(voltages[offset], current, temperatures[offset], 1d) * 100d
						: estimator.initialSoCFromVoltage(voltages[offset]) * 100d;
				this.calculatedSocProvenance.put(cell, legacyProvenance(
						useEkf ? "LEGACY_EKF" : "LEGACY_VOLTAGE", socPercent, "Legacy SoC estimator"));
				ta._setBatterySoC(socPercent);
				socs[offset] = socPercent;
				this.initializedSocCells.add(cell);
				this.calculatedSocCells.add(cell);
				this.calculatedSocValues.put(cell, socPercent);
				calculatedSocCount++;
			} else if (voltageRaw == null || voltages[offset] == null) {
				socs[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::soc);
			} else if (availabilityWarnings[offset] == null) {
				availabilityWarnings[offset] = "INVALID_DATA";
			}
			if (hasCurrent && resistances[offset] != null) {
				irs[offset] = resistances[offset] * current * 10d / 100_000d;
			} else {
				irs[offset] = previousCell(previousSnapshot, cell, BmsCalculationSnapshot.Cell::ir);
			}
		}
		this.hasCalculatedSoc = calculatedSocCount > 0;
		this.hasCalculatedSoh = calculatedSohCount > 0;
		this.calculatedStringSoc = aggregateStringEstimate(socs, count, estimatorMode.baselineAware(),
				previousSnapshot == null ? null : previousSnapshot.stringSoc(), true);
		this.calculatedStringSoh = aggregateStringEstimate(sohs, count, estimatorMode.baselineAware(),
				previousSnapshot == null ? null : previousSnapshot.stringSoh(), false);
		if (hasTaMeasurements) {
			this.writeCalculatedTcChannels(tc, count, voltages, temperatures, resistances, socs, sohs);
		}
		// Publish all configured cell slots even if one or every device failed to
		// provide values. Missing device data is represented only on that cell.
		var stringWarning = tcCommunicationFailed
				? "MODBUS_COMMUNICATION_FAILED" : null;
		if (hasTaMeasurements || hasWarning(availabilityWarnings) || stringWarning != null) {
			long capturedAt;
			if (hasTaMeasurements) {
				capturedAt = cycleAt;
				this.lastSuccessfulCaptureAt = capturedAt;
				this.firstFailedCycleAt = 0;
			} else {
				if (this.firstFailedCycleAt == 0) {
					this.firstFailedCycleAt = cycleAt;
				}
				capturedAt = this.lastSuccessfulCaptureAt != 0 ? this.lastSuccessfulCaptureAt : this.firstFailedCycleAt;
			}
			this.publishSnapshot(previousSnapshot, currentProvenance, ambientRaw, count, voltages, temperatures,
					resistances, socs, sohs, irs, availabilityWarnings, stringWarning, capturedAt, estimatorInputs);
		}
		return hasTaMeasurements;
	}

	/** Returns the last fully completed generation through one volatile read. */
	@Override
	public BmsCalculationSnapshot latestSnapshot() {
		return this.latestSnapshot;
	}

	/** True only after at least one valid cell SoC calculation in the latest cycle. */
	public boolean hasCalculatedSoc() {
		return this.hasCalculatedSoc;
	}

	/** True only when this cell had a valid SoC calculation in the latest cycle. */
	public boolean hasCalculatedSoc(int cell) {
		return this.calculatedSocCells.contains(cell);
	}

	/** True only after at least one valid cell resistance-based SoH calculation in the latest cycle. */
	public boolean hasCalculatedSoh() {
		return this.hasCalculatedSoh;
	}

	/** True only when this cell had a valid resistance-based SoH calculation in the latest cycle. */
	public boolean hasCalculatedSoh(int cell) {
		return this.calculatedSohCells.contains(cell);
	}

	public int calculatedSocCellCount() {
		return this.calculatedSocCells.size();
	}

	public int calculatedSohCellCount() {
		return this.calculatedSohCells.size();
	}

	public Double calculatedSoc(int cell) {
		return this.calculatedSocValues.get(cell);
	}

	public Double calculatedSoh(int cell) {
		return this.calculatedSohValues.get(cell);
	}

	public Double calculatedStringSoc() {
		return this.calculatedStringSoc;
	}

	public Double calculatedStringSoh() {
		return this.calculatedStringSoh;
	}

	public String lastCalculationError() {
		return this.lastCalculationError;
	}

	public double cellCutoffVoltage() {
		return this.config.cellCutoffVoltage();
	}

	public double cellFloatVoltage() {
		return this.config.cellFloatVoltage();
	}

	public int ratedResistanceUohm() {
		return this.config.ratedResistanceUohm();
	}

	@Override
	public int consecutiveSnapshotFailures() {
		return this.consecutiveSnapshotFailures;
	}

	private EstimatorInputs estimatorInputs(long nowMillis) {
		InitialBaselineReferenceDataset baseline = null;
		var completedProvider = this.baselineProvider;
		if (completedProvider != null) {
			var providerSnapshot = completedProvider.snapshot();
			if (providerSnapshot != null && fresh(providerSnapshot.lastSuccessfulRefreshAt(), nowMillis,
					this.config.baselineReferenceFreshnessSeconds())) {
				baseline = providerSnapshot.byStringKey().get(this.config.stringKey());
			}
		}

		ActiveCalibrationEstimatorContext active = null;
		var activeProvider = this.activeEstimatorContextProvider;
		if (activeProvider != null) {
			var candidate = activeProvider.activeByStringKey(this.config.stringKey()).orElse(null);
			if (candidate != null && candidate.initialBaselineDischarge()
					&& fresh(candidate.publishedAt(), nowMillis,
							this.config.activeCalibrationContextFreshnessSeconds())) {
				active = candidate;
			}
		}
		var identity = referenceIdentity(baseline);
		return new EstimatorInputs(baseline, active, identity);
	}

	private void prepareEstimatorState(EstimatorInputs inputs, int cellCount, Long currentMeasuredAt) {
		var referenceIdentity = inputs.referenceIdentity();
		if (java.util.Objects.equals(referenceIdentity, this.lastEstimatorReferenceIdentity)) {
			if (this.estimatorStates.isEmpty()) {
				this.restoreEstimatorState(referenceIdentity, cellCount, currentMeasuredAt);
			}
			return;
		}
		this.estimatorStates.clear();
		this.floatCandidateSince.clear();
		this.lastEstimatorReferenceIdentity = referenceIdentity;
		this.lastCheckpointRestoreIdentity = null;
		this.restoreEstimatorState(referenceIdentity, cellCount, currentMeasuredAt);
	}

	private void restoreEstimatorState(String referenceIdentity, int cellCount, Long currentMeasuredAt) {
		if (SocCheckpointPolicy.parse(this.config.socCheckpointPolicy()) != SocCheckpointPolicy.PERSIST_DATABASE
				|| referenceIdentity == null || currentMeasuredAt == null
				|| referenceIdentity.equals(this.lastCheckpointRestoreIdentity)) return;
		var store = this.checkpointStore;
		if (store == null) return;
		this.lastCheckpointRestoreIdentity = referenceIdentity;
		store.latest(this.config.stringKey())
				.flatMap(value -> SocEstimatorCheckpointValidator.restore(value, this.config.stringKey(), cellCount,
						referenceIdentity, currentMeasuredAt, this.config.estimatorMaximumGapSeconds() * 1_000L))
				.ifPresent(this.estimatorStates::putAll);
	}

	private boolean stableFloat(int cell, Double voltage, Double domainCurrent, Long currentMeasuredAt,
			boolean currentFresh, boolean hasCapacityReference) {
		if (!hasCapacityReference || !currentFresh || domainCurrent == null || !Double.isFinite(domainCurrent)
				|| currentMeasuredAt == null || voltage == null || !Double.isFinite(voltage)
				|| Math.abs(domainCurrent) > this.config.estimatorFloatCurrentDeadbandA()
				|| Math.abs(voltage - this.config.cellFloatVoltage()) > this.config.estimatorFloatVoltageToleranceV()) {
			this.floatCandidateSince.remove(cell);
			return false;
		}
		var startedAt = this.floatCandidateSince.computeIfAbsent(cell, ignored -> currentMeasuredAt);
		return currentMeasuredAt - startedAt >= this.config.estimatorFloatStableSeconds() * 1_000L;
	}

	static boolean fresh(Instant timestamp, long nowMillis, int freshnessSeconds) {
		if (timestamp == null) return false;
		return fresh(timestamp.toEpochMilli(), nowMillis, freshnessSeconds);
	}

	static boolean fresh(long timestamp, long nowMillis, int freshnessSeconds) {
		return freshnessSeconds > 0 && timestamp > 0 && timestamp <= nowMillis
				&& nowMillis - timestamp <= freshnessSeconds * 1_000L;
	}

	static String referenceIdentity(InitialBaselineReferenceDataset baseline) {
		if (baseline == null || baseline.curve() == null || baseline.curve().contentHash() == null
				|| baseline.curve().contentHash().isBlank()) return null;
		return baseline.calibrationEventId() + ":" + baseline.curve().id() + ":"
				+ baseline.curve().calibrationProcessId() + ":" + baseline.curve().processAttemptNumber() + ":"
				+ baseline.curve().contentHash();
	}

	private record EstimatorInputs(InitialBaselineReferenceDataset baseline,
			ActiveCalibrationEstimatorContext active, String referenceIdentity) {

		private static EstimatorInputs empty() {
			return new EstimatorInputs(null, null, null);
		}

		private Double capacityAh(int cellIndex) {
			if (this.baseline == null) return null;
			var reference = this.baseline.cellCapacityReferences().get(cellIndex);
			return reference == null ? null : reference.measuredCellCapacityAh();
		}
	}

	private static EstimatorProvenance provenance(SocResult result, EstimatorInputs inputs) {
		var state = inputs.baseline() != null ? "PUBLISHED_BASELINE"
				: result.quality() == SocResult.Quality.UNAVAILABLE ? "UNAVAILABLE" : "NO_REFERENCE";
		return new EstimatorProvenance(result.method().name(), state,
				EstimatorQuality.valueOf(result.quality().name()), result.note(), reference(inputs));
	}

	private static EstimatorProvenance provenance(SohResult result, EstimatorInputs inputs) {
		var state = switch (result.method()) {
		case AUTHORITATIVE_BASELINE -> "PUBLISHED_BASELINE";
		case IN_PROGRESS, IN_PROGRESS_CAPACITY -> "ACTIVE_INITIAL_BASELINE";
		case RESISTANCE_ESTIMATE -> "NO_REFERENCE";
		case UNAVAILABLE -> "UNAVAILABLE";
		};
		return new EstimatorProvenance(result.method().name(), state,
				EstimatorQuality.valueOf(result.quality().name()), result.note(), reference(inputs));
	}

	private static EstimatorProvenance legacyProvenance(String method, Double value, String note) {
		return new EstimatorProvenance(value == null ? "UNAVAILABLE" : method,
				value == null ? "UNAVAILABLE" : "LEGACY",
				value == null ? EstimatorQuality.UNAVAILABLE : EstimatorQuality.ESTIMATED, note, null);
	}

	private static EstimatorReferenceIdentity reference(EstimatorInputs inputs) {
		if (inputs.baseline() != null) {
			var baseline = inputs.baseline();
			return new EstimatorReferenceIdentity(baseline.calibrationEventId(), baseline.curve().id(),
					baseline.curve().calibrationProcessId(), baseline.curve().processAttemptNumber(),
					baseline.curve().contentHash());
		}
		if (inputs.active() != null) {
			var active = inputs.active();
			return new EstimatorReferenceIdentity(active.calibrationEventId(), null,
					active.calibrationProcessId(), active.processAttemptNumber(), null);
		}
		return null;
	}

	static EstimatorProvenance aggregateProvenance(Double aggregate,
			Map<Integer, BmsCalculationSnapshot.Cell> cells, boolean soc) {
		if (aggregate == null) return null;
		for (var cell : cells.values()) {
			var value = soc ? cell.soc() : cell.soh();
			if (value != null && Double.compare(value, aggregate) == 0) {
				return soc ? cell.socEstimator() : cell.sohEstimator();
			}
		}
		return null;
	}

	private void publishSnapshot(BmsCalculationSnapshot previousSnapshot, CurrentProvenance currentProvenance,
			Integer ambientRaw, int count,
			Double[] voltages,
			Double[] temperatures, Double[] resistances, Double[] socs, Double[] sohs, Double[] irs,
			String[] availabilityWarnings, String stringWarning, long capturedAt, EstimatorInputs estimatorInputs) {
		var cells = new LinkedHashMap<Integer, BmsCalculationSnapshot.Cell>(count);
		for (var offset = 0; offset < count; offset++) {
			var resistance = resistances[offset] == null ? null : resistances[offset].intValue();
			var cellId = offset + 1;
			var previous = previousCell(previousSnapshot, cellId);
			cells.put(cellId, new BmsCalculationSnapshot.Cell(cellId, voltages[offset], temperatures[offset], resistance,
					socs[offset], sohs[offset], irs[offset], availabilityWarnings[offset],
					this.calculatedSocProvenance.getOrDefault(cellId,
							previous == null ? null : previous.socEstimator()),
					this.calculatedSohProvenance.getOrDefault(cellId,
							previous == null ? null : previous.sohEstimator())));
		}

		var maxVoltage = Helper.getMaxVoltageBattery(count, voltages);
		var minVoltage = Helper.getMinVoltageBattery(count, voltages);
		var maxTemperature = Helper.getMaxTemperatureBattery(count, temperatures);
		var minTemperature = Helper.getMinTemperatureBattery(count, temperatures);
		var maxResistance = Helper.getMaxResistanceBattery(count, resistances);
		var minResistance = Helper.getMinResistanceBattery(count, resistances);
		var ambientTemperature = ambientTemperature(ambientRaw, previousSnapshot);
		var baselineEstimator = EstimatorMode.parse(this.config.estimatorMode()).baselineAware();
		var complete = new BmsCalculationSnapshot(this.snapshotGeneration, ++this.snapshotSequence, capturedAt, count,
				ambientTemperature,
				currentProvenance.current(), currentProvenance.measuredAt(), currentProvenance.quality(),
				currentProvenance.warning(),
				this.calculatedStringSoc, this.calculatedStringSoh,
				hasValue(voltages) ? Helper.calculateStringVoltage(count, voltages) : null,
				midpointVoltage(count, voltages),
				hasPositiveValue(voltages) ? finite(Helper.getAverageVoltageBattery(count, voltages)) : null,
				hasPositiveValue(temperatures) ? finite(Helper.getAverageTemperatureBattery(count, temperatures)) : null,
				hasPositiveValue(resistances) ? finite(Helper.getAverageResistanceBattery(count, resistances)) : null,
				value(maxVoltage), index(maxVoltage), value(minVoltage), index(minVoltage),
				value(maxTemperature), index(maxTemperature), value(minTemperature), index(minTemperature),
				integerValue(maxResistance), index(maxResistance), integerValue(minResistance), index(minResistance), cells,
				stringWarning,
				baselineEstimator ? aggregateProvenance(this.calculatedStringSoc, cells, true)
						: legacyProvenance("LEGACY_AGGREGATE", this.calculatedStringSoc, "Legacy string SoC aggregate"),
				baselineEstimator ? aggregateProvenance(this.calculatedStringSoh, cells, false)
						: legacyProvenance("LEGACY_AGGREGATE", this.calculatedStringSoh, "Legacy string SoH aggregate"));

		// This is the only publication point. Readers see the previous complete
		// generation or this complete generation, never the local work in progress.
		this.latestSnapshot = complete;
		this.publishCheckpoint(complete, estimatorInputs);
	}

	private void publishCheckpoint(BmsCalculationSnapshot snapshot, EstimatorInputs inputs) {
		if (SocCheckpointPolicy.parse(this.config.socCheckpointPolicy()) != SocCheckpointPolicy.PERSIST_DATABASE
				|| inputs.baseline() == null || snapshot.currentMeasuredAt() == null
				|| this.estimatorStates.size() != snapshot.configuredCellCount()) return;
		var store = this.checkpointStore;
		var reference = reference(inputs);
		if (store == null || reference == null) return;
		var cells = new LinkedHashMap<Integer, SocEstimatorCheckpoint.CellState>(snapshot.configuredCellCount());
		for (var index = 1; index <= snapshot.configuredCellCount(); index++) {
			var state = this.estimatorStates.get(index);
			if (state == null) return;
			cells.put(index, new SocEstimatorCheckpoint.CellState(state.socPct(), state.currentMeasuredAt(),
					state.referenceIdentity()));
		}
		store.publish(new SocEstimatorCheckpoint(SocEstimatorCheckpoint.CURRENT_SCHEMA_VERSION,
				this.config.stringKey(), snapshot.configuredCellCount(), snapshot.generation(),
				snapshot.sequence(), snapshot.currentMeasuredAt(), reference, cells));
	}

	static CurrentProvenance adaptCurrent(Value<Integer> value, boolean communicationFailed,
			BmsCalculationSnapshot previousSnapshot, long generationStartedAt) {
		var rawCurrent = value == null ? null : value.get();
		var sourceMeasuredAt = rawCurrent == null || value.getTimestamp() == null ? null
				: value.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		var current = rawCurrent == null ? null : rawCurrent / 1000d;

		if (communicationFailed) {
			return retainCurrent(current, sourceMeasuredAt, previousSnapshot,
					BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED, "MODBUS_COMMUNICATION_FAILED");
		}
		if (rawCurrent == null) {
			if (previousSnapshot != null && previousSnapshot.stringCurrent() != null) {
				return new CurrentProvenance(previousSnapshot.stringCurrent(), previousSnapshot.currentMeasuredAt(),
						BmsCalculationSnapshot.CurrentQuality.MISSING, "CURRENT_DATA_UNAVAILABLE");
			}
			return new CurrentProvenance(null, null, BmsCalculationSnapshot.CurrentQuality.MISSING,
					"CURRENT_DATA_UNAVAILABLE");
		}
		if (sourceMeasuredAt == null) {
			return new CurrentProvenance(current, null, BmsCalculationSnapshot.CurrentQuality.TIMESTAMP_MISSING,
					"CURRENT_TIMESTAMP_MISSING");
		}
		if (sourceMeasuredAt <= generationStartedAt
				|| previousSnapshot != null && previousSnapshot.currentMeasuredAt() != null
						&& sourceMeasuredAt <= previousSnapshot.currentMeasuredAt()) {
			return new CurrentProvenance(current, sourceMeasuredAt, BmsCalculationSnapshot.CurrentQuality.RETAINED,
					"CURRENT_VALUE_RETAINED");
		}
		return new CurrentProvenance(current, sourceMeasuredAt, BmsCalculationSnapshot.CurrentQuality.FRESH, null);
	}

	private static CurrentProvenance retainCurrent(Double current, Long measuredAt,
			BmsCalculationSnapshot previousSnapshot, BmsCalculationSnapshot.CurrentQuality quality, String warning) {
		if (current != null) {
			return new CurrentProvenance(current, measuredAt, quality, warning);
		}
		return new CurrentProvenance(previousSnapshot == null ? null : previousSnapshot.stringCurrent(),
				previousSnapshot == null ? null : previousSnapshot.currentMeasuredAt(), quality, warning);
	}

	static record CurrentProvenance(Double current, Long measuredAt, BmsCalculationSnapshot.CurrentQuality quality,
			String warning) {
	}

	private static Double finite(double value) {
		return Double.isFinite(value) ? value : null;
	}

	private static boolean hasValue(Double[] values) {
		for (var value : values) {
			if (value != null && Double.isFinite(value)) return true;
		}
		return false;
	}

	private static boolean hasPositiveValue(Double[] values) {
		for (var value : values) {
			if (value != null && Double.isFinite(value) && value > 0) return true;
		}
		return false;
	}

	static Double midpointVoltage(int configuredCellCount, Double[] voltages) {
		var midpointCellCount = Math.floorDiv(configuredCellCount, 2);
		if (midpointCellCount < 1 || voltages == null || voltages.length < midpointCellCount) {
			return null;
		}
		double sum = 0.0;
		for (var offset = 0; offset < midpointCellCount; offset++) {
			var voltage = voltages[offset];
			if (voltage == null || !Double.isFinite(voltage)) {
				return null;
			}
			sum += voltage;
			if (!Double.isFinite(sum)) {
				return null;
			}
		}
		return sum;
	}

	static Double ambientTemperature(Integer ambientRaw, BmsCalculationSnapshot previousSnapshot) {
		if (ambientRaw != null) {
			return ambientRaw / 10d;
		}
		return previousSnapshot == null ? null : previousSnapshot.ambientTemperature();
	}

	static Double aggregateStringEstimate(Double[] values, int configuredCellCount, boolean baselineAware,
			Double previousValue, boolean soc) {
		if (hasValue(values)) {
			if (baselineAware) {
				return StringEstimatorAggregation.minimumValid(values);
			}
			return soc ? Helper.calculateStringSOC(configuredCellCount, values)
					: Helper.calculateStringSOH(configuredCellCount, values);
		}
		return baselineAware ? null : previousValue;
	}

	private static Double value(Helper.Result result) {
		return result.index > 0 && Double.isFinite(result.value) ? result.value : null;
	}

	private static Integer integerValue(Helper.Result result) {
		return result.index > 0 && Double.isFinite(result.value) ? (int) result.value : null;
	}

	private static Integer index(Helper.Result result) {
		return result.index > 0 ? result.index : null;
	}

	private static boolean hasWarning(String[] warnings) {
		for (var warning : warnings) {
			if (warning != null) {
				return true;
			}
		}
		return false;
	}

	private static BmsCalculationSnapshot.Cell previousCell(BmsCalculationSnapshot snapshot, int cellId) {
		return snapshot == null ? null : snapshot.cells().get(cellId);
	}

	private static <T> T previousCell(BmsCalculationSnapshot snapshot, int cellId,
			Function<BmsCalculationSnapshot.Cell, T> value) {
		var cell = previousCell(snapshot, cellId);
		return cell == null ? null : value.apply(cell);
	}

	private static void copyPreviousCell(BmsCalculationSnapshot snapshot, int cellId, int offset, Double[] voltages,
			Double[] temperatures, Double[] resistances, Double[] socs, Double[] sohs, Double[] irs) {
		var cell = previousCell(snapshot, cellId);
		if (cell == null) {
			return;
		}
		voltages[offset] = cell.voltage();
		temperatures[offset] = cell.temperature();
		resistances[offset] = cell.internalResistance() == null ? null : cell.internalResistance().doubleValue();
		socs[offset] = cell.soc();
		sohs[offset] = cell.soh();
		irs[offset] = cell.ir();
	}

	private void writeCalculatedTcChannels(BmsHuasuTc tc, int count, Double[] voltages, Double[] temperatures,
			Double[] resistances, Double[] socs, Double[] sohs) {
		var maxVoltage = Helper.getMaxVoltageBattery(count, voltages);
		var minVoltage = Helper.getMinVoltageBattery(count, voltages);
		var maxTemperature = Helper.getMaxTemperatureBattery(count, temperatures);
		var minTemperature = Helper.getMinTemperatureBattery(count, temperatures);
		var maxResistance = Helper.getMaxResistanceBattery(count, resistances);
		var minResistance = Helper.getMinResistanceBattery(count, resistances);
		if (this.calculatedStringSoc != null) tc._setStringSoC(this.calculatedStringSoc);
		if (this.calculatedStringSoh != null) tc._setStringSoH(this.calculatedStringSoh);
		tc._setStringVoltage(Helper.calculateStringVoltage(count, voltages));
		tc._setAvgVoltage(Helper.getAverageVoltageBattery(count, voltages));
		tc._setAvgTemperature(Helper.getAverageTemperatureBattery(count, temperatures));
		tc._setAvgResistance(Helper.getAverageResistanceBattery(count, resistances));
		tc._setMaxVoltage(maxVoltage.value);
		tc._setMaxVoltageCellIndex(maxVoltage.index);
		tc._setMinVoltage(minVoltage.value);
		tc._setMinVoltageCellIndex(minVoltage.index);
		tc._setMaxTemperature(maxTemperature.value);
		tc._setMaxTemperatureCellIndex(maxTemperature.index);
		tc._setMinTemperature(minTemperature.value);
		tc._setMinTemperatureCellIndex(minTemperature.index);
		tc._setMaxResistance((int) maxResistance.value);
		tc._setMaxResistanceCellIndex(maxResistance.index);
		tc._setMinResistance((int) minResistance.value);
		tc._setMinResistanceCellIndex(minResistance.index);
	}

	private BmsHuasuTc tc() throws OpenemsNamedException {
		return this.componentManager.getComponent("bmsHuasuTc-str" + this.config.stringId());
	}

	private BmsHuasuTa ta(int cell) throws OpenemsNamedException {
		return this.componentManager.getComponent("bmsHuasuTa-str" + this.config.stringId() + "-cell" + cell);
	}

}
