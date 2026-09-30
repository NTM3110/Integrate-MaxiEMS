package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;

import io.openems.edge.bms.service.calibration.contracts.CalibrationEventOperations;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CreateCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CompleteProcessCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfirmDischargeStoppedCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDemoTimeScaleCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeSafetyFloorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeMonitorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureFloatStablePolicyCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveFloatCurrentRangeCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveVocDeadbandCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeletedEvent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventHistory;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePointPage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventSummary;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.RedoStageCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Sample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand;
import io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition;
import io.openems.edge.bms.service.strings.implement.persistence.StringManagementService;

/** OSGi command/query boundary for Calibration Event records. */
@Component(name = "io.openems.edge.bms.platform.calibration.CalibrationEventService", service = { CalibrationEventService.class, CalibrationEventOperations.class }, immediate = true)
public class CalibrationEventService implements CalibrationEventOperations {

	private final Logger log = LoggerFactory.getLogger(CalibrationEventService.class);

	@Reference
	private CalibrationEventStore store;
	@Reference
	private StringManagementService strings;

	@Activate
	protected void activate() {
		try {
			var count = this.store.requirePhysicalStageRevalidation();
			if (count > 0) this.log.warn("Marked {} active calibration physical stage(s) for operator revalidation", count);
		} catch (SQLException e) {
			this.log.error("Unable to mark active calibration stages for restart revalidation", e);
		}
	}

	public EventDetail create(CreateCommand command) throws SQLException {
		requireOperator(command.actor());
		this.strings.reconcile();
		return this.createFromSynchronizedString(command);
	}

	protected EventDetail createFromSynchronizedString(CreateCommand command) throws SQLException {
		if (command.stringId() == null) throw new IllegalArgumentException("stringId is required");
		if (command.configuration() == null) throw new IllegalArgumentException("configuration is required");
		if (command.configuration().eventType() == CalibrationEventType.PERIODIC_DISCHARGE
				&& command.referenceCalibrationEventId() == null) {
			throw new IllegalArgumentException("Periodic Discharge requires an approved Initial Baseline reference");
		}
		var definition = this.strings.persistentDefinitions().stream()
				.filter(value -> value.stringKey().equals(command.configuration().stringKey())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Configuration must use an established string"));
		if (!definition.id().equals(command.stringId())) throw new IllegalArgumentException("stringId does not match stringKey");
		validateConfigurationSnapshot(command.configuration(), definition);
		return withActions(this.store.create(command));
	}

	/** Builds the immutable topology portion from the established server-side string. */
	public EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal)
			throws SQLException {
		return create(stringIndex, eventType, currentDirection, floatCoefficient, boostCoefficient, dischargeMethod,
				endpointMode, targetDodPercent, referenceEventId, principal,
				CalibrationEventConfiguration.DEFAULT_FLOAT_STABLE_DURATION_MINUTES,
				CalibrationEventConfiguration.DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V,
				CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MIN_A,
				CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MAX_A, null, null);
	}

