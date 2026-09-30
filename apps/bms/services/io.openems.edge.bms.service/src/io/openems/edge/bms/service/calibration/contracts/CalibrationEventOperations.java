package io.openems.edge.bms.service.calibration.contracts;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
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

/** Public feature boundary; implementations and JDBC remain in Platform. */
public interface CalibrationEventOperations {
	EventDetail create(CreateCommand command) throws SQLException;

	EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal)
			throws SQLException;

	EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA, Double chargeCurrentLimitA)
			throws SQLException;

	EventDetail create(int stringIndex, CalibrationEventType eventType,
			io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection currentDirection,
			double floatCoefficient, double boostCoefficient,
			CalibrationEventConfiguration.DischargeMethod dischargeMethod,
			CalibrationEventConfiguration.DischargeEndpointMode endpointMode, Double targetDodPercent,
			Long referenceEventId, AuthenticatedPrincipal principal, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA, Double chargeCurrentLimitA,
			Double safetyFloorVoltagePerCell)
			throws SQLException;

	List<EventSummary> list(UUID stringId, AuthenticatedPrincipal principal) throws SQLException;

	List<EventSummary> listByStringIndex(int stringIndex, AuthenticatedPrincipal principal) throws SQLException;

	EventDetail get(long eventId, AuthenticatedPrincipal principal) throws SQLException;

	EventHistory history(long eventId, AuthenticatedPrincipal principal) throws SQLException;

	List<Sample> samples(long eventId, AuthenticatedPrincipal principal) throws SQLException;

	CurvePointPage curvePoints(long eventId, Long curveVersionId, Integer cellIndex, int offset, int limit,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail start(long eventId, long expectedEventVersion, AuthenticatedPrincipal principal)
			throws SQLException;

	DeletedEvent deleteDraft(DeleteDraftCommand command, AuthenticatedPrincipal principal) throws SQLException;

	EventDetail startProcess(StartProcessCommand command, AuthenticatedPrincipal principal) throws SQLException;

	EventDetail completeProcess(CompleteProcessCommand command, AuthenticatedPrincipal principal) throws SQLException;

	EventDetail confirmDischargeStopped(ConfirmDischargeStoppedCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail configureDemoTimeScale(ConfigureDemoTimeScaleCommand command, AuthenticatedPrincipal principal)
			throws SQLException;

	EventDetail configureDischargeSafetyFloor(ConfigureDischargeSafetyFloorCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail configureDischargeMonitor(ConfigureDischargeMonitorCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail configureFloatStablePolicy(ConfigureFloatStablePolicyCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail configureActiveFloatCurrentRange(ConfigureActiveFloatCurrentRangeCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail configureActiveVocDeadband(ConfigureActiveVocDeadbandCommand command,
			AuthenticatedPrincipal principal) throws SQLException;

	EventDetail appendEvidence(ManualEvidenceCommand command, AuthenticatedPrincipal principal)
			throws SQLException;

	EventDetail executeAction(ExecuteActionCommand command, AuthenticatedPrincipal principal)
			throws SQLException;

	EventDetail redoStage(RedoStageCommand command, AuthenticatedPrincipal principal) throws SQLException;

	List<AllowedAction> allowedActions(EventDetail detail);
}
