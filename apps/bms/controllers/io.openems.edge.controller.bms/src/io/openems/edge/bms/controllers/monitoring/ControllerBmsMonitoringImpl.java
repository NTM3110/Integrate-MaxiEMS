package io.openems.edge.bms.controllers.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import io.openems.common.types.OpenemsType;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.contracts.ActiveCalibrationContextProvider;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.reference.contracts.InitialBaselineReferenceDatasetProvider;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

/** Lifecycle-native monitoring controller for one BMS string. */
@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.Bms.Monitoring", immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerBmsMonitoringImpl extends AbstractOpenemsComponent
		implements OpenemsComponent, Controller, BmsMonitoringSnapshotProvider {
	private static final Logger LOG = LoggerFactory.getLogger(ControllerBmsMonitoringImpl.class);

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		BATTERY_VOLTAGE(Doc.of(OpenemsType.DOUBLE)),
		MIDPOINT_VOLTAGE(Doc.of(OpenemsType.DOUBLE)),
		BATTERY_CURRENT(Doc.of(OpenemsType.DOUBLE)),
		BATTERY_POWER(Doc.of(OpenemsType.DOUBLE)),
		FLOAT_CURRENT(Doc.of(OpenemsType.DOUBLE)),
		MIN_CELL_VOLTAGE(Doc.of(OpenemsType.DOUBLE)),
		MAX_CELL_VOLTAGE(Doc.of(OpenemsType.DOUBLE)),
		AVERAGE_CELL_VOLTAGE(Doc.of(OpenemsType.DOUBLE)),
		MAX_CELL_RESISTANCE(Doc.of(OpenemsType.INTEGER)),
		AVERAGE_CELL_RESISTANCE(Doc.of(OpenemsType.DOUBLE)),
		AMBIENT_TEMPERATURE(Doc.of(OpenemsType.DOUBLE)),
		MAX_CELL_TEMPERATURE(Doc.of(OpenemsType.DOUBLE)),
		AVERAGE_CELL_TEMPERATURE(Doc.of(OpenemsType.DOUBLE)),
		BATTERY_SOC(Doc.of(OpenemsType.DOUBLE)),
		REMAINING_DISCHARGE_TIME(Doc.of(OpenemsType.DOUBLE)),
		BATTERY_SOH(Doc.of(OpenemsType.DOUBLE)),
		BATTERY_SOH_MIN(Doc.of(OpenemsType.DOUBLE)),
		OPERATING_MODE(Doc.of(OpenemsType.STRING)),
		OPERATING_MODE_QUALITY(Doc.of(OpenemsType.STRING)),
		CURRENT_DIRECTION_MAPPING(Doc.of(OpenemsType.STRING)),
		ALARM_MASK(Doc.of(OpenemsType.INTEGER)),
		SOURCE_SEQUENCE(Doc.of(OpenemsType.LONG)),
		SOURCE_GENERATION(Doc.of(OpenemsType.STRING)),
		REFERENCE_EVENT_ID(Doc.of(OpenemsType.LONG)),
		REFERENCE_CURVE_VERSION_ID(Doc.of(OpenemsType.LONG));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	@Reference
	private ComponentManager componentManager;
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile InitialBaselineReferenceDatasetProvider baselineProvider;
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile ActiveCalibrationContextProvider calibrationContextProvider;

	private volatile BmsMonitoringSnapshot latestFrame;
	private final BmsMonitoringPublisher publisher = new BmsMonitoringPublisher();
	private Config config;

	public ControllerBmsMonitoringImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values(), ChannelId.values());
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.apply(config);
	}

	@Modified
	protected void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.apply(config);
	}

	@Deactivate
	protected void deactivate() {
		this.latestFrame = null;
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		BmsCalculationSnapshot snapshot = null;
		try {
			BmsStringCalculator calculator = this.componentManager.getComponent(this.config.calculatorId());
			snapshot = calculator.latestSnapshot();
		} catch (OpenemsNamedException unavailable) {
			// Retain the last measurement only as explicitly stale evidence. Do not
			// leave the old publication looking live when the calculator disappears.
		}
		long nowMillis = System.currentTimeMillis();
		var provider = this.baselineProvider;
		var baseline = provider == null ? null : provider.activeByStringKey(this.config.stringKey()).orElse(null);
		var contextProvider = this.calibrationContextProvider;
		var activeContext = contextProvider == null ? ActiveCalibrationContext.none()
				: contextProvider.activeContext(this.config.stringKey());
		var policy = this.policy();
		var direction = BmsCurrentDirectionMapping.parse(this.config.dischargeCurrentDirection());
		var frame = this.publisher.evaluate(this.config.stringKey(), snapshot, baseline, activeContext,
				policy, direction, nowMillis);
		if (frame == null) {
			this.clearPublishedFrame();
			return;
		}
		if (frame == this.latestFrame) return;
		this.publishChannels(frame);
		this.latestFrame = frame;
		LOG.info("BMS monitoring snapshot published: controller={} stringKey={} sourceSnapshot={}/{} publication={}/{} stale={} qualityWarning={}",
				this.config.id(), frame.stringKey(), frame.snapshotGeneration(), frame.snapshotSequence(),
				frame.publicationGeneration(), frame.publicationRevision(), frame.stale(), frame.qualityWarning());
	}

	@Override
	public String stringKey() {
		return this.config.stringKey();
	}

	@Override
	public BmsMonitoringSnapshot latestFrame() {
		return this.latestFrame;
	}

	@Override
	public int snapshotFreshnessSeconds() {
		return this.config.snapshotFreshnessSeconds();
	}

	private void apply(Config config) {
		if (config.stringKey() == null || config.stringKey().isBlank()) {
			throw new IllegalArgumentException("stringKey is required");
		}
		if (config.calculatorId() == null || config.calculatorId().isBlank()) {
			throw new IllegalArgumentException("calculatorId is required");
		}
		if (config.snapshotFreshnessSeconds() <= 0
				|| config.batteryVoltageLowV() >= config.batteryVoltageHighV()
				|| config.currentDeadbandA() < 0 || config.floatVoltageToleranceV() <= 0
				|| config.modeStableSeconds() < 0) {
			throw new IllegalArgumentException("Invalid BMS monitoring thresholds");
		}
		BmsCurrentDirectionMapping.parse(config.dischargeCurrentDirection());
		this.config = config;
		this.latestFrame = null;
		this.publisher.reset();
	}

	private BmsMonitoringPolicy policy() {
		var defaults = BmsMonitoringPolicy.defaults();
		return new BmsMonitoringPolicy(this.config.snapshotFreshnessSeconds(),
				defaults.cellVoltageDeviationFraction(), this.config.batteryVoltageLowV(),
				this.config.batteryVoltageHighV(), defaults.resistanceBaselineMultiplier(),
				defaults.cellTemperatureAboveAverageC(), defaults.cellTemperatureAboveAmbientC(),
				defaults.floatCurrentBaselineMultiplier(), defaults.chargeOvercurrentCapacityFraction(),
				this.config.lowSocPct(), this.config.highAmbientTemperatureC(), defaults.midpointImbalanceV(),
				this.config.currentDeadbandA(), this.config.floatVoltageToleranceV(), this.config.modeStableSeconds());
	}

	private void publishChannels(BmsMonitoringSnapshot frame) {
		var string = frame.string();
		this.channel(ChannelId.BATTERY_VOLTAGE).setNextValue(string.batteryVoltageV());
		this.channel(ChannelId.MIDPOINT_VOLTAGE).setNextValue(string.midpointVoltageV());
		this.channel(ChannelId.BATTERY_CURRENT).setNextValue(string.batteryCurrentA());
		this.channel(ChannelId.BATTERY_POWER).setNextValue(string.batteryPowerW());
		this.channel(ChannelId.FLOAT_CURRENT).setNextValue(string.floatCurrentA());
		this.channel(ChannelId.MIN_CELL_VOLTAGE).setNextValue(string.minCellVoltageV());
		this.channel(ChannelId.MAX_CELL_VOLTAGE).setNextValue(string.maxCellVoltageV());
		this.channel(ChannelId.AVERAGE_CELL_VOLTAGE).setNextValue(string.averageCellVoltageV());
		this.channel(ChannelId.MAX_CELL_RESISTANCE).setNextValue(string.maxCellResistanceUohm());
		this.channel(ChannelId.AVERAGE_CELL_RESISTANCE).setNextValue(string.averageCellResistanceUohm());
		this.channel(ChannelId.AMBIENT_TEMPERATURE).setNextValue(string.ambientTemperatureC());
		this.channel(ChannelId.MAX_CELL_TEMPERATURE).setNextValue(string.maxCellTemperatureC());
		this.channel(ChannelId.AVERAGE_CELL_TEMPERATURE).setNextValue(string.averageCellTemperatureC());
		this.channel(ChannelId.BATTERY_SOC).setNextValue(string.batterySocPct());
		this.channel(ChannelId.REMAINING_DISCHARGE_TIME).setNextValue(string.remainingDischargeHours());
		this.channel(ChannelId.BATTERY_SOH).setNextValue(string.batterySohPct());
		this.channel(ChannelId.BATTERY_SOH_MIN).setNextValue(string.minimumCellSohPct());
		this.channel(ChannelId.OPERATING_MODE).setNextValue(frame.operatingMode().mode().name());
		this.channel(ChannelId.OPERATING_MODE_QUALITY).setNextValue(frame.operatingMode().quality().name());
		this.channel(ChannelId.CURRENT_DIRECTION_MAPPING).setNextValue(frame.currentDirectionMapping().name());
		this.channel(ChannelId.ALARM_MASK).setNextValue(frame.alarmMask());
		this.channel(ChannelId.SOURCE_SEQUENCE).setNextValue(frame.snapshotSequence());
		this.channel(ChannelId.SOURCE_GENERATION).setNextValue(frame.snapshotGeneration().toString());
		this.channel(ChannelId.REFERENCE_EVENT_ID).setNextValue(frame.reference() == null ? null : frame.reference().calibrationEventId());
		this.channel(ChannelId.REFERENCE_CURVE_VERSION_ID).setNextValue(frame.reference() == null ? null : frame.reference().curveVersionId());
	}

	private void clearPublishedFrame() {
		this.latestFrame = null;
		this.publisher.reset();
		for (var channelId : ChannelId.values()) {
			this.channel(channelId).setNextValue(null);
		}
	}
}