	public EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA, Double chargeCurrentLimitA)
			throws SQLException {
		return create(stringIndex, eventType, currentDirection, floatCoefficient, boostCoefficient, dischargeMethod,
				endpointMode, targetDodPercent, referenceEventId, principal, floatStableDurationMinutes,
				floatVoltageToleranceV, floatCurrentMinA, floatCurrentMaxA, chargeCurrentLimitA, null);
	}

	public EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA, Double chargeCurrentLimitA,
			Double safetyFloorVoltagePerCell)
			throws SQLException {
		requireOperator(principal);
		this.strings.reconcile();
		var definition = definition(stringIndex);
		var cutoffVoltagePerCell = eventType == CalibrationEventType.INITIAL_BASELINE
				? CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(dischargeMethod)
				: definition.cutoffVoltage();
		var configuration = new CalibrationEventConfiguration(eventType, definition.stringKey(),
				definition.cellCount(), definition.capacityAh(), definition.capacityAh(), null,
				cutoffVoltagePerCell, definition.floatVoltage(), definition.boostVoltage(), currentDirection,
				CalibrationEventConfiguration.CURRENT_MAPPING_VERSION, floatCoefficient, boostCoefficient,
				CalibrationEventConfiguration.CURRENT_DEADBAND_A,
				CalibrationEventConfiguration.SAMPLE_INTERVAL_SECONDS,
				CalibrationEventConfiguration.SNAPSHOT_FRESHNESS_SECONDS, dischargeMethod, endpointMode,
				targetDodPercent, CalibrationEventConfiguration.INITIAL_FLOAT_CURRENT_LIMIT_A,
				CalibrationEventConfiguration.PERIODIC_FLOAT_CURRENT_LIMIT_A,
				CalibrationEventConfiguration.FLOAT_OVERCURRENT_REFERENCE_A,
				CalibrationEventConfiguration.DISCHARGE_VOLTAGE_FLOOR_POLICY, floatStableDurationMinutes,
				floatVoltageToleranceV, floatCurrentMinA, floatCurrentMaxA,
				chargeCurrentLimitA == null ? CalibrationEventConfiguration.defaultChargeCurrentLimitA(definition.capacityAh())
						: chargeCurrentLimitA,
				safetyFloorVoltagePerCell == null
						? CalibrationEventConfiguration.defaultSafetyFloorVoltagePerCell(cutoffVoltagePerCell)
						: safetyFloorVoltagePerCell);
		return createFromSynchronizedString(new CreateCommand(definition.id(), configuration, referenceEventId,
				principal));
	}

	public List<EventSummary> list(UUID stringId, AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return this.store.list(stringId);
	}

	public List<EventSummary> listByStringIndex(int stringIndex, AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return this.store.list(definition(stringIndex).id());
	}

	public EventDetail get(long eventId, AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return withActions(this.store.get(eventId));
	}

	public EventHistory history(long eventId, AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return this.store.history(eventId);
	}

	public List<Sample> samples(long eventId, AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return this.store.samples(eventId);
	}

	public CurvePointPage curvePoints(long eventId, Long curveVersionId, Integer cellIndex, int offset, int limit,
			AuthenticatedPrincipal principal) throws SQLException {
		requireViewer(principal);
		return this.store.curvePoints(eventId, curveVersionId, cellIndex, offset, limit);
	}

	public EventDetail start(long eventId, long expectedEventVersion, AuthenticatedPrincipal principal)
			throws SQLException {
		requireOperator(principal);
		return withActions(this.store.start(eventId, expectedEventVersion, principal));
	}

	public DeletedEvent deleteDraft(DeleteDraftCommand command, AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return this.store.deleteDraft(command);
	}

	public EventDetail startProcess(StartProcessCommand command, AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.startProcess(command, principal));
	}

	public EventDetail completeProcess(CompleteProcessCommand command, AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.completeProcess(command, principal));
	}

	public EventDetail confirmDischargeStopped(ConfirmDischargeStoppedCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.confirmDischargeStopped(command, principal));
	}

	public EventDetail configureDemoTimeScale(ConfigureDemoTimeScaleCommand command, AuthenticatedPrincipal principal)
			throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureDemoTimeScale(command, principal));
	}

	public EventDetail configureDischargeSafetyFloor(ConfigureDischargeSafetyFloorCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureDischargeSafetyFloor(command, principal));
	}

	public EventDetail configureDischargeMonitor(ConfigureDischargeMonitorCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureDischargeMonitor(command, principal));
	}

	public EventDetail configureFloatStablePolicy(ConfigureFloatStablePolicyCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureFloatStablePolicy(command, principal));
	}

	public EventDetail configureActiveFloatCurrentRange(ConfigureActiveFloatCurrentRangeCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureActiveFloatCurrentRange(command, principal));
	}

	public EventDetail configureActiveVocDeadband(ConfigureActiveVocDeadbandCommand command,
			AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.configureActiveVocDeadband(command, principal));
	}

	public EventDetail appendEvidence(ManualEvidenceCommand command, AuthenticatedPrincipal principal)
			throws SQLException {
		requireOperator(principal);
		return withActions(this.store.appendEvidence(command, principal));
	}

	public EventDetail executeAction(ExecuteActionCommand command, AuthenticatedPrincipal principal)
			throws SQLException {
		requireOperator(principal);
		return withActions(this.store.executeAction(command, principal, CalibrationActionPolicy::derive));
	}

	public EventDetail redoStage(RedoStageCommand command, AuthenticatedPrincipal principal) throws SQLException {
		requireOperator(principal);
		return withActions(this.store.redoStage(command, principal));
	}

	public List<AllowedAction> allowedActions(EventDetail detail) {
		return CalibrationActionPolicy.derive(detail);
	}

	private EventDetail withActions(EventDetail detail) {
		return new EventDetail(detail.event(), detail.configuration(), detail.referenceCalibrationEventId(),
				detail.blockers(), detail.missingEvidence(), detail.stages(), detail.processes(),
				detail.referenceCurve(), CalibrationActionPolicy.derive(detail));
	}

	private static void requireOperator(AuthenticatedPrincipal principal) {
		if (principal == null || !principal.hasRole(PlatformRole.BMS_OPERATOR)) {
			throw new SecurityException("BMS_OPERATOR is required for calibration mutations");
		}
	}

	private Definition definition(int stringIndex) throws SQLException {
		if (stringIndex <= 0) throw new IllegalArgumentException("stringIndex must be positive");
		return this.strings.persistentDefinitions().stream().filter(value -> value.stringIndex() == stringIndex).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown string index " + stringIndex));
	}

	private static void validateConfigurationSnapshot(CalibrationEventConfiguration configuration,
			io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition definition) {
		var expectedCutoffVoltagePerCell = configuration.eventType() == CalibrationEventType.INITIAL_BASELINE
				? CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(configuration.dischargeMethod())
				: definition.cutoffVoltage();
		if (configuration.cellCount() != definition.cellCount()
				|| Double.compare(configuration.ratedCapacityAh(), definition.capacityAh()) != 0
				|| Double.compare(configuration.cutoffVoltagePerCell(), expectedCutoffVoltagePerCell) != 0
				|| Double.compare(configuration.floatVoltagePerCell(), definition.floatVoltage()) != 0
				|| Double.compare(configuration.boostVoltagePerCell(), definition.boostVoltage()) != 0) {
			throw new IllegalArgumentException("Calibration configuration does not match the current string definition");
		}
	}

	private static void requireViewer(AuthenticatedPrincipal principal) {
		// The generic principal hierarchy only knows PLATFORM_ADMIN. Calibration reads
		// explicitly make BMS_OPERATOR a superset of BMS_VIEWER.
		if (principal == null || !(principal.hasRole(PlatformRole.BMS_VIEWER)
				|| principal.hasRole(PlatformRole.BMS_OPERATOR))) {
			throw new SecurityException("BMS_VIEWER is required for calibration reads");
		}
	}
}
