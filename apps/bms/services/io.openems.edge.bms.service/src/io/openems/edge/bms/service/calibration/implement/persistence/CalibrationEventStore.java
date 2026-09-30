package io.openems.edge.bms.service.calibration.implement.persistence;

import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations;
import io.openems.edge.bms.service.calibration.implement.CalibrationCommandValidator;
import io.openems.edge.bms.service.calibration.implement.CalibrationDemoTimeScale;
import io.openems.edge.bms.service.calibration.implement.CalibrationDischargeSampling;
import io.openems.edge.bms.service.calibration.implement.CalibrationManualEvidencePolicy;
import io.openems.edge.bms.service.calibration.implement.CalibrationProcessDefinition;
import io.openems.edge.bms.service.calibration.implement.CalibrationRetainedCurrentPolicy;
import io.openems.edge.bms.service.calibration.implement.CalibrationSamplePersistencePolicy;
import io.openems.edge.bms.service.calibration.implement.InitialBaselineCurveIntegrity;
import io.openems.edge.bms.service.calibration.implement.monitors.ChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.FloatChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.InspectionStageMonitor;

import io.openems.edge.bms.service.calibration.implement.CalibrationConflictException;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CreateCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeletedEvent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventStatus;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventSummary;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ReferenceCurve;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePoint;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePointPage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventHistory;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidence;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.RedoStageCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Report;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Sample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CellSample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CompleteProcessCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfirmDischargeStoppedCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDemoTimeScaleCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeSafetyFloorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeMonitorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureFloatStablePolicyCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveFloatCurrentRangeCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveVocDeadbandCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Process;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ProcessState;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Transition;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.SampleWrite;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Stage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StageState;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.WorkerEvent;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** One JDBC transaction owner for the complete calibration aggregate. */
@Component(name = "io.openems.edge.bms.platform.calibration.CalibrationEventStore", service = CalibrationEventStore.class)
public class CalibrationEventStore {

	private static final Gson GSON = new GsonBuilder().serializeNulls().create();
	private static final Logger LOG = LoggerFactory.getLogger(CalibrationEventStore.class);

	@Reference
	private BmsDatabase database;
	private final Set<Long> warnedIncompatibleWorkerEvents = ConcurrentHashMap.newKeySet();

	public EventDetail create(CreateCommand command) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var referenceBaselineAh = validateReference(c, command.stringId(),
						command.referenceCalibrationEventId());
				var configuration = withReference(command.configuration(), referenceBaselineAh);
				long eventId;
				try (var insert = c.prepareStatement("""
					INSERT INTO bms.calibration_event
						(string_id,event_type,configuration_snapshot,reference_calibration_event_id,
						 created_by,created_by_name,missing_evidence)
					VALUES (?,?,?,?,?,?,?) RETURNING id
					""")) {
					insert.setObject(1, command.stringId());
					insert.setString(2, configuration.eventType().name());
					insert.setObject(3, jsonb(GSON.toJsonTree(configuration)));
					setNullableLong(insert, 4, command.referenceCalibrationEventId());
					insert.setObject(5, command.actor().id());
					insert.setString(6, command.actor().name());
					insert.setObject(7, jsonb(GSON.toJsonTree(initialMissingEvidence(configuration.eventType()))));
					try (var result = insert.executeQuery()) {
						result.next();
						eventId = result.getLong(1);
					}
				}
				return loadDetail(c, eventId, false);
			});
		}
	}

	public List<EventSummary> list(UUID stringId) throws SQLException {
		var events = new ArrayList<EventSummary>();
		var sql = """
			SELECT id,string_id,event_type,status,current_stage_id,event_version,measurement_revision,
				created_by,created_by_name,created_at,closed_at,baseline_approved_at,baseline_approved_by,
				baseline_approved_by_name
			FROM bms.calibration_event WHERE (? IS NULL OR string_id=?) ORDER BY created_at DESC
			""";
		try (var connection = this.database.getConnection(); var query = connection.prepareStatement(sql)) {
			query.setObject(1, stringId);
			query.setObject(2, stringId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					events.add(summary(result));
				}
			}
		}
		return List.copyOf(events);
	}

	public EventDetail get(long eventId) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return loadDetail(connection, eventId, false);
		}
	}

	public EventHistory history(long eventId) throws SQLException {
		try (var connection = this.database.getConnection()) {
			// Also provides a consistent not-found contract for empty history.
			loadDetail(connection, eventId, false);
			var transitions = new ArrayList<Transition>();
			try (var query = connection.prepareStatement("""
				SELECT id,calibration_event_id,calibration_stage_id,from_state,to_state,action,reason,actor_id,
					actor_name,evidence,resulting_event_version,transition_order,occurred_at
				FROM bms.calibration_transition WHERE calibration_event_id=?
				ORDER BY resulting_event_version,transition_order,id
				""")) {
				query.setLong(1, eventId);
				try (var result = query.executeQuery()) {
					while (result.next()) transitions.add(new Transition(result.getLong(1), result.getLong(2),
							result.getLong(3), result.getString(4), result.getString(5), result.getString(6),
							result.getString(7), result.getObject(8, UUID.class), result.getString(9),
							parse(result.getString(10)), result.getLong(11), result.getInt(12),
							instant(result.getTimestamp(13))));
				}
			}
			var evidence = new ArrayList<ManualEvidence>();
			try (var query = connection.prepareStatement("""
				SELECT id,calibration_event_id,calibration_stage_id,evidence_type,status,numeric_value,text_value,unit,
					instrument_id,instrument_resolution,instrument_accuracy,calibration_reference,actor_id,actor_name,
						note,external_reference,calibration_sample_id,recorded_at
				FROM bms.calibration_manual_evidence WHERE calibration_event_id=? ORDER BY recorded_at,id
				""")) {
				query.setLong(1, eventId);
				try (var result = query.executeQuery()) {
					while (result.next()) evidence.add(new ManualEvidence(result.getLong(1), result.getLong(2),
							result.getLong(3), result.getString(4), result.getString(5), number(result, 6),
							result.getString(7), result.getString(8), result.getString(9), number(result, 10),
							number(result, 11), result.getString(12), result.getObject(13, UUID.class),
								result.getString(14), result.getString(15), result.getString(16),
								result.getObject(17, Long.class), instant(result.getTimestamp(18))));
				}
			}
			Report report = null;
			try (var query = connection.prepareStatement("""
				SELECT report_schema_version,report_data,generated_at,generated_by,content_hash
				FROM bms.calibration_report WHERE calibration_event_id=?
				""")) {
				query.setLong(1, eventId);
				try (var result = query.executeQuery()) {
					if (result.next()) report = new Report(result.getInt(1), parse(result.getString(2)),
							instant(result.getTimestamp(3)), result.getObject(4, UUID.class), result.getString(5));
				}
			}
			return new EventHistory(transitions, evidence, report);
		}
	}

	public List<Sample> samples(long eventId) throws SQLException {
		try (var connection = this.database.getConnection()) {
			loadDetail(connection, eventId, false);
			var samples = new ArrayList<Sample>();
			try (var query = connection.prepareStatement("""
					SELECT id,calibration_event_id,calibration_stage_id,calibration_process_id,process_attempt_number,snapshot_generation,snapshot_sequence,measurement_time,
					persistence_reason,quality,blocker_reason,raw_current_a,domain_current_a,current_direction,mapping_version,
					current_measured_at,current_quality,string_voltage_v,midpoint_voltage_v,average_voltage_v,ambient_temperature_c,
					average_cell_temperature_c,delta_seconds,delta_ah,raw_used_ah,representative_discharge_temperature_c,
					temperature_factor_kc,corrected_used_ah,
					soc_pct,soc_method,dod_pct,float_target_voltage_v,boost_target_voltage_v,created_at
				FROM bms.calibration_sample WHERE calibration_event_id=? ORDER BY measurement_time,id
				""")) {
				query.setLong(1, eventId);
				try (var result = query.executeQuery()) {
					while (result.next()) {
						long sampleId = result.getLong(1);
							samples.add(new Sample(sampleId, result.getLong(2), result.getLong(3),
									result.getObject(4, Long.class), result.getObject(5, Integer.class), result.getObject(6, UUID.class),
									result.getLong(7), instant(result.getTimestamp(8)), result.getString(9), result.getString(10),
									result.getString(11), number(result, 12), number(result, 13), result.getString(14),
									result.getObject(15, Long.class), instant(result.getTimestamp(16)), result.getString(17), number(result, 18),
									number(result, 19), number(result, 20), number(result, 21), number(result, 22),
									number(result, 23), number(result, 24), number(result, 25), number(result, 26),
									number(result, 27), number(result, 28), number(result, 29), result.getString(30),
									number(result, 31), number(result, 32), number(result, 33), instant(result.getTimestamp(34)),
								cells(connection, sampleId)));
					}
				}
			}
			return List.copyOf(samples);
		}
	}

	public CurvePointPage curvePoints(long eventId, Long curveVersionId, Integer cellIndex, int offset, int limit)
			throws SQLException {
		if (offset < 0) throw new IllegalArgumentException("offset must be at least zero");
		if (limit < 1 || limit > 2_000) throw new IllegalArgumentException("limit must be between 1 and 2000");
		if (cellIndex != null && cellIndex < 1) throw new IllegalArgumentException("cellIndex must be positive");
		try (var connection = this.database.getConnection()) {
			loadDetail(connection, eventId, false);
			var curve = loadReferenceCurve(connection, eventId, curveVersionId);
			if (curve == null) throw new IllegalArgumentException("Published reference curve was not found");
			long total;
			try (var count = connection.prepareStatement("""
				SELECT count(*) FROM bms.calibration_curve_point
				WHERE curve_version_id=? AND (?::integer IS NULL OR cell_index=?)
				""")) {
				count.setLong(1, curve.curveVersionId());
				if (cellIndex == null) count.setNull(2, Types.INTEGER); else count.setInt(2, cellIndex);
				if (cellIndex == null) count.setNull(3, Types.INTEGER); else count.setInt(3, cellIndex);
				try (var result = count.executeQuery()) { result.next(); total = result.getLong(1); }
			}
			var points = new ArrayList<CurvePoint>();
			try (var query = connection.prepareStatement("""
				SELECT curve_version_id,cell_index,point_order,calibration_sample_id,milestone_percent,
					normalized_actual_dod_percent,discharged_ah,cell_voltage_v,cell_temperature_c,
					cell_resistance_uohm,cell_soc_pct,cell_soh_pct,string_voltage_v,average_voltage_v,quality,usable
				FROM bms.calibration_curve_point
				WHERE curve_version_id=? AND (?::integer IS NULL OR cell_index=?)
				ORDER BY cell_index,point_order LIMIT ? OFFSET ?
				""")) {
				query.setLong(1, curve.curveVersionId());
				if (cellIndex == null) query.setNull(2, Types.INTEGER); else query.setInt(2, cellIndex);
				if (cellIndex == null) query.setNull(3, Types.INTEGER); else query.setInt(3, cellIndex);
				query.setInt(4, limit);
				query.setInt(5, offset);
				try (var result = query.executeQuery()) {
					while (result.next()) points.add(new CurvePoint(result.getLong(1), result.getInt(2),
							result.getInt(3), result.getLong(4), result.getDouble(5), result.getDouble(6),
							result.getDouble(7), result.getDouble(8), number(result, 9),
							result.getObject(10, Integer.class), number(result, 11), number(result, 12),
							number(result, 13), number(result, 14), result.getString(15), result.getBoolean(16)));
				}
			}
			return new CurvePointPage(curve, offset, limit, total, points);
		}
	}

	private static List<CellSample> cells(Connection connection, long sampleId) throws SQLException {
		var cells = new ArrayList<CellSample>();
		try (var query = connection.prepareStatement("""
			SELECT cell_index,voltage_v,temperature_c,resistance_uohm,soc_pct,soc_method,soh_pct,soh_method,quality,
				availability_warning FROM bms.calibration_cell_sample WHERE calibration_sample_id=? ORDER BY cell_index
			""")) {
			query.setLong(1, sampleId);
			try (var result = query.executeQuery()) {
				while (result.next()) cells.add(new CellSample(result.getInt(1), number(result, 2), number(result, 3),
						result.getObject(4, Integer.class), number(result, 5), result.getString(6), number(result, 7),
						result.getString(8), result.getString(9), result.getString(10)));
			}
		}
		return List.copyOf(cells);
	}

	public EventDetail start(long eventId, long expectedVersion, AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, eventId, true);
				CalibrationCommandValidator.requireVersion(detail, expectedVersion);
				if (detail.event().status() != EventStatus.DRAFT) {
					throw new IllegalArgumentException("Only a draft calibration event can be started");
				}
				if (detail.event().currentStageId() != null) {
					throw new IllegalArgumentException("A draft calibration event must not have a current stage");
				}
				detail = prepareStages(c, detail);
				var first = detail.stages().get(0);
				try (var stage = c.prepareStatement("""
					UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now(),started_by=?,updated_at=now() WHERE id=?
					""")) {
					stage.setObject(1, actor.id());
					stage.setLong(2, first.id());
					stage.executeUpdate();
				}
				try (var event = c.prepareStatement("""
					UPDATE bms.calibration_event SET status='ACTIVE',current_stage_id=?,started_at=now(),
						event_version=event_version+1,updated_at=now() WHERE id=?
					""")) {
					event.setLong(1, first.id());
					event.setLong(2, eventId);
					event.executeUpdate();
				}
				appendTransition(c, eventId, first.id(), "NOT_STARTED", "ACTIVE", "START_EVENT", null, actor,
						expectedVersion + 1, 0, new JsonObject());
				return loadDetail(c, eventId, false);
			});
		}
	}

	/**
	 * Permanently removes only an unstarted, unaudited draft. Older draft rows may
	 * contain unused stages and processes; remove that aggregate in foreign-key
	 * order while the event row remains locked.
	 */
	public DeletedEvent deleteDraft(DeleteDraftCommand command) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				if (detail.event().status() != EventStatus.DRAFT) {
					throw new IllegalArgumentException("Only a draft calibration event can be deleted");
				}
				if (detail.event().currentStageId() != null) {
					throw new IllegalArgumentException("A draft calibration event must not have a current stage");
				}
				if (hasAuditRows(c, command.calibrationEventId())) {
					throw new IllegalArgumentException("Audited calibration events cannot be deleted");
				}
				if (hasStartedRows(c, command.calibrationEventId())) {
					throw new IllegalArgumentException("Started calibration events cannot be deleted");
				}
				// Both event and legacy-stage DELETE triggers require this transaction-local
				// authorization. It is applied only after the locked aggregate has passed
				// every draft/version/audit/start validation above.
				try (var authorize = c.prepareStatement(
						"SELECT set_config('bms.calibration.delete_draft_event_id',?,true)")) {
					authorize.setString(1, Long.toString(detail.event().id()));
					authorize.execute();
				}
				deleteDraftAggregate(c, command.calibrationEventId());
				return new DeletedEvent(command.calibrationEventId());
			});
		}
	}

	private static boolean hasStartedRows(Connection connection, long eventId) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT EXISTS (
				SELECT 1 FROM bms.calibration_stage
				WHERE calibration_event_id=? AND (state <> 'NOT_STARTED' OR started_at IS NOT NULL OR completed_at IS NOT NULL)
				UNION ALL SELECT 1 FROM bms.calibration_process
				WHERE calibration_event_id=? AND (state <> 'NOT_STARTED' OR started_at IS NOT NULL OR completed_at IS NOT NULL)
			)
			""")) {
			query.setLong(1, eventId);
			query.setLong(2, eventId);
			try (var result = query.executeQuery()) {
				result.next();
				return result.getBoolean(1);
			}
		}
	}

	private static boolean hasAuditRows(Connection connection, long eventId) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT EXISTS (
				SELECT 1 FROM bms.calibration_transition WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_process_transition WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_sample WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_manual_evidence WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_report WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_cell_cutoff_crossing WHERE calibration_event_id=?
				UNION ALL SELECT 1 FROM bms.calibration_curve_version WHERE calibration_event_id=?
			)
			""")) {
			for (int index = 1; index <= 7; index++) query.setLong(index, eventId);
			try (var result = query.executeQuery()) {
				result.next();
				return result.getBoolean(1);
			}
		}
	}

	private static void deleteDraftAggregate(Connection connection, long eventId) throws SQLException {
		deleteByEventId(connection, "DELETE FROM bms.calibration_cell_sample WHERE calibration_sample_id IN "
				+ "(SELECT id FROM bms.calibration_sample WHERE calibration_event_id=?)", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_sample_reason WHERE calibration_sample_id IN "
				+ "(SELECT id FROM bms.calibration_sample WHERE calibration_event_id=?)", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_sample WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_process_transition WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_process WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_transition WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_manual_evidence WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_report WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_stage WHERE calibration_event_id=?", eventId);
		deleteByEventId(connection, "DELETE FROM bms.calibration_event WHERE id=?", eventId);
	}

	private static void deleteByEventId(Connection connection, String sql, long eventId) throws SQLException {
		try (var delete = connection.prepareStatement(sql)) {
			delete.setLong(1, eventId);
			delete.executeUpdate();
		}
	}

	/**
	 * Starts exactly one physical process. The enclosing stage remains active;
	 * checklist-only stages do not have a process row and therefore cannot enter
	 * the measurement worker.
	 */
	public EventDetail startProcess(StartProcessCommand command, AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				validateActiveProcessDefinitions(detail);
				if (detail.event().closedAt() != null || stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException("Only the active stage of an open event can start a process");
				}
				Process process = detail.processes().stream()
						.filter(value -> value.id() == command.calibrationProcessId()
								&& value.calibrationStageId() == stage.id())
						.findFirst().orElseThrow(() -> new IllegalArgumentException("Calibration process was not found"));
				if (process.state() != ProcessState.NOT_STARTED) {
					throw new IllegalArgumentException("Calibration process has already started");
				}
				validateProcessStartPolicy(stage, process);
				boolean predecessorOpen = detail.processes().stream()
						.anyMatch(value -> value.calibrationStageId() == stage.id() && value.order() < process.order()
								&& value.state() != ProcessState.COMPLETED);
				if (predecessorOpen) throw new IllegalArgumentException("Complete the previous process first");
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process SET state='ACTIVE',started_at=now(),started_by=?,updated_at=now()
					WHERE id=? AND state='NOT_STARTED'
					""")) {
					update.setObject(1, actor.id());
					update.setLong(2, process.id());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration process was changed concurrently");
				}
				try (var update = c.prepareStatement("UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					update.executeUpdate();
				}
				appendProcessTransition(c, detail.event().id(), stage.id(), process.id(), "NOT_STARTED", "ACTIVE",
						"START_PROCESS", null, actor, version, appliedPolicyEvidence(process));
				appendTransition(c, detail.event().id(), stage.id(), stage.state().name(), stage.state().name(),
						"START_PROCESS", null, actor, version, 0, appliedPolicyEvidence(process));
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/** Completes an active process and appends its final snapshot when required. */
	public EventDetail completeProcess(CompleteProcessCommand command, AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				validateActiveProcessDefinitions(detail);
				if (detail.event().closedAt() != null || stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException("Only an unblocked active stage can complete a process");
				}
				var process = detail.processes().stream().filter(value -> value.id() == command.calibrationProcessId()
						&& value.calibrationStageId() == stage.id()).findFirst()
						.orElseThrow(() -> new IllegalArgumentException("Calibration process was not found"));
				if (process.state() != ProcessState.ACTIVE) {
					throw new IllegalArgumentException("Only an active calibration process can be completed");
				}
				validateManualProcessCompletion(process);
				if ("CONTROLLED_DISCHARGE".equals(process.type())) {
					var runtime = process.runtime() != null && process.runtime().isJsonObject()
							? process.runtime().getAsJsonObject() : new JsonObject();
					var blockers = runtime.get("blockers");
					var checkpoint = runtime.get("checkpoint");
					boolean endpointReady = checkpoint != null && checkpoint.isJsonObject()
							&& checkpoint.getAsJsonObject().has("endpoint")
							&& !checkpoint.getAsJsonObject().get("endpoint").isJsonNull();
					boolean noBlockers = blockers != null && blockers.isJsonArray() && blockers.getAsJsonArray().isEmpty();
					if (!runtime.has("recommendation")
							|| !"READY_TO_COMPLETE".equals(runtime.get("recommendation").getAsString())
							|| !endpointReady || !noBlockers) {
						throw new IllegalArgumentException(
								"Controlled discharge can complete only after a valid cutoff endpoint is ready");
					}
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),completed_by=?,
						updated_at=now() WHERE id=? AND state='ACTIVE'
					""")) {
					update.setObject(1, actor.id());
					update.setLong(2, process.id());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration process was changed concurrently");
				}
				try (var update = c.prepareStatement("UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					update.executeUpdate();
				}
				appendProcessTransition(c, detail.event().id(), stage.id(), process.id(), "ACTIVE", "COMPLETED",
						"COMPLETE_PROCESS", trimToNull(command.reason()), actor, version);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/** Blocks the aggregate after the operator verifies that safety-triggered discharge current is zero. */
	public EventDetail confirmDischargeStopped(ConfirmDischargeStoppedCommand command, AuthenticatedPrincipal actor)
			throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
						|| stage.type() != CalibrationStageType.DISCHARGE || stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException("Only an active Initial Baseline discharge can confirm a safety stop");
				}
				var process = detail.processes().stream()
						.filter(value -> value.id() == command.calibrationProcessId()
								&& value.calibrationStageId() == stage.id()
								&& "CONTROLLED_DISCHARGE".equals(value.type()))
						.findFirst().orElseThrow(() -> new IllegalArgumentException("Controlled discharge process was not found"));
				if (process.state() != ProcessState.ACTIVE) {
					throw new IllegalArgumentException("Only the active controlled discharge can confirm a safety stop");
				}
				String safetyState;
				Double domainCurrentA;
				boolean currentFresh;
				try (var query = c.prepareStatement("""
					SELECT discharge.safety_state,runtime.last_domain_current_a,
						runtime.last_current_measured_at IS NOT NULL
						AND runtime.last_current_measured_at > now()-(? * interval '1 second')
					FROM bms.calibration_discharge_runtime discharge
					JOIN bms.calibration_process_runtime runtime
						ON runtime.calibration_process_id=discharge.calibration_process_id
						AND runtime.attempt_number=discharge.attempt_number
					WHERE discharge.calibration_process_id=? AND discharge.attempt_number=?
					FOR UPDATE OF discharge,runtime
					""")) {
					query.setInt(1, detail.configuration().snapshotFreshnessSeconds());
					query.setLong(2, process.id());
					query.setInt(3, process.attemptNumber());
					try (var result = query.executeQuery()) {
						if (!result.next()) throw new IllegalArgumentException("Discharge runtime was not found");
						safetyState = result.getString(1);
						domainCurrentA = number(result, 2);
						currentFresh = result.getBoolean(3);
					}
				}
				if (!"LATCHED".equals(safetyState)) {
					throw new IllegalArgumentException("Discharge safety state is not LATCHED");
				}
				if (!currentFresh) throw new IllegalArgumentException("Latest discharge current is stale");
				if (domainCurrentA == null || !Double.isFinite(domainCurrentA)
						|| Math.abs(domainCurrentA) > detail.configuration().currentDeadbandA()) {
					throw new IllegalArgumentException("Stop the discharge load and wait for near-zero current before confirming");
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_discharge_runtime SET safety_state='STOP_CONFIRMED',updated_at=now()
					WHERE calibration_process_id=? AND attempt_number=? AND safety_state='LATCHED'
					""")) {
					update.setLong(1, process.id());
					update.setInt(2, process.attemptNumber());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Discharge safety state changed concurrently");
				}
				var blockers = new JsonArray();
				blockers.add("SAFETY_FLOOR_REACHED");
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process SET state='BLOCKED',updated_at=now()
					WHERE id=? AND attempt_number=? AND state='ACTIVE'
					""")) {
					update.setLong(1, process.id());
					update.setInt(2, process.attemptNumber());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration process changed concurrently");
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_stage SET state='BLOCKED',blocker_code='SAFETY_FLOOR_REACHED',
						blocker_detail=?,updated_at=now() WHERE id=? AND state='ACTIVE'
					""")) {
					update.setObject(1, jsonb(blockers));
					update.setLong(2, stage.id());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration stage changed concurrently");
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_event SET status='BLOCKED',blockers=?,event_version=?,updated_at=now()
					WHERE id=?
					""")) {
					update.setObject(1, jsonb(blockers));
					update.setLong(2, version);
					update.setLong(3, detail.event().id());
					update.executeUpdate();
				}
				appendProcessTransition(c, detail.event().id(), stage.id(), process.id(), "ACTIVE", "BLOCKED",
						"CONFIRM_DISCHARGE_STOPPED", "SAFETY_FLOOR_REACHED", actor, version);
				var evidence = new JsonObject();
				evidence.addProperty("calibrationProcessId", process.id());
				evidence.addProperty("domainCurrentA", domainCurrentA);
				appendTransition(c, detail.event().id(), stage.id(), "ACTIVE", "BLOCKED",
						"CONFIRM_DISCHARGE_STOPPED", "SAFETY_FLOOR_REACHED", actor, version, 0, evidence);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/**
	 * Stores the Initial Baseline demo acceleration on the Prerequisites stage.
	 * It is intentionally not part of the immutable equipment configuration: it
	 * affects test evaluation time only and is audit-recorded before any physical
	 * process can run.
	 */
	public EventDetail configureDemoTimeScale(ConfigureDemoTimeScaleCommand command, AuthenticatedPrincipal actor)
			throws SQLException {
		CalibrationDemoTimeScale.requireSupported(command.demoTimeScaleFactor());
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE) {
					throw new IllegalArgumentException("demoTimeScaleFactor is supported only for Initial Baseline");
				}
				if (detail.event().closedAt() != null || stage.type() != CalibrationStageType.PREREQUISITES
						|| stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException("demoTimeScaleFactor may be configured only on active Prerequisites");
				}
				if (detail.processes().stream().anyMatch(value -> value.state() != ProcessState.NOT_STARTED)) {
					throw new IllegalArgumentException("demoTimeScaleFactor is immutable after a physical process starts");
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_stage SET properties=jsonb_set(properties,?::text[],to_jsonb(?::integer),true),updated_at=now()
					WHERE id=? AND state='ACTIVE'
					""")) {
					update.setString(1, "{" + CalibrationDemoTimeScale.STAGE_PROPERTY + "}");
					update.setInt(2, command.demoTimeScaleFactor());
					update.setLong(3, stage.id());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration stage was changed concurrently");
				}
				try (var update = c.prepareStatement("UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					update.executeUpdate();
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process
					SET properties=jsonb_set(properties,?::text[],to_jsonb(?::integer),true),updated_at=now()
					WHERE calibration_event_id=? AND process_type='CONTROLLED_DISCHARGE'
						AND state='NOT_STARTED' AND started_at IS NULL
					""")) {
					update.setString(1, "{" + CalibrationDischargeSampling.WALL_CLOCK_SECONDS_PROPERTY + "}");
					update.setInt(2, CalibrationDischargeSampling.wallClockSeconds(command.demoTimeScaleFactor()));
					update.setLong(3, detail.event().id());
					if (update.executeUpdate() != 1) {
						throw new IllegalStateException("Controlled discharge process was changed concurrently");
					}
				}
				var audit = new JsonObject();
				audit.addProperty(CalibrationDemoTimeScale.STAGE_PROPERTY, command.demoTimeScaleFactor());
				audit.addProperty(CalibrationDischargeSampling.WALL_CLOCK_SECONDS_PROPERTY,
						CalibrationDischargeSampling.wallClockSeconds(command.demoTimeScaleFactor()));
				appendTransition(c, detail.event().id(), stage.id(), stage.state().name(), stage.state().name(),
						"CONFIGURE_DEMO_TIME_SCALE", null, actor, version, 0, audit);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/**
	 * Stores the operator-selected safety floor on the controlled-discharge
	 * process. The immutable event snapshot keeps its creation-time default; event
	 * detail overlays this audited process policy until the process starts.
	 */
	public EventDetail configureDischargeSafetyFloor(ConfigureDischargeSafetyFloorCommand command,
			AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
						|| detail.event().closedAt() != null || stage.type() != CalibrationStageType.WAITING_FOR_LOAD
						|| stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException(
							"Discharge safety floor may be configured only on active Waiting for Load");
				}
				var effective = detail.configuration()
						.withSafetyFloorVoltagePerCell(command.safetyFloorVoltagePerCell());
				var discharge = detail.processes().stream()
						.filter(value -> "CONTROLLED_DISCHARGE".equals(value.type()))
						.findFirst().orElseThrow(() -> new IllegalArgumentException("Controlled discharge process was not found"));
				if (discharge.state() != ProcessState.NOT_STARTED || discharge.startedAt() != null) {
					throw new IllegalArgumentException("Discharge safety floor is immutable after controlled discharge starts");
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process
					SET properties=jsonb_set(properties,'{safetyFloorVoltagePerCell}',to_jsonb(?::double precision),true),
						updated_at=now()
					WHERE id=? AND state='NOT_STARTED' AND started_at IS NULL
					""")) {
					update.setDouble(1, effective.safetyFloorVoltagePerCell());
					update.setLong(2, discharge.id());
					if (update.executeUpdate() != 1) {
						throw new IllegalStateException("Calibration process was changed concurrently");
					}
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement(
						"UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					if (update.executeUpdate() != 1) {
						throw new IllegalStateException("Calibration event was changed concurrently");
					}
				}
				var audit = new JsonObject();
				audit.addProperty("safetyFloorVoltagePerCell", effective.safetyFloorVoltagePerCell());
				audit.addProperty("cutoffVoltagePerCell", effective.cutoffVoltagePerCell());
				appendTransition(c, detail.event().id(), stage.id(), stage.state().name(), stage.state().name(),
						"CONFIGURE_DISCHARGE_SAFETY_FLOOR", null, actor, version, 0, audit);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/**
	 * Stores the complete operator-selected controlled-discharge monitor policy.
	 * Both values remain mutable only while Waiting for Load is active and the
	 * controlled-discharge process has never started.
	 */
	public EventDetail configureDischargeMonitor(ConfigureDischargeMonitorCommand command,
			AuthenticatedPrincipal actor) throws SQLException {
		if (command.maxWrongCurrentDirectionSamples() <= 0) {
			throw new IllegalArgumentException(
					DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY + " must be positive");
		}
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
						|| detail.event().closedAt() != null || stage.type() != CalibrationStageType.WAITING_FOR_LOAD
						|| stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException(
							"Discharge monitor may be configured only on active Waiting for Load");
				}
				var effective = detail.configuration()
						.withSafetyFloorVoltagePerCell(command.safetyFloorVoltagePerCell());
				var discharge = detail.processes().stream()
						.filter(value -> "CONTROLLED_DISCHARGE".equals(value.type()))
						.findFirst().orElseThrow(
								() -> new IllegalArgumentException("Controlled discharge process was not found"));
				if (discharge.state() != ProcessState.NOT_STARTED || discharge.startedAt() != null) {
					throw new IllegalArgumentException("Discharge monitor is immutable after controlled discharge starts");
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process
					SET properties=jsonb_set(
						jsonb_set(properties,'{safetyFloorVoltagePerCell}',to_jsonb(?::double precision),true),
						?::text[],to_jsonb(?::integer),true),updated_at=now()
					WHERE id=? AND state='NOT_STARTED' AND started_at IS NULL
					""")) {
					update.setDouble(1, effective.safetyFloorVoltagePerCell());
					update.setString(2,
							"{" + DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY + "}");
					update.setInt(3, command.maxWrongCurrentDirectionSamples());
					update.setLong(4, discharge.id());
					if (update.executeUpdate() != 1) {
						throw new IllegalStateException("Calibration process was changed concurrently");
					}
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement(
						"UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					if (update.executeUpdate() != 1) {
						throw new IllegalStateException("Calibration event was changed concurrently");
					}
				}
				var audit = new JsonObject();
				audit.addProperty("safetyFloorVoltagePerCell", effective.safetyFloorVoltagePerCell());
				audit.addProperty("cutoffVoltagePerCell", effective.cutoffVoltagePerCell());
				audit.addProperty(DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY,
						command.maxWrongCurrentDirectionSamples());
				appendTransition(c, detail.event().id(), stage.id(), stage.state().name(), stage.state().name(),
						"CONFIGURE_DISCHARGE_MONITOR", null, actor, version, 0, audit);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/**
	 * Persists the operator-selected Float observation policy before Stage 2 begins.
	 * The immutable equipment snapshot is intentionally not rewritten: the exact
	 * policy lives with each affected process and is recorded in the transition audit.
	 */
	public EventDetail configureFloatStablePolicy(ConfigureFloatStablePolicyCommand command,
			AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
						|| detail.event().closedAt() != null || stage.type() != CalibrationStageType.INITIAL_CHARGE
						|| stage.state() != StageState.ACTIVE) {
					throw new IllegalArgumentException(
							"Float stable policy may be configured only on active Initial Charge");
				}
				var stageProcesses = detail.processes().stream()
						.filter(process -> process.calibrationStageId() == stage.id()).toList();
				if (stageProcesses.isEmpty() || stageProcesses.stream()
						.anyMatch(process -> process.state() != ProcessState.NOT_STARTED)) {
					throw new IllegalArgumentException("Float stable policy is immutable after Initial Charge starts");
				}
				var effectiveConfiguration = detail.configuration().withFloatStablePolicy(
						command.floatStableDurationMinutes(), command.floatVoltageToleranceV(), command.floatCurrentMinA(),
						command.floatCurrentMaxA());
				var affected = detail.processes().stream().filter(CalibrationEventStore::hasFloatStablePolicy).toList();
				if (affected.isEmpty() || affected.stream().anyMatch(process -> process.state() != ProcessState.NOT_STARTED)) {
					throw new IllegalArgumentException("Float stable policy is immutable after a charge process starts");
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_process SET properties=?::jsonb,updated_at=now()
					WHERE id=? AND state='NOT_STARTED'
					""")) {
					for (var process : affected) {
						var properties = process.configuration().getAsJsonObject().deepCopy();
						applyFloatStablePolicy(properties, effectiveConfiguration);
						update.setObject(1, jsonb(properties));
						update.setLong(2, process.id());
						update.addBatch();
					}
					for (var result : update.executeBatch()) {
						if (result != 1) throw new IllegalStateException("Calibration process was changed concurrently");
					}
				}
				long version = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
					update.setLong(1, version);
					update.setLong(2, detail.event().id());
					if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration event was changed concurrently");
				}
				var audit = new JsonObject();
				audit.addProperty("floatStableDurationMinutes", command.floatStableDurationMinutes());
				audit.addProperty("floatVoltageToleranceV", command.floatVoltageToleranceV());
				audit.addProperty("floatCurrentMinA", command.floatCurrentMinA());
				audit.addProperty("floatCurrentMaxA", command.floatCurrentMaxA());
				appendTransition(c, detail.event().id(), stage.id(), stage.state().name(), stage.state().name(),
						"CONFIGURE_FLOAT_STABLE_POLICY", null, actor, version, 0, audit);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	/** Changes only the current active Float process and restarts its stable window. */
	public EventDetail configureActiveFloatCurrentRange(ConfigureActiveFloatCurrentRangeCommand command,
			AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				var reason = requirePolicyReason(command.reason());
				detail.configuration().withFloatStablePolicy(detail.configuration().floatStableDurationMinutes(),
						detail.configuration().floatVoltageToleranceV(), command.floatCurrentMinA(),
						command.floatCurrentMaxA());
				var process = requireActivePolicyProcess(detail, stage, command.calibrationProcessId(),
						command.processAttemptNumber(), Set.of("FLOAT_SETTLEMENT_TO_VFLOAT",
								"RECOVERY_FLOAT_STABILIZATION"));
				var applied = process.configuration().getAsJsonObject().deepCopy();
				int revision = policyRevision(applied) + 1;
				applied.addProperty("floatCurrentMinA", command.floatCurrentMinA());
				applied.addProperty("floatCurrentMaxA", command.floatCurrentMaxA());
				applied.addProperty("policyRevision", revision);
				updateActiveProcessPolicy(c, process, applied);
				try (var reset = c.prepareStatement("""
					UPDATE bms.calibration_process_runtime SET checkpoint_data=
						jsonb_set(jsonb_set(jsonb_set(jsonb_set(COALESCE(checkpoint_data,'{}'::jsonb),
						'{stableSinceMillis}'::text[],'null'::jsonb,true),
						'{stableDurationMillis}'::text[],'0'::jsonb,true),
						'{sampleCount}'::text[],'0'::jsonb,true),
						'{maxFloatCurrentA}'::text[],'0'::jsonb,true)
						|| jsonb_build_object('invalidWindow',false,'result','DATA_INVALID'),updated_at=now()
					WHERE calibration_process_id=? AND attempt_number=?
					""")) {
					reset.setLong(1, process.id()); reset.setInt(2, process.attemptNumber()); reset.executeUpdate();
				}
				return finishPolicyUpdate(c, detail, stage, process, actor, reason, revision, applied,
						"UPDATE_ACTIVE_FLOAT_POLICY", true);
			});
		}
	}

	/** Changes only the current active Voc process; the unchanged runtime identity forces a new snapshot. */
	public EventDetail configureActiveVocDeadband(ConfigureActiveVocDeadbandCommand command,
			AuthenticatedPrincipal actor) throws SQLException {
		if (!Double.isFinite(command.vocCurrentDeadbandA()) || command.vocCurrentDeadbandA() < 0) {
			throw new IllegalArgumentException("vocCurrentDeadbandA must be finite and non-negative");
		}
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var stage = requireCurrentStage(detail, command.calibrationStageId());
				var reason = requirePolicyReason(command.reason());
				var process = requireActivePolicyProcess(detail, stage, command.calibrationProcessId(),
						command.processAttemptNumber(), Set.of("VOC_BASELINE_CAPTURE"));
				var applied = process.configuration().getAsJsonObject().deepCopy();
				int revision = policyRevision(applied) + 1;
				applied.addProperty("vocCurrentDeadbandA", command.vocCurrentDeadbandA());
				applied.addProperty("policyRevision", revision);
				updateActiveProcessPolicy(c, process, applied);
				return finishPolicyUpdate(c, detail, stage, process, actor, reason, revision, applied,
						"UPDATE_ACTIVE_VOC_POLICY", false);
			});
		}
	}

	private static Process requireActivePolicyProcess(EventDetail detail, Stage stage, long processId,
			int attemptNumber, Set<String> allowedTypes) {
		if (detail.event().closedAt() != null || stage.state() != StageState.ACTIVE) {
			throw new IllegalArgumentException("Policy can be changed only on the current active stage");
		}
		var process = detail.processes().stream().filter(value -> value.id() == processId
				&& value.calibrationStageId() == stage.id()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Calibration process was not found"));
		if (process.state() != ProcessState.ACTIVE || process.attemptNumber() != attemptNumber
				|| !allowedTypes.contains(process.type())) {
			throw new IllegalArgumentException("Policy can be changed only on the exact active process attempt");
		}
		return process;
	}

	private static String requirePolicyReason(String value) {
		var result = trimToNull(value);
		if (result == null) throw new IllegalArgumentException("A reason is required to change an active process policy");
		if (result.length() > 512) throw new IllegalArgumentException("Policy-change reason must not exceed 512 characters");
		return result;
	}

	private static int policyRevision(JsonObject properties) {
		var value = properties.get("policyRevision");
		return value == null || value.isJsonNull() ? 1 : Math.max(1, value.getAsInt());
	}

	private static void updateActiveProcessPolicy(Connection connection, Process process, JsonObject applied)
			throws SQLException {
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET properties=?::jsonb,updated_at=now()
			WHERE id=? AND attempt_number=? AND state='ACTIVE'
			""")) {
			update.setObject(1, jsonb(applied)); update.setLong(2, process.id());
			update.setInt(3, process.attemptNumber());
			if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration process was changed concurrently");
		}
	}

	private static EventDetail finishPolicyUpdate(Connection connection, EventDetail detail, Stage stage,
			Process process, AuthenticatedPrincipal actor, String reason, int revision, JsonObject applied,
			String action, boolean stableWindowReset) throws SQLException {
		long version = detail.event().eventVersion() + 1;
		try (var update = connection.prepareStatement(
				"UPDATE bms.calibration_event SET event_version=?,updated_at=now() WHERE id=?")) {
			update.setLong(1, version); update.setLong(2, detail.event().id());
			if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration event was changed concurrently");
		}
		var evidence = new JsonObject();
		evidence.addProperty("processType", process.type());
		evidence.addProperty("attemptNumber", process.attemptNumber());
		evidence.addProperty("policyRevision", revision);
		evidence.add("appliedPolicy", applied.deepCopy());
		evidence.addProperty("stableWindowReset", stableWindowReset);
		appendProcessTransition(connection, detail.event().id(), stage.id(), process.id(), "ACTIVE", "ACTIVE",
				action, reason, actor, version, evidence);
		appendTransition(connection, detail.event().id(), stage.id(), "ACTIVE", "ACTIVE", action, reason, actor,
				version, 0, evidence);
		return loadDetail(connection, detail.event().id(), false);
	}

	private static EventDetail prepareStages(Connection connection, EventDetail detail) throws SQLException {
		var eventType = detail.event().eventType();
		var expected = CalibrationProcessDefinition.stages(eventType);
		if (detail.stages().isEmpty()) {
			insertStages(connection, detail.event().id(), eventType, expected, detail.configuration());
			return loadDetail(connection, detail.event().id(), false);
		}
		if (detail.stages().size() != expected.size()) {
			throw new IllegalArgumentException("Calibration draft has an invalid stage definition");
		}
		for (int index = 0; index < expected.size(); index++) {
			var stage = detail.stages().get(index);
			if (stage.order() != index || stage.type() != expected.get(index)
					|| stage.state() != StageState.NOT_STARTED) {
				throw new IllegalArgumentException("Calibration draft has an invalid stage definition");
			}
		}
		return detail;
	}

	private static void insertStages(Connection connection, long eventId, CalibrationEventType eventType,
			List<CalibrationStageType> stages, CalibrationEventConfiguration configuration)
			throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type)
			VALUES (?,?,?)
			""")) {
			for (int index = 0; index < stages.size(); index++) {
				insert.setLong(1, eventId);
				insert.setInt(2, index);
				insert.setString(3, stages.get(index).name());
				insert.addBatch();
			}
			insert.executeBatch();
		}
		try (var stageQuery = connection.prepareStatement("""
			SELECT id,stage_type FROM bms.calibration_stage
			WHERE calibration_event_id=? ORDER BY stage_order
			""")) {
			stageQuery.setLong(1, eventId);
			try (var rows = stageQuery.executeQuery()) {
				while (rows.next()) {
					insertProcesses(connection, eventId, rows.getLong(1), eventType,
							CalibrationStageType.valueOf(rows.getString(2)), configuration);
				}
			}
		}
	}

	private static void insertProcesses(Connection connection, long eventId, long stageId,
			CalibrationEventType eventType, CalibrationStageType stageType,
			CalibrationEventConfiguration configuration) throws SQLException {
		var definitions = CalibrationProcessDefinition.processes(eventType, stageType);
		if (definitions.isEmpty()) return;
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_process
				(calibration_event_id,calibration_stage_id,process_order,process_type,sampling_policy,properties)
			VALUES (?,?,?,?,?,?)
			""")) {
			for (int order = 0; order < definitions.size(); order++) {
				var process = definitions.get(order);
				insert.setLong(1, eventId);
				insert.setLong(2, stageId);
				insert.setInt(3, order);
				insert.setString(4, process.type());
				insert.setString(5, process.samplingPolicy().name());
				insert.setObject(6, jsonb(initialProcessProperties(process.type(), configuration)));
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static JsonObject initialProcessProperties(String processType, CalibrationEventConfiguration configuration) {
		var properties = new JsonObject();
		if (processType.contains("VBOOST")) {
			properties.addProperty("boostTargetVoltageV", configuration.boostVoltagePerCell() * configuration.cellCount());
		}
		if (processType.contains("VBOOST") || processType.contains("FLOAT")) {
			// Persist the effective Float policy on both parts of a charge pair. This
			// makes a Boost-to-Float transition self-contained and auditably tied to
			// the immutable event snapshot; it does not issue a hardware command.
			applyFloatStablePolicy(properties, configuration);
			properties.addProperty("chargeCurrentLimitA", configuration.chargeCurrentLimitA());
		}
		if (processType.contains("FLOAT")) {
			properties.addProperty("floatTargetVoltageV", configuration.floatVoltagePerCell() * configuration.cellCount());
		}
		if ("CONTROLLED_DISCHARGE".equals(processType)) {
			properties.addProperty(CalibrationDischargeSampling.WALL_CLOCK_SECONDS_PROPERTY,
					CalibrationDischargeSampling.PERIODIC_WALL_CLOCK_SECONDS);
			properties.addProperty("safetyFloorVoltagePerCell", configuration.safetyFloorVoltagePerCell());
			properties.addProperty(DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY,
					DischargeStageMonitor.DEFAULT_MAX_WRONG_CURRENT_DIRECTION_SAMPLES);
		}
		if ("VOC_BASELINE_CAPTURE".equals(processType)) {
			properties.addProperty("vocCurrentDeadbandA", configuration.currentDeadbandA());
			properties.addProperty("policyRevision", 1);
		}
		if ("FLOAT_SETTLEMENT_TO_VFLOAT".equals(processType)
				|| "RECOVERY_FLOAT_STABILIZATION".equals(processType)) {
			properties.addProperty("policyRevision", 1);
		}
		return properties;
	}

	private static boolean hasFloatStablePolicy(Process process) {
		return process.type().contains("VBOOST") || process.type().contains("FLOAT");
	}

	private static void applyFloatStablePolicy(JsonObject properties, CalibrationEventConfiguration configuration) {
		properties.addProperty("floatVoltageToleranceV", configuration.floatVoltageToleranceV());
		properties.addProperty("floatCurrentMinA", configuration.floatCurrentMinA());
		properties.addProperty("floatCurrentMaxA", configuration.floatCurrentMaxA());
		properties.addProperty("floatStableDurationMinutes", configuration.floatStableDurationMinutes());
	}

	static void validateActiveProcessDefinitions(EventDetail detail) {
		if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
				&& detail.event().eventType() != CalibrationEventType.SIX_MONTH_INSPECTION) return;
		if (detail.event().status() == EventStatus.DRAFT) return;
		int expectedCount = 0;
		for (var stage : detail.stages()) {
			var definitions = CalibrationProcessDefinition.processes(detail.event().eventType(), stage.type());
			expectedCount += definitions.size();
			var actual = detail.processes().stream().filter(process -> process.calibrationStageId() == stage.id())
					.sorted(java.util.Comparator.comparingInt(Process::order)).toList();
			if (actual.size() != definitions.size()) throw incompatibleProcessRuntime();
			for (int index = 0; index < definitions.size(); index++) {
				var definition = definitions.get(index);
				var process = actual.get(index);
				if (process.order() != index || !definition.type().equals(process.type())
						|| !definition.samplingPolicy().name().equals(process.samplingPolicy())) {
					throw incompatibleProcessRuntime();
				}
				validateRequiredProcessProperties(detail.configuration(), process);
			}
		}
		if (detail.processes().size() != expectedCount) throw incompatibleProcessRuntime();
	}

	static void validateProcessStartPolicy(Stage stage, Process process) {
		boolean supported = switch (stage.type()) {
		case INITIAL_CHARGE -> process.order() == 0 && "BOOST_CHARGE_TO_VBOOST".equals(process.type())
				&& hasManualEvidence(stage.properties(), "CHARGE_SETPOINTS_REVIEWED");
		case PRE_DISCHARGE_FLOAT -> process.order() == 0 && "COOL_DOWN_TO_AMBIENT".equals(process.type())
				&& hasManualEvidence(stage.properties(), "CHARGER_MCB_OPEN_FOR_COOLDOWN");
		case RECHARGE -> process.order() == 0 && "RECOVERY_CHARGE_TO_VBOOST".equals(process.type())
				&& hasManualEvidence(stage.properties(), "RECOVERY_CHARGE_SETPOINTS_REVIEWED")
				&& hasManualEvidence(stage.properties(), "RECOVERY_CHARGER_MCB_CLOSED_CONFIRMED");
		case DISCHARGE -> process.order() == 0 && "CONTROLLED_DISCHARGE".equals(process.type());
		default -> false;
		};
		if (supported) return;
		if (stage.type() == CalibrationStageType.INITIAL_CHARGE
				|| stage.type() == CalibrationStageType.PRE_DISCHARGE_FLOAT
				|| stage.type() == CalibrationStageType.RECHARGE) {
			throw new IllegalArgumentException(
					"Record the required charge setup evidence on the current stage before starting its first Boost process");
		}
		throw new IllegalArgumentException("This process is started only by the authoritative calibration flow");
	}

	static void validateManualProcessCompletion(Process process) {
		if (CalibrationProcessDefinition.SamplingPolicy.FINAL_SNAPSHOT.name().equals(process.samplingPolicy())) {
			throw new IllegalArgumentException(
					"This automatic process is completed only by authoritative worker evaluation");
		}
	}

	static void validateRequiredProcessProperties(CalibrationEventConfiguration configuration, Process process) {
		CalibrationEventConfiguration processPolicy = configuration;
		if (hasFloatStablePolicy(process)) {
			if (process.configuration() == null || !process.configuration().isJsonObject()) throw incompatibleProcessRuntime();
			var properties = process.configuration().getAsJsonObject();
			try {
				processPolicy = configuration.withFloatStablePolicy(
						integerProcessProperty(properties, "floatStableDurationMinutes"),
						numberProcessProperty(properties, "floatVoltageToleranceV"),
						numberProcessProperty(properties, "floatCurrentMinA"),
						numberProcessProperty(properties, "floatCurrentMaxA"));
			} catch (IllegalArgumentException e) {
				throw incompatibleProcessRuntime();
			}
		}
		validateRequiredProcessProperties(configuration, process, processPolicy);
	}

	private static void validateRequiredProcessProperties(CalibrationEventConfiguration configuration, Process process,
			CalibrationEventConfiguration floatPolicy) {
		if (process.configuration() == null || !process.configuration().isJsonObject()) throw incompatibleProcessRuntime();
		var properties = process.configuration().getAsJsonObject();
		if (process.type().contains("VBOOST")) {
			requireProcessProperty(properties, "boostTargetVoltageV",
					configuration.boostVoltagePerCell() * configuration.cellCount());
		}
		if (process.type().contains("FLOAT")) {
			requireProcessProperty(properties, "floatTargetVoltageV",
					configuration.floatVoltagePerCell() * configuration.cellCount());
		}
		if (hasFloatStablePolicy(process)) {
			requireProcessProperty(properties, "floatVoltageToleranceV", floatPolicy.floatVoltageToleranceV());
			requireProcessProperty(properties, "floatCurrentMinA", floatPolicy.floatCurrentMinA());
			requireProcessProperty(properties, "floatCurrentMaxA", floatPolicy.floatCurrentMaxA());
			requireProcessProperty(properties, "floatStableDurationMinutes", floatPolicy.floatStableDurationMinutes());
			requireProcessProperty(properties, "chargeCurrentLimitA", configuration.chargeCurrentLimitA());
		}
	}

	private static int integerProcessProperty(JsonObject properties, String name) {
		var value = numberProcessProperty(properties, name);
		if (value > Integer.MAX_VALUE || Math.rint(value) != value) throw incompatibleProcessRuntime();
		return (int) value;
	}

	private static double numberProcessProperty(JsonObject properties, String name) {
		var value = properties.get(name);
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
				|| !value.getAsJsonPrimitive().isNumber()) throw incompatibleProcessRuntime();
		var number = value.getAsDouble();
		if (!Double.isFinite(number)) throw incompatibleProcessRuntime();
		return number;
	}

	private static void requireProcessProperty(JsonObject properties, String name, double expected) {
		var value = properties.get(name);
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
				|| !value.getAsJsonPrimitive().isNumber()) throw incompatibleProcessRuntime();
		double actual = value.getAsDouble();
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > 0.000_001) throw incompatibleProcessRuntime();
	}

	private static boolean hasManualEvidence(JsonElement properties, String evidenceType) {
		return manualEvidenceTypes(properties).contains(evidenceType);
	}

	private static IncompatibleLegacyProcessRuntimeException incompatibleProcessRuntime() {
		return new IncompatibleLegacyProcessRuntimeException(
				"This active calibration event uses an incompatible legacy process definition; "
				+ "abort it and create a new Initial Baseline event");
	}

	private static final class IncompatibleLegacyProcessRuntimeException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;

		private IncompatibleLegacyProcessRuntimeException(String message) {
			super(message);
		}
	}

	public EventDetail appendEvidence(ManualEvidenceCommand command, AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				var current = requireCurrentStage(detail, command.calibrationStageId());
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				if (detail.event().closedAt() != null) {
					throw new IllegalArgumentException("Closed calibration events cannot accept evidence");
				}
					CalibrationManualEvidencePolicy.validate(current.type(), command);
					validateInitialBaselineReviewEvidence(detail, current, command);
					validateDischargeCutoffReviewSample(c, detail, current, command);
					validateRecoveryFloatReviewSample(c, detail, current, command);
					var evidenceStatus = requireText(command.status(), "status");
					if (current.type() == CalibrationStageType.BASELINE_CAPTURE) {
						validateBaselineEvidence(c, detail, current, command, evidenceStatus);
					}
					try (var insert = c.prepareStatement("""
					INSERT INTO bms.calibration_manual_evidence
						(calibration_event_id,calibration_stage_id,evidence_type,status,numeric_value,text_value,unit,
						 instrument_id,instrument_resolution,instrument_accuracy,calibration_reference,actor_id,
							 actor_name,note,external_reference,calibration_sample_id)
						VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					""")) {
					insert.setLong(1, command.calibrationEventId());
					insert.setLong(2, command.calibrationStageId());
					insert.setString(3, requireText(command.evidenceType(), "evidenceType"));
					insert.setString(4, evidenceStatus);
					setNullableDouble(insert, 5, command.numericValue());
					insert.setString(6, trimToNull(command.textValue()));
					insert.setString(7, trimToNull(command.unit()));
					insert.setString(8, trimToNull(command.instrumentId()));
					setNullableDouble(insert, 9, command.instrumentResolution());
					setNullableDouble(insert, 10, command.instrumentAccuracy());
					insert.setString(11, trimToNull(command.calibrationReference()));
					insert.setObject(12, actor.id());
					insert.setString(13, actor.name());
					insert.setString(14, trimToNull(command.note()));
						insert.setString(15, trimToNull(command.externalReference()));
						setNullableLong(insert, 16, command.calibrationSampleId());
					insert.executeUpdate();
				}
				try (var stage = c.prepareStatement("""
					UPDATE bms.calibration_stage SET properties=jsonb_set(
						jsonb_set(properties,'{manualEvidenceRecorded}','true'::jsonb,true),
						'{manualEvidenceTypes}',
						CASE WHEN ? IN ('PASS','RECORDED') THEN COALESCE((
							SELECT jsonb_agg(value ORDER BY value) FROM (
								SELECT DISTINCT value FROM jsonb_array_elements_text(
									COALESCE(properties->'manualEvidenceTypes','[]'::jsonb)) value
								UNION SELECT ?::text
							) bounded_types), '[]'::jsonb)
						ELSE COALESCE(properties->'manualEvidenceTypes','[]'::jsonb) END,true),
						updated_at=now() WHERE id=?
					""")) {
					stage.setString(1, evidenceStatus);
					stage.setString(2, command.evidenceType().trim());
					stage.setLong(3, command.calibrationStageId());
					stage.executeUpdate();
				}
				JsonObject startedProcessPolicy = null;
				if (current.type() == CalibrationStageType.BASELINE_CAPTURE
						&& "CHARGER_MCB_OPEN_CONFIRMATION".equals(command.evidenceType().trim())
						&& ("PASS".equals(evidenceStatus) || "RECORDED".equals(evidenceStatus))) {
					startedProcessPolicy = startBaselineVocProcess(c, command.calibrationEventId(), command.calibrationStageId(), actor,
							command.expectedEventVersion() + 1);
				}
				updateEventAfterEvidence(c, command.calibrationEventId(), command.evidenceType().trim(), evidenceStatus);
				var audit = new JsonObject();
				audit.addProperty("evidenceType", command.evidenceType().trim());
				audit.addProperty("status", evidenceStatus);
				if (startedProcessPolicy != null) audit.add("startedProcessPolicy", startedProcessPolicy);
				appendTransition(c, command.calibrationEventId(), command.calibrationStageId(),
						requireCurrentStage(detail, command.calibrationStageId()).state().name(),
						requireCurrentStage(detail, command.calibrationStageId()).state().name(),
						"RECORD_MANUAL_EVIDENCE", command.note(), actor,
						command.expectedEventVersion() + 1, 0, audit);
				return loadDetail(c, command.calibrationEventId(), false);
			});
		}
	}

	/**
	 * Review evidence attests that an operator reviewed a worker-owned result. It
	 * never changes process state, so the corresponding process must already have
	 * completed before the acknowledgement can be recorded.
	 */
	static void validateInitialBaselineReviewEvidence(EventDetail detail, Stage stage, ManualEvidenceCommand command) {
		if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE) return;
		var type = command.evidenceType().trim();
		var requiredProcess = switch (type) {
		case "BOOST_TARGET_REVIEWED" -> "BOOST_CHARGE_TO_VBOOST";
		case "FLOAT_MODE_REVIEWED" -> "FLOAT_SETTLEMENT_TO_VFLOAT";
		case "AMBIENT_TEMPERATURE_REVIEWED" -> "COOL_DOWN_TO_AMBIENT";
		case "DISCHARGE_CUTOFF_REVIEWED" -> "CONTROLLED_DISCHARGE";
		case "RECOVERY_BOOST_TARGET_REVIEWED" -> "RECOVERY_CHARGE_TO_VBOOST";
		case "RECOVERY_FLOAT_STABILITY_REVIEWED" -> "RECOVERY_FLOAT_STABILIZATION";
		default -> null;
		};
		if (requiredProcess != null && detail.processes().stream().noneMatch(process ->
				process.calibrationStageId() == stage.id() && requiredProcess.equals(process.type())
						&& process.state() == CalibrationEventModels.ProcessState.COMPLETED)) {
			throw new IllegalArgumentException(
					"The corresponding automatic process must be completed before recording " + type);
		}
		if ("BASELINE_SUMMARY_REVIEWED".equals(type) && detail.processes().stream()
				.anyMatch(process -> process.state() != CalibrationEventModels.ProcessState.COMPLETED)) {
			throw new IllegalArgumentException(
					"All Initial Baseline processes must be completed before recording BASELINE_SUMMARY_REVIEWED");
		}
	}

	private static void validateDischargeCutoffReviewSample(Connection connection, EventDetail detail, Stage stage,
			ManualEvidenceCommand command) throws SQLException {
		if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
				|| stage.type() != CalibrationStageType.DISCHARGE
				|| !"DISCHARGE_CUTOFF_REVIEWED".equals(command.evidenceType().trim())) return;
		if (command.calibrationSampleId() == null) {
			throw new IllegalArgumentException("DISCHARGE_CUTOFF_REVIEWED requires calibrationSampleId");
		}
		try (var query = connection.prepareStatement("""
			SELECT count(*),
				(SELECT count(*) FROM bms.calibration_cell_sample cell WHERE cell.calibration_sample_id=s.id
					AND cell.cell_index BETWEEN 1 AND ? AND cell.voltage_v IS NOT NULL AND cell.quality='VALID')
			FROM bms.calibration_sample s JOIN bms.calibration_process p ON p.id=s.calibration_process_id
			WHERE s.id=? AND s.calibration_event_id=? AND s.calibration_stage_id=?
				AND p.process_type='CONTROLLED_DISCHARGE' AND p.state='COMPLETED'
				AND s.process_attempt_number=p.attempt_number AND p.started_at IS NOT NULL
				AND s.created_at>=p.started_at AND s.persistence_reason='CUTOFF' AND s.quality='VALID'
			GROUP BY s.id
			""")) {
			query.setInt(1, detail.configuration().cellCount());
			query.setLong(2, command.calibrationSampleId());
			query.setLong(3, detail.event().id());
			query.setLong(4, stage.id());
			try (var result = query.executeQuery()) {
				if (!result.next() || result.getInt(1) != 1
						|| result.getInt(2) != detail.configuration().cellCount()) {
					throw new IllegalArgumentException(
							"DISCHARGE_CUTOFF_REVIEWED requires the current attempt's complete valid CUTOFF sample");
				}
			}
		}
	}

	private static void validateRecoveryFloatReviewSample(Connection connection, EventDetail detail, Stage stage,
			ManualEvidenceCommand command) throws SQLException {
		if (detail.event().eventType() != CalibrationEventType.INITIAL_BASELINE
				|| stage.type() != CalibrationStageType.POST_RECHARGE_FLOAT) return;
		var type = command.evidenceType().trim();
		if (!Set.of("RECOVERY_FLOAT_STABILITY_REVIEWED", "CELL_RESISTANCE_SAMPLE_REVIEWED",
				"CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE")
				.contains(type)) return;
		if (command.calibrationSampleId() == null) {
			throw new IllegalArgumentException(type + " requires calibrationSampleId");
		}
		try (var query = connection.prepareStatement("""
			SELECT s.domain_current_a,s.midpoint_voltage_v,
				(SELECT count(*) FROM bms.calibration_cell_sample cell WHERE cell.calibration_sample_id=s.id),
				(SELECT count(*) FROM bms.calibration_cell_sample cell WHERE cell.calibration_sample_id=s.id
					AND cell.cell_index BETWEEN 1 AND ? AND cell.resistance_uohm IS NOT NULL
					AND cell.resistance_uohm>=0 AND cell.quality='VALID' AND cell.availability_warning IS NULL)
			FROM bms.calibration_sample s JOIN bms.calibration_process p ON p.id=s.calibration_process_id
			WHERE s.id=? AND s.calibration_event_id=? AND s.calibration_stage_id=?
				AND p.calibration_event_id=s.calibration_event_id AND p.calibration_stage_id=s.calibration_stage_id
				AND p.process_type='RECOVERY_FLOAT_STABILIZATION' AND p.state='COMPLETED'
				AND s.process_attempt_number=p.attempt_number AND p.started_at IS NOT NULL
				AND s.created_at>=p.started_at AND s.persistence_reason='FINAL_SNAPSHOT' AND s.quality='VALID'
				AND s.id=(SELECT canonical.id FROM bms.calibration_sample canonical
					WHERE canonical.calibration_process_id=p.id
						AND canonical.process_attempt_number=p.attempt_number
						AND canonical.persistence_reason='FINAL_SNAPSHOT' AND canonical.quality='VALID'
					ORDER BY canonical.measurement_time DESC,canonical.id DESC LIMIT 1)
			""")) {
			query.setInt(1, detail.configuration().cellCount());
			query.setLong(2, command.calibrationSampleId());
			query.setLong(3, detail.event().id());
			query.setLong(4, stage.id());
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException(
						type + " must link the current completed Recovery Float FINAL_SNAPSHOT");
				var current = result.getDouble(1);
				var currentMissing = result.wasNull();
				var midpoint = result.getDouble(2);
				var midpointMissing = result.wasNull();
				if ("CELL_RESISTANCE_SAMPLE_REVIEWED".equals(type)
						&& (result.getLong(3) != detail.configuration().cellCount()
								|| result.getLong(4) != detail.configuration().cellCount())) {
					throw new IllegalArgumentException(
							"Recovery Float snapshot must contain valid resistance for every configured cell");
				}
				if ("CHARGER_OUTPUT_CURRENT".equals(type)) {
					if (currentMissing || command.numericValue() == null
							|| !sameMeasurement(Math.abs(current), command.numericValue())) {
						throw new IllegalArgumentException(
								"CHARGER_OUTPUT_CURRENT must match the linked Recovery Float snapshot");
					}
				}
				if ("MIDPOINT_VOLTAGE".equals(type)) {
					if (midpointMissing || command.numericValue() == null
							|| !sameMeasurement(midpoint, command.numericValue())) {
						throw new IllegalArgumentException(
								"MIDPOINT_VOLTAGE must match the linked Recovery Float snapshot");
					}
				}
			}
		}
	}

	private static boolean sameMeasurement(double persisted, double submitted) {
		return Double.isFinite(persisted) && Double.isFinite(submitted)
				&& Math.abs(persisted - submitted) <= Math.max(1e-9, Math.abs(persisted) * 1e-9);
	}

	private static JsonObject startBaselineVocProcess(Connection connection, long eventId, long stageId,
			AuthenticatedPrincipal actor, long version) throws SQLException {
		try (var process = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now(),started_by=?,updated_at=now()
			WHERE calibration_event_id=? AND calibration_stage_id=? AND process_order=0 AND state='NOT_STARTED'
			RETURNING id
		""")) {
			process.setObject(1, actor.id());
			process.setLong(2, eventId);
			process.setLong(3, stageId);
			try (var result = process.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("VOC baseline process was changed concurrently");
				var processId = result.getLong(1);
				var evidence = appliedPolicyEvidence(connection, processId);
				appendProcessTransition(connection, eventId, stageId, processId,
						"NOT_STARTED", "ACTIVE", "START_VOC_AFTER_MCB_OPEN", null, actor, version, evidence);
				return evidence;
			}
		}
	}

	private static void validateBaselineEvidence(Connection connection, EventDetail detail, Stage stage,
			ManualEvidenceCommand command, String status) throws SQLException {
		if (!("PASS".equals(status) || "RECORDED".equals(status))) {
			throw new IllegalArgumentException("Baseline capture sequence accepts only PASS or RECORDED evidence");
		}
		var type = command.evidenceType().trim();
		var accepted = manualEvidenceTypes(stage.properties());
		if (accepted.contains(type)) {
			throw new IllegalArgumentException("Baseline capture evidence was already accepted: " + type);
		}
		var requiredPredecessors = switch (type) {
		case "LOAD_CONNECTED_CONFIRMATION" -> List.<String>of();
		case "RIPPLE_AFTER_LOAD" -> List.of("LOAD_CONNECTED_CONFIRMATION");
		case "CHARGER_MCB_OPEN_CONFIRMATION" -> List.of("LOAD_CONNECTED_CONFIRMATION", "RIPPLE_AFTER_LOAD");
		case "VOC_BASELINE_CONFIRMATION" -> List.of("LOAD_CONNECTED_CONFIRMATION", "RIPPLE_AFTER_LOAD",
				"CHARGER_MCB_OPEN_CONFIRMATION");
		default -> throw new IllegalArgumentException("Unsupported Baseline capture evidence: " + type);
		};
		if (!accepted.containsAll(requiredPredecessors)) {
			throw new IllegalArgumentException("Baseline capture evidence is out of order: " + type);
		}
		if ("RIPPLE_AFTER_LOAD".equals(type)) {
			if (command.numericValue() == null || !Double.isFinite(command.numericValue()) || command.numericValue() < 0) {
				throw new IllegalArgumentException("RIPPLE_AFTER_LOAD requires a finite non-negative numericValue");
			}
			if (!"V".equals(trimToNull(command.unit()))) {
				throw new IllegalArgumentException("RIPPLE_AFTER_LOAD unit must be V");
			}
		}
		if ("CHARGER_MCB_OPEN_CONFIRMATION".equals(type)) {
			requireBaselineVocProcessNotStarted(connection, detail.event().id(), stage.id());
		}
		if ("VOC_BASELINE_CONFIRMATION".equals(type)) {
			validateVocSampleLink(connection, detail, stage, command.calibrationSampleId());
		} else if (command.calibrationSampleId() != null) {
			throw new IllegalArgumentException("calibrationSampleId is valid only for VOC_BASELINE_CONFIRMATION");
		}
	}

	private static void requireBaselineVocProcessNotStarted(Connection connection, long eventId, long stageId)
			throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT count(*) FILTER (WHERE process_type='VOC_BASELINE_CAPTURE' AND state='NOT_STARTED'),count(*)
			FROM bms.calibration_process WHERE calibration_event_id=? AND calibration_stage_id=?
			""")) {
			query.setLong(1, eventId);
			query.setLong(2, stageId);
			try (var result = query.executeQuery()) {
				result.next();
				if (result.getLong(1) != 1 || result.getLong(2) != 1) {
					throw new IllegalArgumentException("VOC baseline process is unavailable or already started");
				}
			}
		}
	}

	private static Set<String> manualEvidenceTypes(JsonElement properties) {
		var result = new java.util.HashSet<String>();
		if (properties == null || !properties.isJsonObject()) return result;
		var values = properties.getAsJsonObject().get("manualEvidenceTypes");
		if (values != null && values.isJsonArray()) for (var value : values.getAsJsonArray()) {
			if (value.isJsonPrimitive()) result.add(value.getAsString());
		}
		return result;
	}

	private static void validateVocSampleLink(Connection connection, EventDetail detail, Stage stage, Long sampleId)
			throws SQLException {
		if (sampleId == null) throw new IllegalArgumentException("VOC_BASELINE_CONFIRMATION requires calibrationSampleId");
		try (var query = connection.prepareStatement("""
			SELECT s.id,
				(SELECT count(*) FROM bms.calibration_sample current_sample
				 WHERE current_sample.calibration_process_id=p.id AND current_sample.persistence_reason='FINAL_SNAPSHOT'
					AND current_sample.process_attempt_number=p.attempt_number
					AND current_sample.quality='VALID' AND current_sample.created_at>=p.started_at),
				(SELECT count(*) FROM bms.calibration_cell_sample cell WHERE cell.calibration_sample_id=s.id),
				(SELECT count(*) FROM bms.calibration_cell_sample cell WHERE cell.calibration_sample_id=s.id
					AND cell.cell_index BETWEEN 1 AND ? AND cell.voltage_v IS NOT NULL
					AND cell.quality='VALID' AND cell.availability_warning IS NULL)
			FROM bms.calibration_sample s JOIN bms.calibration_process p ON p.id=s.calibration_process_id
			WHERE s.id=? AND s.calibration_event_id=? AND s.calibration_stage_id=?
				AND p.calibration_event_id=s.calibration_event_id AND p.calibration_stage_id=s.calibration_stage_id
				AND p.process_type='VOC_BASELINE_CAPTURE' AND p.state='COMPLETED'
				AND s.process_attempt_number=p.attempt_number
				AND p.started_at IS NOT NULL AND s.created_at>=p.started_at
				AND s.persistence_reason='FINAL_SNAPSHOT' AND s.quality='VALID'
				AND s.raw_current_a IS NOT NULL AND s.domain_current_a IS NOT NULL
				AND s.current_measured_at IS NOT NULL AND s.current_quality='FRESH'
				AND s.string_voltage_v IS NOT NULL
			""")) {
			query.setInt(1, detail.configuration().cellCount());
			query.setLong(2, sampleId);
			query.setLong(3, detail.event().id());
			query.setLong(4, stage.id());
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException(
						"calibrationSampleId is not the current completed VOC baseline snapshot");
				if (result.getLong(2) != 1) throw new IllegalArgumentException(
						"VOC baseline process must contain exactly one valid FINAL_SNAPSHOT");
				if (result.getLong(3) != detail.configuration().cellCount()
						|| result.getLong(4) != detail.configuration().cellCount()) {
					throw new IllegalArgumentException("VOC baseline snapshot must contain valid data for every configured cell");
				}
			}
		}
	}

	public EventDetail executeAction(ExecuteActionCommand command, AuthenticatedPrincipal actor,
			Function<EventDetail, List<AllowedAction>> actionResolver) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				var current = requireCurrentStage(detail, command.calibrationStageId());
				var option = actionResolver.apply(detail).stream().filter(a -> a.code() == command.actionCode()).findFirst()
						.orElseThrow(() -> new IllegalArgumentException("Action is not available for the current stage"));
				if (!option.enabled()) {
					throw new IllegalArgumentException(option.disabledReason());
				}
				CalibrationCommandValidator.validatePayload(option, command);
				long newVersion = detail.event().eventVersion() + 1;
				applyAction(c, detail, current, command, actor, newVersion);
				return loadDetail(c, command.calibrationEventId(), false);
			});
		}
	}

	public EventDetail redoStage(RedoStageCommand command, AuthenticatedPrincipal actor) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var detail = loadDetail(c, command.calibrationEventId(), true);
				CalibrationCommandValidator.requireVersion(detail, command.expectedEventVersion());
				if (detail.event().closedAt() != null || !isOpenForRedo(detail.event().status())) {
					throw new CalibrationConflictException("Only open calibration events can redo a stage");
				}
				var reason = requireText(command.reason(), "reason");
				var current = detail.stages().stream()
						.filter(stage -> detail.event().currentStageId() != null
								&& stage.id() == detail.event().currentStageId()).findFirst()
						.orElseThrow(() -> new CalibrationConflictException("Current calibration stage is unavailable"));
				var target = detail.stages().stream().filter(stage -> stage.id() == command.calibrationStageId()).findFirst()
						.orElseThrow(() -> new IllegalArgumentException("Unknown calibration stage"));
				boolean safetyStoppedCurrent = target.id() == current.id() && target.type() == CalibrationStageType.DISCHARGE
						&& target.state() == StageState.BLOCKED && detail.processes().stream()
								.anyMatch(process -> process.calibrationStageId() == target.id()
										&& "CONTROLLED_DISCHARGE".equals(process.type())
										&& process.dischargeRuntime() != null
										&& "STOP_CONFIRMED".equals(process.dischargeRuntime().getAsJsonObject()
												.get("safetyState").getAsString()));
				if (!safetyStoppedCurrent
						&& (target.state() != StageState.COMPLETED || target.order() >= current.order())) {
					throw new IllegalArgumentException("Only an earlier completed stage or safety-stopped current discharge can be redone");
				}
				int attempt = integer(target.properties(), "attemptNumber", 0) + 1;
				// The schema normally makes terminal stages immutable. Authorize only this
				// locked event and the target/later stage range for this transaction; the
				// trigger still rejects every mutation once the event is closed.
				try (var authorize = c.prepareStatement("""
					SELECT set_config('bms.calibration.redo_event_id',?,true),
						set_config('bms.calibration.redo_stage_order',?,true)
					""")) {
					authorize.setString(1, Long.toString(detail.event().id()));
					authorize.setString(2, Integer.toString(target.order()));
					authorize.execute();
				}
				// Clear the old current stage before activating the target. PostgreSQL checks
				// the partial one-ACTIVE-stage index row by row, so a single CASE update can
				// transiently create two ACTIVE rows depending on its physical row order.
				try (var reset = c.prepareStatement("""
					UPDATE bms.calibration_stage SET state='NOT_STARTED',properties='{}'::jsonb,
						result=NULL,blocker_code=NULL,blocker_detail=NULL,started_at=NULL,
						completed_at=NULL,completed_by=NULL,updated_at=now()
					WHERE calibration_event_id=? AND stage_order>=?
					""")) {
					reset.setLong(1, detail.event().id());
					reset.setInt(2, target.order());
					reset.executeUpdate();
				}
				try (var resetProcesses = c.prepareStatement("""
					UPDATE bms.calibration_process process SET state='NOT_STARTED',started_at=NULL,completed_at=NULL,
						started_by=NULL,completed_by=NULL,
						attempt_number=attempt_number+1,
						properties=process.properties-ARRAY['checkpoint','blockers','recommendation','latestMeasurement',
							'completionReason']::text[],updated_at=now()
					FROM bms.calibration_stage stage
					WHERE process.calibration_stage_id=stage.id AND stage.calibration_event_id=? AND stage.stage_order>=?
					""")) {
					resetProcesses.setLong(1, detail.event().id());
					resetProcesses.setInt(2, target.order());
					resetProcesses.executeUpdate();
				}
				try (var activate = c.prepareStatement("""
					UPDATE bms.calibration_stage SET state='ACTIVE',
						properties=jsonb_build_object('attemptNumber',?::integer),started_at=now(),updated_at=now()
					WHERE id=?
					""")) {
					activate.setInt(1, attempt);
					activate.setLong(2, target.id());
					activate.executeUpdate();
				}
				long newVersion = detail.event().eventVersion() + 1;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_event SET status='ACTIVE',current_stage_id=?,event_version=?,
						blockers='[]'::jsonb,missing_evidence=?,updated_at=now() WHERE id=?
					""")) {
					update.setLong(1, target.id());
					update.setLong(2, newVersion);
					update.setObject(3, jsonb(GSON.toJsonTree(initialMissingEvidence(detail.event().eventType()))));
					update.setLong(4, detail.event().id());
					update.executeUpdate();
				}
				var evidence = new JsonObject();
				evidence.addProperty("attemptNumber", attempt);
				evidence.addProperty("previousCurrentStageId", current.id());
				appendTransition(c, detail.event().id(), target.id(), target.state().name(), "ACTIVE", "REDO_STAGE",
						reason, actor, newVersion, 0, evidence);
				return loadDetail(c, detail.event().id(), false);
			});
		}
	}

	public List<WorkerEvent> activeWorkerEvents() throws SQLException {
		var incompatibleEventIds = incompatibleActiveWorkerEventIds();
		var events = new ArrayList<WorkerEvent>();
		try (var connection = this.database.getConnection(); var query = connection.prepareStatement("""
			SELECT e.id,st.id,e.string_id,s.string_key,e.configuration_snapshot,
				COALESCE((SELECT CASE WHEN prerequisite.properties->>'demoTimeScaleFactor' IN ('1','60')
					THEN (prerequisite.properties->>'demoTimeScaleFactor')::integer ELSE 1 END
					FROM bms.calibration_stage prerequisite
					WHERE prerequisite.calibration_event_id=e.id AND prerequisite.stage_type='PREREQUISITES'),1),
				st.stage_type,st.state,st.properties,
				pm.event_id,pm.float_current_a,pm.measurement_time,cp.id,cp.attempt_number,cp.process_type,
				cp.state,cp.sampling_policy,cp.properties
				,rt.checkpoint_data,dr.per_cell_state,cutoff.per_cell_state,
				dr.safety_state,dr.frozen_used_ah,dr.safety_trigger_cells
			FROM bms.calibration_event e JOIN bms.calibration_stage st ON st.id=e.current_stage_id
			LEFT JOIN bms.calibration_process cp ON cp.calibration_stage_id=st.id AND cp.state IN ('ACTIVE','BLOCKED')
			JOIN bms.bms_string s ON s.id=e.string_id
			LEFT JOIN LATERAL (
				SELECT pe.id AS event_id,abs(cs.domain_current_a) AS float_current_a,cs.measurement_time
				FROM bms.calibration_event pe
				JOIN bms.calibration_stage ps ON ps.calibration_event_id=pe.id
				JOIN bms.calibration_sample cs ON cs.calibration_stage_id=ps.id
				WHERE pe.string_id=e.string_id AND pe.id<>e.id AND pe.event_type='MONTHLY_INSPECTION'
					AND pe.status='COMPLETED' AND ps.stage_type='MONTHLY_INSPECTION'
					AND cs.quality='VALID' AND cs.domain_current_a IS NOT NULL
				ORDER BY pe.closed_at DESC NULLS LAST,pe.id DESC,cs.measurement_time DESC,cs.id DESC LIMIT 1
			) pm ON true
			LEFT JOIN bms.calibration_process_runtime rt
				ON rt.calibration_process_id=cp.id AND rt.attempt_number=cp.attempt_number
			LEFT JOIN bms.calibration_discharge_runtime dr
				ON dr.calibration_process_id=cp.id AND dr.attempt_number=cp.attempt_number
			LEFT JOIN LATERAL (
				SELECT jsonb_object_agg(crossing.cell_index::text,jsonb_build_object(
					'lastVoltageV',crossing.cutoff_voltage_v,
					'lastRawUsedAh',crossing.cell_usable_capacity_ah,
					'cutoffReached',true,
					'cutoffVoltageV',crossing.cutoff_voltage_v,
					'cellUsableCapacityAh',crossing.cell_usable_capacity_ah)) AS per_cell_state
				FROM bms.calibration_cell_cutoff_crossing crossing
				WHERE crossing.calibration_process_id=cp.id
					AND crossing.process_attempt_number=cp.attempt_number
			) cutoff ON true
			WHERE e.status IN ('ACTIVE','BLOCKED') AND st.state IN ('ACTIVE','BLOCKED')
				AND (e.event_type NOT IN ('INITIAL_BASELINE','SIX_MONTH_INSPECTION') OR cp.id IS NOT NULL)
				AND (dr.safety_state IS NULL OR dr.safety_state<>'STOP_CONFIRMED')
			"""); var result = query.executeQuery()) {
			while (result.next()) {
				long eventId = result.getLong(1);
				if (incompatibleEventIds.contains(eventId)) continue;
				events.add(new WorkerEvent(eventId, result.getLong(2), result.getObject(3, UUID.class),
						result.getString(4), configuration(result.getString(5)), result.getInt(6),
						CalibrationStageType.valueOf(result.getString(7)), StageState.valueOf(result.getString(8)),
						parse(result.getString(9)), result.getObject(10, Long.class), number(result, 11),
						timestampMillis(result.getTimestamp(12)), result.getObject(13, Long.class),
						result.getObject(14, Integer.class),
						result.getString(15),
						result.getString(16) == null ? null : ProcessState.valueOf(result.getString(16)), result.getString(17),
						parse(result.getString(18)), dischargeCheckpoint(parse(result.getString(19)), result.getString(20),
								result.getString(21), result.getString(22), number(result, 23), result.getString(24))));
			}
		}
		return List.copyOf(events);
	}

	private static JsonElement dischargeCheckpoint(JsonElement checkpoint, String perCellState, String cutoffState,
			String safetyState, Double frozenUsedAh, String safetyTriggerCells) {
		if (checkpoint == null || !checkpoint.isJsonObject()) return checkpoint;
		var result = checkpoint.getAsJsonObject().deepCopy();
		var merged = perCellState == null || !parse(perCellState).isJsonObject()
				? new JsonObject() : parse(perCellState).getAsJsonObject().deepCopy();
		var cutoffs = cutoffState == null ? null : parse(cutoffState);
		if (cutoffs != null && cutoffs.isJsonObject()) {
			for (var entry : cutoffs.getAsJsonObject().entrySet()) merged.add(entry.getKey(), entry.getValue().deepCopy());
		}
		result.add("perCellState", merged);
		if (safetyState != null) result.addProperty("safetyState", safetyState);
		if (frozenUsedAh != null) result.addProperty("frozenUsedAh", frozenUsedAh);
		result.add("safetyTriggers", safetyTriggerCells == null ? new JsonArray() : parse(safetyTriggerCells));
		return result;
	}

	/**
	 * An incompatible legacy aggregate remains command-readable so an operator can
	 * abort it, but must not stop polling for other active aggregates.
	 */
	private Set<Long> incompatibleActiveWorkerEventIds() throws SQLException {
		var incompatibleEventIds = new java.util.HashSet<Long>();
		try (var connection = this.database.getConnection()) {
			var eventIds = new ArrayList<Long>();
			try (var query = connection.prepareStatement("""
				SELECT id FROM bms.calibration_event
				WHERE event_type IN ('INITIAL_BASELINE','SIX_MONTH_INSPECTION')
					AND status IN ('ACTIVE','BLOCKED')
				"""); var result = query.executeQuery()) {
				while (result.next()) eventIds.add(result.getLong(1));
			}
			for (var eventId : eventIds) {
				try {
					validateActiveProcessDefinitions(loadDetail(connection, eventId, false));
				} catch (IncompatibleLegacyProcessRuntimeException e) {
					incompatibleEventIds.add(eventId);
					if (this.warnedIncompatibleWorkerEvents.add(eventId)) {
						LOG.warn("Skipping incompatible legacy calibration event {} during worker polling: {}", eventId,
								e.getMessage());
					}
				}
			}
		}
		this.warnedIncompatibleWorkerEvents.retainAll(incompatibleEventIds);
		return incompatibleEventIds;
	}

	/** Atomically deduplicates and stores one worker evaluation without changing eventVersion. */
	public boolean persistWorkerEvaluation(WorkerEvent expected, SampleWrite write) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				try (var lock = c.prepareStatement("SELECT current_stage_id,status FROM bms.calibration_event WHERE id=? FOR UPDATE")) {
					lock.setLong(1, expected.eventId());
					try (var result = lock.executeQuery()) {
						if (!result.next() || result.getLong(1) != expected.stageId()
								|| !("ACTIVE".equals(result.getString(2)) || "BLOCKED".equals(result.getString(2)))) {
							return false;
						}
					}
				}
				if (expected.processId() != null && !isActiveProcess(c, expected)) return false;
				Long lastCurrentMeasuredAt = null;
				Long lastPersistedAt = null;
				boolean currentStaleAlreadyPersisted = false;
				boolean stageWasBlocked = false;
				List<String> persistedBlockers = List.of();
				boolean runtimeExists = false;
				String persistedQualitySignature = null;
				UUID runtimeSnapshotGeneration = null;
				Long runtimeSnapshotSequence = null;
				try (var latest = c.prepareStatement("""
					SELECT process.properties #>> '{checkpoint,lastCurrentMeasuredAt}',
						latest.current_measured_at,periodic.created_at,
						COALESCE(jsonb_exists(stage.blocker_detail,'CURRENT_STALE'),false),
						stage.blocker_code IS NOT NULL,stage.blocker_detail,
						runtime.calibration_process_id IS NOT NULL,runtime.last_current_measured_at,
						runtime.last_periodic_persisted_at,runtime.quality_signature,
						runtime.snapshot_generation,runtime.snapshot_sequence
					FROM bms.calibration_stage stage
					LEFT JOIN bms.calibration_process process ON process.id=?
					LEFT JOIN bms.calibration_process_runtime runtime
						ON runtime.calibration_process_id=process.id AND runtime.attempt_number=process.attempt_number
					LEFT JOIN LATERAL
						(SELECT current_measured_at FROM bms.calibration_sample
							 WHERE calibration_event_id=? AND calibration_stage_id=?
								AND calibration_process_id IS NOT DISTINCT FROM ?
							 ORDER BY id DESC LIMIT 1) latest ON true
					LEFT JOIN LATERAL
						(SELECT sample.created_at FROM bms.calibration_sample sample
							 WHERE sample.calibration_event_id=? AND sample.calibration_stage_id=?
								AND sample.calibration_process_id IS NOT DISTINCT FROM ?
								AND (sample.persistence_reason='PERIODIC_SAMPLE'
								OR EXISTS (SELECT 1 FROM bms.calibration_sample_reason reason
									WHERE reason.calibration_sample_id=sample.id
									AND reason.persistence_reason='PERIODIC_SAMPLE'))
							 ORDER BY sample.id DESC LIMIT 1) periodic ON true
					WHERE stage.id=?
					""")) {
					setNullableLong(latest, 1, expected.processId());
					latest.setLong(2, expected.eventId());
					latest.setLong(3, expected.stageId());
					setNullableLong(latest, 4, expected.processId());
					latest.setLong(5, expected.eventId());
					latest.setLong(6, expected.stageId());
					setNullableLong(latest, 7, expected.processId());
					latest.setLong(8, expected.stageId());
					try (var result = latest.executeQuery()) {
						if (result.next()) {
							var persistedMeasuredAt = result.getTimestamp(2);
							lastCurrentMeasuredAt = latestEvaluatedCurrentMeasuredAt(result.getString(1),
									persistedMeasuredAt == null ? null : persistedMeasuredAt.toInstant().toEpochMilli());
							var persistedAt = result.getTimestamp(3);
							lastPersistedAt = persistedAt == null ? null : persistedAt.toInstant().toEpochMilli();
							currentStaleAlreadyPersisted = result.getBoolean(4);
							stageWasBlocked = result.getBoolean(5);
							persistedBlockers = strings(parse(result.getString(6)));
							runtimeExists = result.getBoolean(7);
							if (runtimeExists) {
								var runtimeMeasuredAt = result.getTimestamp(8);
								lastCurrentMeasuredAt = runtimeMeasuredAt == null ? null
										: runtimeMeasuredAt.toInstant().toEpochMilli();
								var runtimePeriodicAt = result.getTimestamp(9);
								lastPersistedAt = runtimePeriodicAt == null ? null
										: runtimePeriodicAt.toInstant().toEpochMilli();
								persistedQualitySignature = result.getString(10);
								runtimeSnapshotGeneration = result.getObject(11, UUID.class);
								runtimeSnapshotSequence = result.getObject(12, Long.class);
							}
						}
					}
				}
				// The worker can poll faster than the calculator publishes snapshots. An
				// immutable snapshot that is already represented by runtime is not a new
				// measurement and must not be re-evaluated as a quality transition. In
				// particular, persisting MEASUREMENT_SEQUENCE_REPEATED here would make a
				// healthy discharge alternate INVALID/VALID on every calculator cycle.
				if (runtimeExists
						&& java.util.Objects.equals(runtimeSnapshotGeneration,
								write.measurement().snapshotGeneration())
						&& java.util.Objects.equals(runtimeSnapshotSequence,
								write.measurement().snapshotSequence())) {
					return false;
				}
				if (CalibrationRetainedCurrentPolicy.shouldSkip(expected.configuration(), write.measurement(),
						write.evaluatedAtMillis(), lastCurrentMeasuredAt, currentStaleAlreadyPersisted)) {
					return false;
				}
				var samplingPolicy = expected.samplingPolicy() == null ? CalibrationProcessDefinition.SamplingPolicy.PERIODIC
						: CalibrationProcessDefinition.SamplingPolicy.valueOf(expected.samplingPolicy());
				int persistenceIntervalSeconds = expected.stageType() == CalibrationStageType.DISCHARGE
						? CalibrationDischargeSampling.wallClockSeconds(expected.demoTimeScaleFactor())
						: expected.configuration().sampleIntervalSeconds();
				String qualitySignature = qualitySignature(write);
				boolean qualityChanged = runtimeExists
						? !java.util.Objects.equals(persistedQualitySignature, qualitySignature)
						: !persistedBlockers.equals(write.evaluation().blockers().stream().map(Enum::name).toList());
				var persistenceReasons = CalibrationSamplePersistencePolicy.reasonsToPersist(write.evaluation(),
						write.evaluatedAtMillis(), lastPersistedAt, qualityChanged, samplingPolicy,
						persistenceIntervalSeconds);
				boolean persistSample = !persistenceReasons.isEmpty();
				boolean historyChanged = false;
				Long sampleId = null;
				if (persistSample) {
					long insertedSampleId = insertSample(c, expected, write, persistenceReasons);
					if (insertedSampleId < 0) {
						sampleId = sampleId(c, expected.eventId(), write.measurement().snapshotGeneration(),
								write.measurement().snapshotSequence());
					} else {
						sampleId = insertedSampleId;
						insertSampleReasons(c, sampleId, persistenceReasons, write.evaluation().milestones());
						insertCells(c, sampleId, write.measurement(),
								expected.configuration().eventType() != CalibrationEventType.INITIAL_BASELINE);
						historyChanged = true;
					}
				}
				if (write.evaluation().checkpoint() instanceof DischargeStageMonitor.Checkpoint discharge
						&& !discharge.newCutoffEndpoints().isEmpty()) {
					if (sampleId == null) throw new IllegalStateException("Cutoff endpoint requires a persisted sample");
					historyChanged |= insertCutoffEndpoints(c, expected, write, sampleId, discharge.newCutoffEndpoints());
				}
				upsertProcessRuntime(c, expected, write, qualitySignature, lastPersistedAt,
						persistenceReasons.contains(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE));
				var blockers = GSON.toJsonTree(write.evaluation().blockers());
				var state = write.evaluation().blockers().isEmpty() ? StageState.ACTIVE : StageState.BLOCKED;
				boolean automaticCompletion = expected.processId() != null && state == StageState.ACTIVE
						&& write.evaluation().recommendation() == CalibrationStageEvaluation.Recommendation.READY_TO_COMPLETE;
				if (expected.processId() != null) {
					updateProcessEvaluation(c, expected, write, state);
					if (isInitialBaselineControlledDischarge(expected)) {
						var capacity = refreshDischargeCapacitySummary(c, expected);
						if (state == StageState.ACTIVE && !isDischargeSafetyLatched(c, expected)
								&& capacity.crossedCellCount() == capacity.configuredCellCount()) {
							if (completeAllCellsControlledDischarge(c, expected)) {
								publishReferenceCurve(c, expected, capacity);
								historyChanged = true;
							}
						}
					}
					if (automaticCompletion && samplingPolicy == CalibrationProcessDefinition.SamplingPolicy.FINAL_SNAPSHOT
							&& allowsAutomaticFinalSnapshotCompletion(expected)) {
						try (var complete = c.prepareStatement("""
							UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
							WHERE id=? AND state='ACTIVE'
							""")) {
							complete.setLong(1, expected.processId());
							if (complete.executeUpdate() == 1) {
								var evidence = automaticPolicyEvidence(c, expected, sampleId, write);
								appendAutomaticProcessTransition(c, expected.eventId(), expected.stageId(),
										expected.processId(), "ACTIVE", "COMPLETED", "AUTO_FINAL_SNAPSHOT", evidence);
								appendAutomaticStageTransition(c, expected.eventId(), expected.stageId(), "ACTIVE", "ACTIVE",
										"AUTO_FINAL_SNAPSHOT", evidence);
							}
						}
					}
				}
				boolean blockerStateChanged = (state == StageState.BLOCKED) != stageWasBlocked;
				if (!historyChanged && !blockerStateChanged) return false;
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_stage SET properties=properties || ?,state=?,blocker_code=?,blocker_detail=?,updated_at=now()
					WHERE id=? AND state IN ('ACTIVE','BLOCKED')
					""")) {
					update.setObject(1, jsonb(stageProperties(write.evaluation())));
					update.setString(2, state.name());
					update.setString(3, state == StageState.ACTIVE || write.evaluation().blockers().isEmpty() ? null
							: write.evaluation().blockers().get(0).name());
					update.setObject(4, jsonb(blockers));
					update.setLong(5, expected.stageId());
					if (update.executeUpdate() != 1) return false;
				}
				try (var update = c.prepareStatement("""
					UPDATE bms.calibration_event SET status=?,blockers=?,measurement_revision=measurement_revision+1,
						updated_at=now() WHERE id=?
					""")) {
					update.setString(1, state == StageState.ACTIVE ? "ACTIVE" : "BLOCKED");
					update.setObject(2, jsonb(blockers));
					update.setLong(3, expected.eventId());
					update.executeUpdate();
				}
				if (automaticCompletion) autoAdvanceBoostToFloat(c, expected,
						automaticPolicyEvidence(c, expected, sampleId, write));
				return true;
			});
		}
	}

	private static boolean isInitialBaselineControlledDischarge(WorkerEvent event) {
		return event.processId() != null && event.processAttemptNumber() != null
				&& event.configuration().eventType() == CalibrationEventType.INITIAL_BASELINE
				&& event.stageType() == CalibrationStageType.DISCHARGE;
	}

	private static boolean isDischargeSafetyLatched(Connection connection, WorkerEvent event) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT safety_state FROM bms.calibration_discharge_runtime
			WHERE calibration_process_id=? AND attempt_number=?
			""")) {
			query.setLong(1, event.processId());
			query.setInt(2, event.processAttemptNumber());
			try (var result = query.executeQuery()) {
				return result.next() && !"CLEAR".equals(result.getString(1));
			}
		}
	}

	/**
	 * Rebuilds the capacity projection from immutable cutoff rows. The projection is
	 * never trusted as the completion source; it is refreshed from the authoritative
	 * aggregate in the same worker transaction.
	 */
	private static DischargeCapacitySummary refreshDischargeCapacitySummary(Connection connection,
			WorkerEvent event) throws SQLException {
		int crossedCellCount;
		Double stringSafeUsableCapacityAh;
		Double allCellsEndpointAh;
		try (var query = connection.prepareStatement("""
			SELECT count(*),min(cell_usable_capacity_ah),max(cell_usable_capacity_ah)
			FROM bms.calibration_cell_cutoff_crossing
			WHERE calibration_process_id=? AND process_attempt_number=?
			""")) {
			query.setLong(1, event.processId());
			query.setInt(2, event.processAttemptNumber());
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Discharge capacity aggregate was not returned");
				crossedCellCount = result.getInt(1);
				stringSafeUsableCapacityAh = number(result, 2);
				allCellsEndpointAh = number(result, 3);
			}
		}
		int configuredCellCount = event.configuration().cellCount();
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_discharge_runtime
			SET crossed_cell_count=?,configured_cell_count=?,string_safe_usable_capacity_ah=?,
				all_cells_endpoint_ah=?,updated_at=now()
			WHERE calibration_process_id=? AND attempt_number=?
			""")) {
			update.setInt(1, crossedCellCount);
			update.setInt(2, configuredCellCount);
			setNullableDouble(update, 3, stringSafeUsableCapacityAh);
			setNullableDouble(update, 4, allCellsEndpointAh);
			update.setLong(5, event.processId());
			update.setInt(6, event.processAttemptNumber());
			if (update.executeUpdate() != 1) {
				throw new IllegalStateException("Discharge runtime was not available for capacity summary");
			}
		}
		return new DischargeCapacitySummary(crossedCellCount, configuredCellCount,
				stringSafeUsableCapacityAh, allCellsEndpointAh);
	}

	private static boolean completeAllCellsControlledDischarge(Connection connection, WorkerEvent event)
			throws SQLException {
		try (var complete = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
			WHERE id=? AND attempt_number=? AND state='ACTIVE'
			""")) {
			complete.setLong(1, event.processId());
			complete.setInt(2, event.processAttemptNumber());
			if (complete.executeUpdate() != 1) return false;
		}
		appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), event.processId(),
				"ACTIVE", "COMPLETED", "AUTO_ALL_CELLS_CUTOFF");
		return true;
	}

	/**
	 * Publishes the immutable Initial Baseline curve in the same transaction that
	 * completes ALL_CELLS. A malformed source aggregate throws and therefore rolls
	 * back process completion and the active-baseline pointer together.
	 */
	private static void publishReferenceCurve(Connection connection, WorkerEvent event,
			DischargeCapacitySummary capacity) throws SQLException {
		if (event.configuration().eventType() != CalibrationEventType.INITIAL_BASELINE
				|| event.stageType() != CalibrationStageType.DISCHARGE
				|| event.configuration().capacityReferenceAh() <= 0
				|| capacity.stringSafeUsableCapacityAh() == null || capacity.allCellsEndpointAh() == null
				|| capacity.crossedCellCount() != event.configuration().cellCount()) {
			throw new IllegalStateException("Initial Baseline curve source is incomplete");
		}
		try (var safety = connection.prepareStatement("""
			SELECT process.state,runtime.safety_state,process.process_type
			FROM bms.calibration_process process
			JOIN bms.calibration_discharge_runtime runtime
				ON runtime.calibration_process_id=process.id AND runtime.attempt_number=process.attempt_number
			WHERE process.id=? AND process.calibration_event_id=? AND process.calibration_stage_id=?
				AND process.attempt_number=?
			""")) {
			safety.setLong(1, event.processId());
			safety.setLong(2, event.eventId());
			safety.setLong(3, event.stageId());
			safety.setInt(4, event.processAttemptNumber());
			try (var result = safety.executeQuery()) {
				if (!result.next() || !"COMPLETED".equals(result.getString(1))
						|| !"CLEAR".equals(result.getString(2))
						|| !"CONTROLLED_DISCHARGE".equals(result.getString(3))) {
					throw new IllegalStateException("Initial Baseline curve requires completed safe discharge");
				}
			}
		}

		var candidates = loadCurveSourcePoints(connection, event.processId(), event.processAttemptNumber(),
				event.configuration().capacityReferenceAh());
		var points = withoutDerivedEstimates(
				prepareCurveSourcePoints(candidates, event.configuration().cellCount()));
		String contentHash = InitialBaselineCurveIntegrity.contentHash(
				new InitialBaselineCurveIntegrity.Header(1, event.eventId(), event.stringId(), event.processId(),
						event.processAttemptNumber(), event.configuration().capacityReferenceAh(),
						capacity.stringSafeUsableCapacityAh(), capacity.allCellsEndpointAh(),
						capacity.configuredCellCount()),
				integrityPoints(points));
		long curveVersionId;
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_curve_version
				(calibration_event_id,string_id,calibration_stage_id,calibration_process_id,
				 process_attempt_number,curve_schema_version,capacity_reference_ah,
				 string_safe_usable_capacity_ah,all_cells_endpoint_ah,cell_count,point_count,content_hash)
			VALUES (?,?,?,?,?,1,?,?,?,?,?,?)
			ON CONFLICT (calibration_process_id,process_attempt_number) DO NOTHING RETURNING id
			""")) {
			insert.setLong(1, event.eventId());
			insert.setObject(2, event.stringId());
			insert.setLong(3, event.stageId());
			insert.setLong(4, event.processId());
			insert.setInt(5, event.processAttemptNumber());
			insert.setDouble(6, event.configuration().capacityReferenceAh());
			insert.setDouble(7, capacity.stringSafeUsableCapacityAh());
			insert.setDouble(8, capacity.allCellsEndpointAh());
			insert.setInt(9, event.configuration().cellCount());
			insert.setInt(10, points.size());
			insert.setString(11, contentHash);
			try (var result = insert.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Reference curve was already published for this attempt");
				curveVersionId = result.getLong(1);
			}
		}
		insertCurvePoints(connection, curveVersionId, points);
		try (var activate = connection.prepareStatement("""
			INSERT INTO bms.calibration_active_baseline
				(string_id,curve_version_id,calibration_event_id,activated_at)
			VALUES (?,?,?,now())
			ON CONFLICT (string_id) DO UPDATE SET curve_version_id=EXCLUDED.curve_version_id,
				calibration_event_id=EXCLUDED.calibration_event_id,activated_at=EXCLUDED.activated_at
			""")) {
			activate.setObject(1, event.stringId());
			activate.setLong(2, curveVersionId);
			activate.setLong(3, event.eventId());
			activate.executeUpdate();
		}
	}

	public static List<CurveSourcePoint> prepareCurveSourcePoints(List<CurveSourcePoint> candidates, int cellCount) {
		var byCell = new LinkedHashMap<Integer, List<CurveSourcePoint>>();
		for (var candidate : candidates) {
			if (!(candidate.cellUsableCapacityAh() > 0) || !Double.isFinite(candidate.cellUsableCapacityAh())) {
				throw new IllegalStateException("Reference curve cell capacity must be positive and finite");
			}
			byCell.computeIfAbsent(candidate.cellIndex(), ignored -> new ArrayList<>()).add(candidate);
		}
		if (byCell.size() != cellCount) {
			throw new IllegalStateException("Reference curve is missing one or more configured cells");
		}
		var points = new ArrayList<CurveSourcePoint>();
		for (int cellIndex = 1; cellIndex <= cellCount; cellIndex++) {
			var source = byCell.get(cellIndex);
			if (source == null || source.stream().noneMatch(CurveSourcePoint::cutoff)) {
				throw new IllegalStateException("Reference curve is missing cutoff endpoint for cell " + cellIndex);
			}
			var bySample = new LinkedHashMap<Long, CurveSourcePoint>();
			for (var candidate : source) {
				if (candidate.dischargedAh() > candidate.cellUsableCapacityAh() + 1e-9) continue;
				var previous = bySample.get(candidate.sampleId());
				if (previous == null || candidate.cutoff()) bySample.put(candidate.sampleId(), candidate);
			}
			var deduped = new LinkedHashMap<String, CurveSourcePoint>();
			for (var candidate : bySample.values()) {
				var key = Double.toString(candidate.milestonePercent());
				var previous = deduped.get(key);
				if (previous == null || candidate.cutoff()) deduped.put(key, candidate);
			}
			var ordered = new ArrayList<>(deduped.values());
			ordered.sort(Comparator.comparingDouble(CurveSourcePoint::dischargedAh)
					.thenComparingLong(CurveSourcePoint::sampleId));
			if (ordered.isEmpty() || ordered.stream().noneMatch(CurveSourcePoint::cutoff)) {
				throw new IllegalStateException("Reference curve has no usable cutoff endpoint for cell " + cellIndex);
			}
			points.addAll(ordered);
		}
		return List.copyOf(points);
	}

	/**
	 * Removes transient calculator estimates from a newly published baseline curve.
	 * Capacity/cutoff and physical Vcurve measurements are the source evidence.
	 */
	public static List<CurveSourcePoint> withoutDerivedEstimates(List<CurveSourcePoint> points) {
		return points.stream().map(point -> new CurveSourcePoint(point.cellIndex(), point.sampleId(),
				point.milestonePercent(), point.dischargedAh(), point.cellVoltageV(), point.cellTemperatureC(),
				point.cellResistanceUohm(), null, null, point.stringVoltageV(), point.averageVoltageV(),
				point.quality(), point.cellUsableCapacityAh(), point.cutoff())).toList();
	}

	public static List<CurveSourcePoint> loadCurveSourcePoints(Connection connection, long processId, int processAttemptNumber,
			double capacityReferenceAh) throws SQLException {
		var points = new ArrayList<CurveSourcePoint>();
		try (var query = connection.prepareStatement("""
			WITH source AS (
				SELECT crossing.cell_index,sample.id AS sample_id,reason.milestone_value::double precision AS milestone,
					sample.raw_used_ah::double precision AS discharged_ah,cell.voltage_v::double precision,
					cell.temperature_c::double precision,cell.resistance_uohm,cell.soc_pct::double precision,
					cell.soh_pct::double precision,sample.string_voltage_v::double precision,
					sample.average_voltage_v::double precision,sample.quality,
					crossing.cell_usable_capacity_ah::double precision AS cell_capacity,false AS cutoff
				FROM bms.calibration_cell_cutoff_crossing crossing
				JOIN bms.calibration_sample sample ON sample.calibration_process_id=crossing.calibration_process_id
					AND sample.process_attempt_number=crossing.process_attempt_number
				JOIN bms.calibration_sample_reason reason ON reason.calibration_sample_id=sample.id
					AND reason.persistence_reason='DOD_MILESTONE_5_PERCENT'
				JOIN bms.calibration_cell_sample cell ON cell.calibration_sample_id=sample.id
					AND cell.cell_index=crossing.cell_index
				WHERE crossing.calibration_process_id=? AND crossing.process_attempt_number=?
					AND sample.quality='VALID' AND cell.quality='VALID' AND sample.raw_used_ah IS NOT NULL
					AND cell.voltage_v IS NOT NULL AND reason.milestone_value IS NOT NULL
				UNION ALL
				SELECT crossing.cell_index,sample.id,
					(sample.raw_used_ah/?*100)::double precision,sample.raw_used_ah::double precision,
					cell.voltage_v::double precision,cell.temperature_c::double precision,cell.resistance_uohm,
					cell.soc_pct::double precision,cell.soh_pct::double precision,
					sample.string_voltage_v::double precision,sample.average_voltage_v::double precision,sample.quality,
					crossing.cell_usable_capacity_ah::double precision,true
				FROM bms.calibration_cell_cutoff_crossing crossing
				JOIN bms.calibration_sample sample ON sample.id=crossing.after_sample_id
				JOIN bms.calibration_cell_sample cell ON cell.calibration_sample_id=sample.id
					AND cell.cell_index=crossing.cell_index
				WHERE crossing.calibration_process_id=? AND crossing.process_attempt_number=?
					AND sample.quality='VALID' AND cell.quality='VALID' AND sample.raw_used_ah IS NOT NULL
					AND cell.voltage_v IS NOT NULL
			)
			SELECT cell_index,sample_id,milestone,discharged_ah,voltage_v,temperature_c,resistance_uohm,
				soc_pct,soh_pct,string_voltage_v,average_voltage_v,quality,cell_capacity,cutoff
			FROM source ORDER BY cell_index,discharged_ah,sample_id,cutoff
			""")) {
			query.setLong(1, processId);
			query.setInt(2, processAttemptNumber);
			query.setDouble(3, capacityReferenceAh);
			query.setLong(4, processId);
			query.setInt(5, processAttemptNumber);
			try (var result = query.executeQuery()) {
				while (result.next()) points.add(new CurveSourcePoint(result.getInt(1), result.getLong(2),
						result.getDouble(3), result.getDouble(4), result.getDouble(5), number(result, 6),
						result.getObject(7, Integer.class), number(result, 8), number(result, 9), number(result, 10),
						number(result, 11), result.getString(12), result.getDouble(13), result.getBoolean(14)));
			}
		}
		return points;
	}

	private static void insertCurvePoints(Connection connection, long curveVersionId,
			List<CurveSourcePoint> points) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_curve_point
				(curve_version_id,cell_index,point_order,calibration_sample_id,milestone_percent,
				 normalized_actual_dod_percent,discharged_ah,cell_voltage_v,cell_temperature_c,
				 cell_resistance_uohm,cell_soc_pct,cell_soh_pct,string_voltage_v,average_voltage_v,quality,usable)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true)
			""")) {
			var orderByCell = new java.util.HashMap<Integer, Integer>();
			for (var point : points) {
				insert.setLong(1, curveVersionId);
				insert.setInt(2, point.cellIndex());
				insert.setInt(3, orderByCell.merge(point.cellIndex(), 1, Integer::sum) - 1);
				insert.setLong(4, point.sampleId());
				insert.setDouble(5, point.milestonePercent());
				insert.setDouble(6, point.dischargedAh() / point.cellUsableCapacityAh() * 100.0);
				insert.setDouble(7, point.dischargedAh());
				insert.setDouble(8, point.cellVoltageV());
				setNullableDouble(insert, 9, point.cellTemperatureC());
				if (point.cellResistanceUohm() == null) insert.setNull(10, Types.INTEGER);
				else insert.setInt(10, point.cellResistanceUohm());
				// Runtime estimates are not baseline evidence. Keep the legacy columns
				// nullable for one-release read compatibility, but new curves are derived
				// only from capacity/cutoff and physical Vcurve measurements.
				insert.setNull(11, Types.NUMERIC);
				insert.setNull(12, Types.NUMERIC);
				setNullableDouble(insert, 13, point.stringVoltageV());
				setNullableDouble(insert, 14, point.averageVoltageV());
				insert.setString(15, point.quality());
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	public static List<InitialBaselineCurveIntegrity.Point> integrityPoints(List<CurveSourcePoint> points) {
		var result = new ArrayList<InitialBaselineCurveIntegrity.Point>(points.size());
		var orderByCell = new java.util.HashMap<Integer, Integer>();
		for (var point : points) {
			int order = orderByCell.merge(point.cellIndex(), 1, Integer::sum) - 1;
			result.add(new InitialBaselineCurveIntegrity.Point(point.cellIndex(), order, point.sampleId(),
					point.milestonePercent(), point.dischargedAh() / point.cellUsableCapacityAh() * 100.0,
					point.dischargedAh(), point.cellVoltageV(), point.cellTemperatureC(), point.cellResistanceUohm(),
					point.cellSocPct(), point.cellSohPct(), point.stringVoltageV(), point.averageVoltageV(),
					point.quality(), true, point.cutoff()));
		}
		return List.copyOf(result);
	}

	public static record CurveSourcePoint(int cellIndex, long sampleId, double milestonePercent, double dischargedAh,
			double cellVoltageV, Double cellTemperatureC, Integer cellResistanceUohm, Double cellSocPct,
			Double cellSohPct, Double stringVoltageV, Double averageVoltageV, String quality,
			double cellUsableCapacityAh, boolean cutoff) {
	}

	private record DischargeCapacitySummary(int crossedCellCount, int configuredCellCount,
			Double stringSafeUsableCapacityAh, Double allCellsEndpointAh) {
	}

	/**
	 * Charge-pair processes use target properties to sequence Boost and Float.
	 * Cool-down completion is selected by its stage type, never by a historical
	 * JSON property, so a stale target cannot keep an in-range thermal process
	 * ACTIVE forever.
	 */
	static boolean allowsAutomaticFinalSnapshotCompletion(WorkerEvent event) {
		if (event.processType() != null) {
			return !Set.of("BOOST_CHARGE_TO_VBOOST", "FLOAT_SETTLEMENT_TO_VFLOAT",
					"RECOVERY_CHARGE_TO_VBOOST", "RECOVERY_FLOAT_STABILIZATION").contains(event.processType());
		}
		// Compatibility for in-memory callers created before WorkerEvent exposed the
		// authoritative process_type column.
		if (event.stageType() == CalibrationStageType.PRE_DISCHARGE_FLOAT) return true;
		if (event.processProperties() == null || !event.processProperties().isJsonObject()) return true;
		var properties = event.processProperties().getAsJsonObject();
		return !properties.has("boostTargetVoltageV") && !properties.has("floatTargetVoltageV");
	}

	/** The worker changes only physical process/stage state; it never sends a charger command. */
	private static void autoAdvanceBoostToFloat(Connection connection, WorkerEvent event, JsonObject completionEvidence)
			throws SQLException {
		if (event.processId() == null) return;
		if ("FLOAT_SETTLEMENT_TO_VFLOAT".equals(event.processType())
				|| "RECOVERY_FLOAT_STABILIZATION".equals(event.processType())) {
			if (event.stageType() == CalibrationStageType.INITIAL_CHARGE) {
				completeAutomaticProcess(connection, event, "AUTO_FLOAT_STABLE", completionEvidence);
				return;
			}
			try (var complete = connection.prepareStatement("""
				UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
				WHERE id=? AND state='ACTIVE'
				""")) {
				complete.setLong(1, event.processId());
				if (complete.executeUpdate() == 1) {
					appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), event.processId(),
							"ACTIVE", "COMPLETED", "AUTO_FLOAT_STABLE", completionEvidence);
					appendAutomaticStageTransition(connection, event.eventId(), event.stageId(), "ACTIVE", "ACTIVE",
							"AUTO_FLOAT_STABLE", completionEvidence);
				}
			}
			return;
		}
		if (!"BOOST_CHARGE_TO_VBOOST".equals(event.processType())
				&& !"RECOVERY_CHARGE_TO_VBOOST".equals(event.processType())) return;
		Long nextId = null;
		try (var query = connection.prepareStatement("""
			SELECT id FROM bms.calibration_process WHERE calibration_stage_id=? AND process_order=(
				SELECT process_order+1 FROM bms.calibration_process WHERE id=?) AND state='NOT_STARTED'
			""")) {
			query.setLong(1, event.stageId());
			query.setLong(2, event.processId());
			try (var result = query.executeQuery()) { if (result.next()) nextId = result.getLong(1); }
		}
		if (nextId == null) {
			if (event.stageType() == CalibrationStageType.RECHARGE) {
				autoAdvanceRecoveryBoostToFloat(connection, event, completionEvidence);
			}
			return;
		}
		try (var complete = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
			WHERE id=? AND state='ACTIVE'
			""")) {
			complete.setLong(1, event.processId());
			if (complete.executeUpdate() != 1) return;
		}
		try (var start = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now(),updated_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			start.setLong(1, nextId);
			start.executeUpdate();
		}
		appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), event.processId(), "ACTIVE",
				"COMPLETED", "AUTO_REACHED_VBOOST");
		appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), nextId, "NOT_STARTED", "ACTIVE",
				"AUTO_START_FLOAT", appliedPolicyEvidence(connection, nextId));
		appendAutomaticStageTransition(connection, event.eventId(), event.stageId(), "ACTIVE", "ACTIVE",
				"AUTO_START_FLOAT", appliedPolicyEvidence(connection, nextId));
	}

	private static void completeAutomaticProcess(Connection connection, WorkerEvent event, String action,
			JsonElement evidence)
			throws SQLException {
		try (var complete = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
			WHERE id=? AND state='ACTIVE'
			""")) {
			complete.setLong(1, event.processId());
			if (complete.executeUpdate() != 1) return;
		}
		appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), event.processId(), "ACTIVE",
				"COMPLETED", action, evidence);
		appendAutomaticStageTransition(connection, event.eventId(), event.stageId(), "ACTIVE", "ACTIVE", action,
				evidence);
	}

	/**
	 * Recovery's Float process belongs to the following persisted stage. Once
	 * Vboost is reached, advance the two persisted stages and start that Float
	 * process as one worker transaction. No charger command or operator
	 * confirmation is implied by this state-only transition.
	 */
	private static void autoAdvanceRecoveryBoostToFloat(Connection connection, WorkerEvent event,
			JsonObject completionEvidence) throws SQLException {
		Long floatStageId = null;
		Long floatProcessId = null;
		try (var query = connection.prepareStatement("""
			SELECT stage.id,process.id FROM bms.calibration_stage stage
			JOIN bms.calibration_process process ON process.calibration_stage_id=stage.id
			WHERE stage.calibration_event_id=? AND stage.stage_type='POST_RECHARGE_FLOAT'
				AND stage.state='NOT_STARTED' AND process.process_order=0 AND process.state='NOT_STARTED'
			""")) {
			query.setLong(1, event.eventId());
			try (var result = query.executeQuery()) {
				if (result.next()) {
					floatStageId = result.getLong(1);
					floatProcessId = result.getLong(2);
				}
			}
		}
		if (floatStageId == null || floatProcessId == null) return;
		try (var complete = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='COMPLETED',completed_at=now(),updated_at=now()
			WHERE id=? AND state='ACTIVE'
			""")) {
			complete.setLong(1, event.processId());
			if (complete.executeUpdate() != 1) return;
		}
		updateStageState(connection, event.stageId(), "COMPLETED", "PASS", null, "AUTO_REACHED_VBOOST", null,
				new JsonObject());
		try (var activate = connection.prepareStatement("""
			UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now(),updated_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			activate.setLong(1, floatStageId);
			if (activate.executeUpdate() != 1) return;
		}
		try (var start = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now(),updated_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			start.setLong(1, floatProcessId);
			if (start.executeUpdate() != 1) throw new IllegalStateException("Recovery Float process was changed concurrently");
		}
		try (var eventUpdate = connection.prepareStatement("""
			UPDATE bms.calibration_event SET status='ACTIVE',current_stage_id=?,blockers='[]'::jsonb,updated_at=now()
			WHERE id=?
			""")) {
			eventUpdate.setLong(1, floatStageId);
			eventUpdate.setLong(2, event.eventId());
			eventUpdate.executeUpdate();
		}
		appendAutomaticProcessTransition(connection, event.eventId(), event.stageId(), event.processId(), "ACTIVE",
				"COMPLETED", "AUTO_REACHED_VBOOST", completionEvidence);
		appendAutomaticStageTransition(connection, event.eventId(), event.stageId(), "ACTIVE", "COMPLETED",
				"AUTO_REACHED_VBOOST");
		appendAutomaticStageTransition(connection, event.eventId(), floatStageId, "NOT_STARTED", "ACTIVE",
				"AUTO_ACTIVATE_FLOAT_STAGE");
		appendAutomaticProcessTransition(connection, event.eventId(), floatStageId, floatProcessId, "NOT_STARTED", "ACTIVE",
				"AUTO_START_FLOAT", appliedPolicyEvidence(connection, floatProcessId));
		appendAutomaticStageTransition(connection, event.eventId(), floatStageId, "ACTIVE", "ACTIVE",
				"AUTO_START_FLOAT", appliedPolicyEvidence(connection, floatProcessId));
	}

	private static void appendAutomaticProcessTransition(Connection connection, long eventId, long stageId, long processId,
			String fromState, String toState, String action) throws SQLException {
		appendAutomaticProcessTransition(connection, eventId, stageId, processId, fromState, toState, action,
				new JsonObject());
	}

	private static void appendAutomaticProcessTransition(Connection connection, long eventId, long stageId, long processId,
			String fromState, String toState, String action, JsonElement evidence) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_process_transition
				(calibration_event_id,calibration_stage_id,calibration_process_id,from_state,to_state,action,
				 resulting_event_version,evidence)
			VALUES (?,?,?,?,?,?,(SELECT event_version FROM bms.calibration_event WHERE id=?),?::jsonb)
			""")) {
			insert.setLong(1, eventId); insert.setLong(2, stageId); insert.setLong(3, processId);
			insert.setString(4, fromState); insert.setString(5, toState); insert.setString(6, action); insert.setLong(7, eventId);
			insert.setObject(8, jsonb(evidence == null ? new JsonObject() : evidence));
			insert.executeUpdate();
		}
	}

	private static void appendAutomaticStageTransition(Connection connection, long eventId, long stageId,
			String fromState, String toState, String action) throws SQLException {
		appendAutomaticStageTransition(connection, eventId, stageId, fromState, toState, action, new JsonObject());
	}

	private static void appendAutomaticStageTransition(Connection connection, long eventId, long stageId,
			String fromState, String toState, String action, JsonElement evidence) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_transition
				(calibration_event_id,calibration_stage_id,from_state,to_state,action,evidence,
				 resulting_event_version,transition_order)
			VALUES (?,?,?,?,?,?::jsonb,(SELECT event_version FROM bms.calibration_event WHERE id=?),
				(SELECT COALESCE(max(transition_order)+1,0) FROM bms.calibration_transition
				 WHERE calibration_event_id=? AND resulting_event_version=(SELECT event_version FROM bms.calibration_event WHERE id=?)))
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setString(3, fromState);
			insert.setString(4, toState);
			insert.setString(5, action);
			insert.setObject(6, jsonb(evidence == null ? new JsonObject() : evidence));
			insert.setLong(7, eventId);
			insert.setLong(8, eventId);
			insert.setLong(9, eventId);
			insert.executeUpdate();
		}
	}

	private static JsonObject automaticPolicyEvidence(Connection connection, WorkerEvent event, Long sampleId,
			SampleWrite write) throws SQLException {
		var evidence = new JsonObject();
		String processType;
		JsonObject applied;
		try (var query = connection.prepareStatement("SELECT process_type,properties FROM bms.calibration_process WHERE id=?")) {
			query.setLong(1, event.processId());
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Completed process was not found");
				processType = result.getString(1);
				var parsed = parse(result.getString(2));
				applied = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
			}
		}
		evidence.addProperty("processType", processType);
		evidence.addProperty("attemptNumber", event.processAttemptNumber());
		evidence.addProperty("policyRevision", policyRevision(applied));
		evidence.add("appliedPolicy", applied.deepCopy());
		if (sampleId != null) evidence.addProperty("calibrationSampleId", sampleId);
		var measurement = new JsonObject();
		measurement.addProperty("snapshotSequence", write.measurement().snapshotSequence());
		if (write.measurement().domainCurrentA() != null) {
			measurement.addProperty("domainCurrentA", write.measurement().domainCurrentA());
		}
		if (write.measurement().stringVoltageV() != null) {
			measurement.addProperty("stringVoltageV", write.measurement().stringVoltageV());
		}
		measurement.addProperty("configuredCellCount", write.measurement().configuredCellCount());
		evidence.add("finalMeasurement", measurement);
		return evidence;
	}

	/** Marks restart-sensitive stages for explicit operator revalidation. */
	public int requirePhysicalStageRevalidation() throws SQLException {
		try (var connection = this.database.getConnection()) {
			return transaction(connection, c -> {
				var ids = new ArrayList<Long>();
				try (var query = c.prepareStatement("""
					SELECT e.id,s.id,e.event_version,s.state FROM bms.calibration_event e
					JOIN bms.calibration_stage s ON s.id=e.current_stage_id
					WHERE e.closed_at IS NULL AND e.status IN ('ACTIVE','BLOCKED') AND s.stage_type IN
						('INITIAL_CHARGE','PRE_DISCHARGE_FLOAT','WAITING_FOR_LOAD','DISCHARGE','LOAD_DISCONNECT','RECHARGE','POST_RECHARGE_FLOAT')
					FOR UPDATE OF e
					"""); var result = query.executeQuery()) {
					while (result.next()) {
						long eventId = result.getLong(1);
						long stageId = result.getLong(2);
						long version = result.getLong(3) + 1;
						ids.add(eventId);
						appendSystemTransition(c, eventId, stageId, result.getString(4),
								"REVALIDATION_REQUIRED", "EDGE_RESTART_REVALIDATION_REQUIRED", version);
					}
				}
				for (var id : ids) {
					try (var stage = c.prepareStatement("""
						UPDATE bms.calibration_stage SET state='REVALIDATION_REQUIRED',updated_at=now()
						WHERE id=(SELECT current_stage_id FROM bms.calibration_event WHERE id=?)
						""")) {
						stage.setLong(1, id);
						stage.executeUpdate();
					}
					try (var event = c.prepareStatement("""
						UPDATE bms.calibration_event SET status='REVALIDATION_REQUIRED',restart_revalidation_required=true,
							event_version=event_version+1,updated_at=now() WHERE id=?
						""")) {
						event.setLong(1, id);
						event.executeUpdate();
					}
				}
				return ids.size();
			});
		}
	}

	private static void applyAction(Connection connection, EventDetail detail, Stage current,
			ExecuteActionCommand command, AuthenticatedPrincipal actor, long newVersion) throws SQLException {
		var action = command.actionCode();
		var evidence = new JsonObject();
		evidence.add("acknowledgedEvidence", GSON.toJsonTree(command.acknowledgedEvidence()));
		if (action == ActionCode.REVALIDATE_STAGE) {
			updateStageState(connection, current.id(), "ACTIVE", null, null, null, null, null);
			try (var event = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='ACTIVE',restart_revalidation_required=false,
					event_version=?,updated_at=now() WHERE id=?
				""")) {
				event.setLong(1, newVersion);
				event.setLong(2, detail.event().id());
				event.executeUpdate();
			}
			appendTransition(connection, detail.event().id(), current.id(), current.state().name(), "ACTIVE",
					action.name(), command.reason(), actor, newVersion, 0, evidence);
			return;
		}
		if (action == ActionCode.FAIL_STAGE) {
			updateStageState(connection, current.id(), "FAILED", "FAIL", actor.id(), action.name(), command.reason(), evidence);
			updateEventAfterTerminal(connection, detail.event().id(), "BLOCKED", current.id(), null, newVersion);
			appendTransition(connection, detail.event().id(), current.id(), current.state().name(), "FAILED", action.name(),
					command.reason(), actor, newVersion, 0, evidence);
			return;
		}
		if (action == ActionCode.ABORT_EVENT) {
			updateStageState(connection, current.id(), "FAILED", "FAIL", actor.id(), action.name(), command.reason(), evidence);
			updateEventAfterTerminal(connection, detail.event().id(), "ABORTED", null, Instant.now(), newVersion);
			appendTransition(connection, detail.event().id(), current.id(), current.state().name(), "FAILED", action.name(),
					command.reason(), actor, newVersion, 0, evidence);
			return;
		}

		var terminalState = action == ActionCode.SKIP_STAGE ? "SKIPPED" : "COMPLETED";
		var result = switch (action) {
		case SKIP_STAGE -> "NOT_APPLICABLE";
		case COMPLETE_WITH_GAPS -> "COMPLETED_WITH_GAPS";
		default -> "PASS";
		};
		updateStageState(connection, current.id(), terminalState, result, actor.id(), action.name(), command.reason(), evidence);
		appendTransition(connection, detail.event().id(), current.id(), current.state().name(), terminalState, action.name(),
				command.reason(), actor, newVersion, 0, evidence);
		int index = detail.stages().indexOf(current);
		if (current.type() == CalibrationStageType.COMPLETION) {
			appendTransition(connection, detail.event().id(), current.id(), terminalState, terminalState,
					"CLOSE_EVENT", command.reason(), actor, newVersion, 1, evidence);
			var report = buildReport(connection, detail.event().id(), newVersion);
			insertReport(connection, detail.event().id(), actor.id(), report);
			try (var event = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='COMPLETED',current_stage_id=NULL,event_version=?,completed_at=now(),
					closed_at=now(),final_result=?,
					baseline_approved_at=CASE WHEN event_type='INITIAL_BASELINE' THEN now() ELSE baseline_approved_at END,
					baseline_approved_by=CASE WHEN event_type='INITIAL_BASELINE' THEN ? ELSE baseline_approved_by END,
					baseline_approved_by_name=CASE WHEN event_type='INITIAL_BASELINE' THEN ? ELSE baseline_approved_by_name END,
					updated_at=now() WHERE id=?
				""")) {
				event.setLong(1, newVersion);
				event.setObject(2, jsonb(report));
				event.setObject(3, actor.id());
				event.setString(4, actor.name());
				event.setLong(5, detail.event().id());
				event.executeUpdate();
			}
			return;
		}
		var next = detail.stages().get(index + 1);
		try (var stage = connection.prepareStatement("""
			UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now(),started_by=?,updated_at=now() WHERE id=?
			""")) {
			stage.setObject(1, actor.id());
			stage.setLong(2, next.id());
			stage.executeUpdate();
		}
		updateEventAfterTerminal(connection, detail.event().id(), "ACTIVE", next.id(), null, newVersion);
		appendTransition(connection, detail.event().id(), next.id(), "NOT_STARTED", "ACTIVE", "ACTIVATE_STAGE", null,
				actor, newVersion, 1, new JsonObject());
	}

	private static void updateStageState(Connection connection, long stageId, String state, String result,
			UUID actorId, String acceptedAction, String reason, JsonElement evidence) throws SQLException {
		var properties = evidence == null ? new JsonObject() : evidence.deepCopy();
		if (properties.isJsonObject() && acceptedAction != null) {
			properties.getAsJsonObject().addProperty("acceptedAction", acceptedAction);
			properties.getAsJsonObject().addProperty("acceptedReason", reason);
		}
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_stage SET state=?,result=?,completed_by=?,
				completed_at=CASE WHEN ? IN ('COMPLETED','SKIPPED','FAILED') THEN now() ELSE NULL END,
				properties=properties || ?,updated_at=now() WHERE id=?
			""")) {
			update.setString(1, state);
			update.setString(2, result);
			update.setObject(3, actorId);
			update.setString(4, state);
			update.setObject(5, jsonb(properties));
			update.setLong(6, stageId);
			update.executeUpdate();
		}
	}

	private static void updateEventAfterTerminal(Connection connection, long eventId, String status,
			Long currentStageId, Instant closedAt, long version) throws SQLException {
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_event SET status=?,current_stage_id=?,closed_at=?,event_version=?,updated_at=now() WHERE id=?
			""")) {
			update.setString(1, status);
			setNullableLong(update, 2, currentStageId);
			update.setTimestamp(3, closedAt == null ? null : Timestamp.from(closedAt));
			update.setLong(4, version);
			update.setLong(5, eventId);
			update.executeUpdate();
		}
	}

	private static JsonObject buildReport(Connection connection, long eventId, long version) throws SQLException {
		var report = new JsonObject();
		report.addProperty("reportSchemaVersion", 2);
		report.addProperty("calibrationEventId", eventId);
		report.addProperty("resultingEventVersion", version);
		try (var query = connection.prepareStatement("""
			SELECT event_type,string_id,configuration_snapshot,reference_calibration_event_id,
				created_by,created_by_name,started_at,missing_evidence
			FROM bms.calibration_event WHERE id=?
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				result.next();
				report.addProperty("eventType", result.getString(1));
				report.addProperty("stringId", result.getObject(2, UUID.class).toString());
				report.addProperty("finalStatus", "COMPLETED");
				report.add("configuration", parse(result.getString(3)));
				addNullable(report, "referenceCalibrationEventId", result.getObject(4, Long.class));
				report.addProperty("createdBy", result.getObject(5, UUID.class).toString());
				report.addProperty("createdByName", result.getString(6));
				addNullable(report, "startedAt", instant(result.getTimestamp(7)));
				report.add("missingEvidence", parse(result.getString(8)));
			}
		}
		var stages = new JsonArray();
		try (var query = connection.prepareStatement("""
			SELECT stage_order,stage_type,state,result,properties FROM bms.calibration_stage
			WHERE calibration_event_id=? ORDER BY stage_order
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var stage = new JsonObject();
					stage.addProperty("order", result.getInt(1));
					stage.addProperty("type", result.getString(2));
					stage.addProperty("state", result.getString(3));
					stage.addProperty("result", result.getString(4));
					stage.add("properties", parse(result.getString(5)));
					stages.add(stage);
				}
			}
		}
		report.add("stages", stages);
		var auditManifest = auditManifest(connection, eventId);
		report.add("auditManifest", auditManifest);
		report.addProperty("auditManifestSha256", sha256(GSON.toJson(auditManifest)));
		report.add("latestTelemetryEvidence", latestEventTelemetryEvidence(connection, eventId));
		return report;
	}

	private static JsonObject auditManifest(Connection connection, long eventId) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT
				(SELECT count(*) FROM bms.calibration_transition WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_manual_evidence WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_sample WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_sample_reason reason JOIN bms.calibration_sample sample
					ON sample.id=reason.calibration_sample_id WHERE sample.calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_cell_sample c JOIN bms.calibration_sample s
					ON s.id=c.calibration_sample_id WHERE s.calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_cell_cutoff_crossing WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_curve_version WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_curve_point point JOIN bms.calibration_curve_version curve
					ON curve.id=point.curve_version_id WHERE curve.calibration_event_id=?),
				(SELECT min(id) FROM bms.calibration_sample WHERE calibration_event_id=?),
				(SELECT max(id) FROM bms.calibration_sample WHERE calibration_event_id=?),
				(SELECT min(measurement_time) FROM bms.calibration_sample WHERE calibration_event_id=?),
				(SELECT max(measurement_time) FROM bms.calibration_sample WHERE calibration_event_id=?)
			""")) {
			for (var parameter = 1; parameter <= 12; parameter++) {
				query.setLong(parameter, eventId);
			}
			try (var result = query.executeQuery()) {
				result.next();
				var manifest = new JsonObject();
				manifest.addProperty("transitionCount", result.getLong(1));
				manifest.addProperty("manualEvidenceCount", result.getLong(2));
				manifest.addProperty("sampleCount", result.getLong(3));
				manifest.addProperty("sampleReasonCount", result.getLong(4));
				manifest.addProperty("cellSampleCount", result.getLong(5));
				manifest.addProperty("cellCutoffCrossingCount", result.getLong(6));
				manifest.addProperty("curveVersionCount", result.getLong(7));
				manifest.addProperty("curvePointCount", result.getLong(8));
				addNullable(manifest, "firstSampleId", result.getObject(9, Long.class));
				addNullable(manifest, "lastSampleId", result.getObject(10, Long.class));
				addNullable(manifest, "firstMeasurementAt", instant(result.getTimestamp(11)));
				addNullable(manifest, "lastMeasurementAt", instant(result.getTimestamp(12)));
				manifest.addProperty("historyMethod", "bms.calibration.history");
				manifest.addProperty("samplesMethod", "bms.calibration.samples");
				manifest.addProperty("auditStorage", "bms.calibration_transition,bms.calibration_manual_evidence,bms.calibration_sample,bms.calibration_sample_reason,bms.calibration_cell_sample,bms.calibration_cell_cutoff_crossing,bms.calibration_curve_version,bms.calibration_curve_point");
				return manifest;
			}
		}
	}

	private static JsonElement latestEventTelemetryEvidence(Connection connection, long eventId) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT to_jsonb(sample_record) FROM (
				SELECT s.*,COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.cell_index)
					FROM bms.calibration_cell_sample c WHERE c.calibration_sample_id=s.id),'[]'::jsonb) AS cells
				FROM bms.calibration_sample s WHERE s.calibration_event_id=?
				ORDER BY s.measurement_time DESC,s.id DESC LIMIT 1
			) sample_record
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				return result.next() ? parse(result.getString(1)) : com.google.gson.JsonNull.INSTANCE;
			}
		}
	}

	private static void insertReport(Connection connection, long eventId, UUID actor, JsonObject report)
			throws SQLException {
		var canonical = GSON.toJson(report);
		var hash = sha256(canonical);
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_report(calibration_event_id,report_schema_version,report_data,generated_by,content_hash)
			VALUES (?,2,?,?,?)
			""")) {
			insert.setLong(1, eventId);
			insert.setObject(2, jsonb(report));
			insert.setObject(3, actor);
			insert.setString(4, hash);
			insert.executeUpdate();
		}
	}

	private static long insertSample(Connection connection, WorkerEvent expected, SampleWrite write,
			List<CalibrationStageEvaluation.PersistenceReason> persistenceReasons)
			throws SQLException {
		var measurement = write.measurement();
		// Evaluators list the regular sample first and exceptional evidence later;
		// preserve the strongest reason (cutoff/target/milestone/peak) in the row.
		var reason = persistenceReasons.get(persistenceReasons.size() - 1);
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_sample
				(calibration_event_id,calibration_stage_id,calibration_process_id,process_attempt_number,snapshot_generation,snapshot_sequence,measurement_time,
				 persistence_reason,quality,blocker_reason,raw_current_a,domain_current_a,current_direction,mapping_version,
				 current_measured_at,current_quality,string_voltage_v,midpoint_voltage_v,average_voltage_v,ambient_temperature_c,
				 average_cell_temperature_c,delta_seconds,delta_ah,raw_used_ah,representative_discharge_temperature_c,
				 temperature_factor_kc,corrected_used_ah,
				 soc_pct,dod_pct,float_target_voltage_v,boost_target_voltage_v)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON CONFLICT (calibration_event_id,snapshot_generation,snapshot_sequence) DO NOTHING RETURNING id
			""")) {
			insert.setLong(1, expected.eventId());
			insert.setLong(2, expected.stageId());
			setNullableLong(insert, 3, expected.processId());
			if (expected.processAttemptNumber() == null) insert.setNull(4, Types.INTEGER);
			else insert.setInt(4, expected.processAttemptNumber());
			insert.setObject(5, measurement.snapshotGeneration());
			insert.setLong(6, measurement.snapshotSequence());
			insert.setTimestamp(7, Timestamp.from(Instant.ofEpochMilli(measurement.capturedAt())));
			insert.setString(8, reason.name());
			insert.setString(9, write.quality());
			insert.setString(10, write.blockerReason());
			setNullableDouble(insert, 11, measurement.rawCurrentA());
			setNullableDouble(insert, 12, measurement.domainCurrentA());
			insert.setString(13, measurement.currentDirection().name());
			insert.setLong(14, measurement.mappingVersion());
			insert.setTimestamp(15, measurement.currentMeasuredAt() == null ? null
					: Timestamp.from(Instant.ofEpochMilli(measurement.currentMeasuredAt())));
			insert.setString(16, measurement.currentQuality() == null ? null : measurement.currentQuality().name());
			setNullableDouble(insert, 17, measurement.stringVoltageV());
			setNullableDouble(insert, 18, measurement.midpointVoltageV());
			setNullableDouble(insert, 19, measurement.averageVoltageV());
			setNullableDouble(insert, 20, measurement.ambientTemperatureC());
			setNullableDouble(insert, 21, measurement.averageTemperatureC());
			var discharge = write.evaluation().checkpoint() instanceof DischargeStageMonitor.Checkpoint value ? value : null;
			setNullableDouble(insert, 22, intervalSeconds(discharge, measurement.domainCurrentA()));
			setNullableDouble(insert, 23, discharge == null ? null : discharge.lastDeltaAh());
			setNullableDouble(insert, 24, discharge == null ? null : discharge.rawUsedAh());
			setNullableDouble(insert, 25, discharge == null || discharge.endpoint() == null ? null
					: discharge.endpoint().representativeTemperatureC());
			setNullableDouble(insert, 26, discharge == null || discharge.endpoint() == null ? null
					: discharge.endpoint().kcFactor());
			setNullableDouble(insert, 27, discharge == null || discharge.endpoint() == null ? null
					: discharge.endpoint().correctedAh());
			// An Initial Baseline persists acquisition evidence, not the calculator's
			// transient estimate. Other event types retain their compatibility payload.
			setNullableDouble(insert, 28,
					expected.configuration().eventType() == CalibrationEventType.INITIAL_BASELINE
							? null : measurement.stringSocPct());
			setNullableDouble(insert, 29, dod(write.evaluation().checkpoint()));
			setNullableDouble(insert, 30, target(write.evaluation().checkpoint(), true));
			setNullableDouble(insert, 31, target(write.evaluation().checkpoint(), false));
			try (var result = insert.executeQuery()) {
				return result.next() ? result.getLong(1) : -1;
			}
		}
	}

	private static Long sampleId(Connection connection, long eventId, UUID generation, long sequence)
			throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT id FROM bms.calibration_sample
			WHERE calibration_event_id=? AND snapshot_generation=? AND snapshot_sequence=?
			""")) {
			query.setLong(1, eventId);
			query.setObject(2, generation);
			query.setLong(3, sequence);
			try (var result = query.executeQuery()) {
				return result.next() ? result.getLong(1) : null;
			}
		}
	}

	private static boolean insertCutoffEndpoints(Connection connection, WorkerEvent expected, SampleWrite write,
			long sampleId, List<DischargeStageMonitor.CutoffEndpoint> endpoints) throws SQLException {
		boolean changed = false;
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_cell_cutoff_crossing
				(calibration_event_id,calibration_stage_id,calibration_process_id,process_attempt_number,
				 cell_index,before_sample_id,after_sample_id,cutoff_voltage_v,crossing_measurement_time,
				 cell_usable_capacity_ah,interpolation_fraction)
			VALUES (?,?,?,?,?,NULL,?,?,?,?,NULL)
			ON CONFLICT (calibration_process_id,process_attempt_number,cell_index) DO NOTHING
			""")) {
			for (var endpoint : endpoints) {
				insert.setLong(1, expected.eventId());
				insert.setLong(2, expected.stageId());
				insert.setLong(3, expected.processId());
				insert.setInt(4, expected.processAttemptNumber());
				insert.setInt(5, endpoint.cellIndex());
				insert.setLong(6, sampleId);
				insert.setDouble(7, endpoint.cutoffVoltageV());
				insert.setTimestamp(8, Timestamp.from(Instant.ofEpochMilli(write.measurement().capturedAt())));
				insert.setDouble(9, endpoint.cellUsableCapacityAh());
				changed |= insert.executeUpdate() == 1;
			}
		}
		return changed;
	}

	private static void insertSampleReasons(Connection connection, long sampleId,
			List<CalibrationStageEvaluation.PersistenceReason> reasons,
			List<CalibrationStageEvaluation.Milestone> milestones) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_sample_reason
				(calibration_sample_id,persistence_reason,reason_key,milestone_value)
			VALUES (?,?,?,?) ON CONFLICT DO NOTHING
			""")) {
			for (var reason : reasons) {
				var matchingMilestones = milestones.stream().filter(value -> switch (reason) {
				case DOD_MILESTONE_5_PERCENT -> value.type() == CalibrationStageEvaluation.MilestoneType.DOD_PERCENT;
				case TARGET_DOD -> value.type() == CalibrationStageEvaluation.MilestoneType.TARGET_DOD;
				case CUTOFF -> value.type() == CalibrationStageEvaluation.MilestoneType.CUTOFF;
				default -> false;
				}).toList();
				if (matchingMilestones.isEmpty()) {
					addSampleReasonBatch(insert, sampleId, reason, "", null);
				} else {
					for (var milestone : matchingMilestones) {
						addSampleReasonBatch(insert, sampleId, reason,
								milestone.type().name() + ":" + Double.toString(milestone.value()), milestone.value());
					}
				}
			}
			insert.executeBatch();
		}
	}

	private static void addSampleReasonBatch(PreparedStatement insert, long sampleId,
			CalibrationStageEvaluation.PersistenceReason reason, String reasonKey, Double milestoneValue)
			throws SQLException {
		insert.setLong(1, sampleId);
		insert.setString(2, reason.name());
		insert.setString(3, reasonKey);
		setNullableDouble(insert, 4, milestoneValue);
		insert.addBatch();
	}

	private static boolean isActiveProcess(Connection connection, WorkerEvent expected) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT 1 FROM bms.calibration_process WHERE id=? AND calibration_event_id=? AND calibration_stage_id=?
				AND attempt_number=? AND state IN ('ACTIVE','BLOCKED')
			""")) {
			query.setLong(1, expected.processId());
			query.setLong(2, expected.eventId());
			query.setLong(3, expected.stageId());
			query.setInt(4, expected.processAttemptNumber());
			try (var result = query.executeQuery()) {
				return result.next();
			}
		}
	}

	/**
	 * A signature is deliberately categorical. Raw amperage and FRESH/RETAINED
	 * publication state are excluded because they can change on every calculator
	 * cycle without representing a new audit condition.
	 */
	static String qualitySignature(SampleWrite write) {
		var signature = new JsonObject();
		signature.addProperty("quality", write.quality());
		signature.add("blockers", GSON.toJsonTree(write.evaluation().blockers().stream().map(Enum::name).toList()));
		signature.addProperty("currentDirection", write.measurement().currentDirection().name());
		return GSON.toJson(signature);
	}

	/**
	 * Persists the common runtime envelope for every automatic process without
	 * appending a full historical cell sample. Discharge-only integration state is
	 * stored in its one-to-one extension.
	 */
	private static void upsertProcessRuntime(Connection connection, WorkerEvent expected, SampleWrite write,
			String qualitySignature, Long previousLastPeriodicAt, boolean periodicPersisted) throws SQLException {
		if (expected.processId() == null || expected.processAttemptNumber() == null) return;
		var measurement = write.measurement();
		try (var upsert = connection.prepareStatement("""
			INSERT INTO bms.calibration_process_runtime
				(calibration_process_id,attempt_number,snapshot_generation,snapshot_sequence,last_captured_at,
				 last_current_measured_at,last_domain_current_a,last_periodic_persisted_at,quality_signature,
				 checkpoint_data,latest_measurement,blockers,recommendation,updated_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
			ON CONFLICT (calibration_process_id,attempt_number) DO UPDATE SET
				snapshot_generation=EXCLUDED.snapshot_generation,
				snapshot_sequence=EXCLUDED.snapshot_sequence,
				last_captured_at=EXCLUDED.last_captured_at,
				last_current_measured_at=EXCLUDED.last_current_measured_at,
				last_domain_current_a=EXCLUDED.last_domain_current_a,
				last_periodic_persisted_at=CASE WHEN ? THEN EXCLUDED.last_periodic_persisted_at
					ELSE bms.calibration_process_runtime.last_periodic_persisted_at END,
				quality_signature=EXCLUDED.quality_signature,
				checkpoint_data=EXCLUDED.checkpoint_data,
				latest_measurement=EXCLUDED.latest_measurement,
				blockers=EXCLUDED.blockers,
				recommendation=EXCLUDED.recommendation,
				updated_at=now()
			""")) {
			upsert.setLong(1, expected.processId());
			upsert.setInt(2, expected.processAttemptNumber());
			upsert.setObject(3, measurement.snapshotGeneration());
			upsert.setLong(4, measurement.snapshotSequence());
			upsert.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(measurement.capturedAt())));
			upsert.setTimestamp(6, measurement.currentMeasuredAt() == null ? null
					: Timestamp.from(Instant.ofEpochMilli(measurement.currentMeasuredAt())));
			setNullableDouble(upsert, 7, measurement.domainCurrentA());
			Long periodicAt = previousLastPeriodicAt;
			if (periodicPersisted) periodicAt = write.evaluatedAtMillis();
			upsert.setTimestamp(8, periodicAt == null ? null : Timestamp.from(Instant.ofEpochMilli(periodicAt)));
			upsert.setString(9, qualitySignature);
			upsert.setObject(10, jsonb(checkpointForCommonRuntime(write.evaluation().checkpoint())));
			upsert.setObject(11, jsonb(GSON.toJsonTree(measurement)));
			upsert.setObject(12, jsonb(GSON.toJsonTree(write.evaluation().blockers())));
			upsert.setString(13, write.evaluation().recommendation().name());
			upsert.setBoolean(14, periodicPersisted);
			upsert.executeUpdate();
		}
		if (expected.stageType() == CalibrationStageType.DISCHARGE
				&& write.evaluation().checkpoint() instanceof DischargeStageMonitor.Checkpoint discharge) {
			upsertDischargeRuntime(connection, expected, discharge);
		}
	}

	private static JsonElement checkpointForCommonRuntime(Object checkpoint) {
		var value = GSON.toJsonTree(checkpoint);
		if (checkpoint instanceof DischargeStageMonitor.Checkpoint && value.isJsonObject()) {
			value.getAsJsonObject().remove("perCellState");
			value.getAsJsonObject().remove("newCutoffEndpoints");
			value.getAsJsonObject().remove("safetyState");
			value.getAsJsonObject().remove("frozenUsedAh");
			value.getAsJsonObject().remove("safetyTriggers");
			value.getAsJsonObject().remove("newSafetyTriggers");
		}
		return value;
	}

	private static void upsertDischargeRuntime(Connection connection, WorkerEvent expected,
			DischargeStageMonitor.Checkpoint discharge) throws SQLException {
		int nextMilestone = Math.min(105, discharge.lastDodMilestonePercent() + 5);
		try (var upsert = connection.prepareStatement("""
			INSERT INTO bms.calibration_discharge_runtime
				(calibration_process_id,attempt_number,last_delta_ah,raw_used_ah,official_used_ah,
				 next_dod_milestone_percent,per_cell_state,safety_state,safety_triggered_at,
				 frozen_used_ah,safety_trigger_cells,updated_at)
			VALUES (?,?,?,?,?,?,?,?,CASE WHEN ?='LATCHED' THEN now() ELSE NULL END,?,?,now())
			ON CONFLICT (calibration_process_id,attempt_number) DO UPDATE SET
				last_delta_ah=EXCLUDED.last_delta_ah,
				raw_used_ah=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.raw_used_ah ELSE bms.calibration_discharge_runtime.raw_used_ah END,
				official_used_ah=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.official_used_ah ELSE bms.calibration_discharge_runtime.official_used_ah END,
				next_dod_milestone_percent=EXCLUDED.next_dod_milestone_percent,
				per_cell_state=EXCLUDED.per_cell_state,
				safety_state=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.safety_state ELSE bms.calibration_discharge_runtime.safety_state END,
				safety_triggered_at=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.safety_triggered_at ELSE bms.calibration_discharge_runtime.safety_triggered_at END,
				frozen_used_ah=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.frozen_used_ah ELSE bms.calibration_discharge_runtime.frozen_used_ah END,
				safety_trigger_cells=CASE WHEN bms.calibration_discharge_runtime.safety_state='CLEAR'
					THEN EXCLUDED.safety_trigger_cells ELSE bms.calibration_discharge_runtime.safety_trigger_cells END,
				updated_at=now()
			""")) {
			var safetyState = discharge.effectiveSafetyState().name();
			double officialUsedAh = discharge.frozenUsedAh() == null ? discharge.rawUsedAh() : discharge.frozenUsedAh();
			upsert.setLong(1, expected.processId());
			upsert.setInt(2, expected.processAttemptNumber());
			setNullableDouble(upsert, 3, discharge.lastDeltaAh());
			upsert.setDouble(4, discharge.rawUsedAh());
			upsert.setDouble(5, officialUsedAh);
			upsert.setInt(6, nextMilestone);
			upsert.setObject(7, jsonb(GSON.toJsonTree(discharge.perCellState())));
			upsert.setString(8, safetyState);
			upsert.setString(9, safetyState);
			setNullableDouble(upsert, 10, discharge.frozenUsedAh());
			upsert.setObject(11, jsonb(GSON.toJsonTree(discharge.safetyTriggers())));
			upsert.executeUpdate();
		}
	}

	private static void updateProcessEvaluation(Connection connection, WorkerEvent expected, SampleWrite write,
			StageState stageState) throws SQLException {
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state=?,updated_at=now()
			WHERE id=? AND state IN ('ACTIVE','BLOCKED')
			""")) {
			update.setString(1, stageState == StageState.ACTIVE ? ProcessState.ACTIVE.name() : ProcessState.BLOCKED.name());
			update.setLong(2, expected.processId());
			if (update.executeUpdate() != 1) throw new IllegalStateException("Calibration process was changed concurrently");
		}
	}

	private static void insertCells(Connection connection, long sampleId, CalibrationMeasurement measurement,
			boolean persistDerivedEstimates) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_cell_sample
				(calibration_sample_id,cell_index,voltage_v,temperature_c,resistance_uohm,soc_pct,soc_method,soh_pct,soh_method,quality,availability_warning)
			VALUES (?,?,?,?,?,?,?,?,?,?,?)
			""")) {
			for (var cell : measurement.cells().values()) {
				insert.setLong(1, sampleId);
				insert.setInt(2, cell.cellId());
				setNullableDouble(insert, 3, cell.voltageV());
				setNullableDouble(insert, 4, cell.temperatureC());
				if (cell.internalResistanceUohm() == null) insert.setNull(5, Types.INTEGER);
				else insert.setInt(5, cell.internalResistanceUohm());
				setNullableDouble(insert, 6, persistDerivedEstimates ? cell.socPct() : null);
				insert.setString(7, persistDerivedEstimates ? "VOLTAGE_ESTIMATE" : null);
				setNullableDouble(insert, 8, persistDerivedEstimates ? cell.sohPct() : null);
				insert.setString(9, persistDerivedEstimates ? "RESISTANCE_ESTIMATE" : null);
				insert.setString(10, cell.availabilityWarning() == null ? "VALID" : "PARTIAL");
				insert.setString(11, cell.availabilityWarning());
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static EventDetail loadDetail(Connection connection, long eventId, boolean lock) throws SQLException {
		EventSummary event;
		CalibrationEventConfiguration configuration;
		Long referenceId;
		List<String> blockers;
		List<String> missing;
		try (var query = connection.prepareStatement("""
			SELECT id,string_id,event_type,status,current_stage_id,event_version,measurement_revision,
				created_by,created_by_name,created_at,closed_at,baseline_approved_at,baseline_approved_by,
				baseline_approved_by_name,configuration_snapshot,reference_calibration_event_id,blockers,missing_evidence
			FROM bms.calibration_event WHERE id=?
			""" + (lock ? " FOR UPDATE" : ""))) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Calibration event was not found");
				event = summary(result);
				configuration = configuration(result.getString(15));
				referenceId = result.getObject(16, Long.class);
				blockers = strings(parse(result.getString(17)));
				missing = strings(parse(result.getString(18)));
			}
		}
		var stages = new ArrayList<Stage>();
		try (var query = connection.prepareStatement("""
			SELECT s.id,s.calibration_event_id,s.stage_order,s.stage_type,s.state,s.properties,s.result,s.blocker_code,
				s.blocker_detail,s.started_at,s.completed_at,s.completed_by,t.action,t.reason,t.actor_name,t.occurred_at,t.evidence
			FROM bms.calibration_stage s LEFT JOIN LATERAL
			(SELECT terminal.action,terminal.reason,terminal.actor_name,terminal.occurred_at,terminal.evidence
				 FROM bms.calibration_transition terminal
				 WHERE terminal.calibration_stage_id=s.id AND terminal.action IN
				 ('COMPLETE_STAGE','COMPLETE_WITH_GAPS','SKIP_STAGE','FAIL_STAGE','ABORT_EVENT')
				 AND terminal.id>COALESCE((SELECT max(redo.id) FROM bms.calibration_transition redo
					JOIN bms.calibration_stage redo_stage ON redo_stage.id=redo.calibration_stage_id
					WHERE redo.calibration_event_id=s.calibration_event_id AND redo.action='REDO_STAGE'
						AND redo_stage.stage_order<=s.stage_order),0)
				 ORDER BY terminal.occurred_at DESC,terminal.id DESC LIMIT 1) t ON true
			WHERE s.calibration_event_id=? ORDER BY s.stage_order
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var evidence = parse(result.getString(17));
					stages.add(new Stage(result.getLong(1), result.getLong(2), result.getInt(3),
							CalibrationStageType.valueOf(result.getString(4)), StageState.valueOf(result.getString(5)),
							parse(result.getString(6)), result.getString(7), result.getString(8), parse(result.getString(9)),
							instant(result.getTimestamp(10)), instant(result.getTimestamp(11)), result.getObject(12, UUID.class),
							result.getString(13), result.getString(14), result.getString(15), instant(result.getTimestamp(16)),
							acknowledgements(evidence)));
				}
			}
		}
		var processes = new ArrayList<Process>();
		try (var query = connection.prepareStatement("""
			SELECT process.id,process.calibration_event_id,process.calibration_stage_id,process.process_order,
				process.process_type,process.attempt_number,process.state,process.sampling_policy,
				process.properties,process.started_at,process.completed_at,
				runtime.calibration_process_id,runtime.snapshot_generation,runtime.snapshot_sequence,
				runtime.last_captured_at,runtime.last_current_measured_at,runtime.last_domain_current_a,
				runtime.last_periodic_persisted_at,runtime.quality_signature,runtime.checkpoint_data,
				runtime.latest_measurement,runtime.blockers,runtime.recommendation,runtime.created_at,runtime.updated_at,
				discharge.calibration_process_id,discharge.last_delta_ah,discharge.raw_used_ah,
				discharge.official_used_ah,discharge.next_dod_milestone_percent,discharge.per_cell_state,
				discharge.crossed_cell_count,discharge.configured_cell_count,
				discharge.string_safe_usable_capacity_ah,discharge.all_cells_endpoint_ah,
				discharge.safety_state,discharge.safety_triggered_at,discharge.frozen_used_ah,
				discharge.safety_trigger_cells,
				discharge.created_at,discharge.updated_at
			FROM bms.calibration_process process
			LEFT JOIN bms.calibration_process_runtime runtime
				ON runtime.calibration_process_id=process.id AND runtime.attempt_number=process.attempt_number
			LEFT JOIN bms.calibration_discharge_runtime discharge
				ON discharge.calibration_process_id=process.id
				AND discharge.attempt_number=process.attempt_number
				AND process.process_type='CONTROLLED_DISCHARGE'
			WHERE process.calibration_event_id=?
			ORDER BY process.calibration_stage_id,process.process_order
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var processConfiguration = parse(result.getString(9));
					var processRuntime = processRuntime(result);
					var processState = ProcessState.valueOf(result.getString(7));
					var dischargeRuntime = dischargeRuntime(result, configuration, processState);
					processes.add(new Process(result.getLong(1), result.getLong(2), result.getLong(3), result.getInt(4),
							result.getString(5), result.getInt(6), processState, result.getString(8),
							processConfiguration, processRuntime, dischargeRuntime,
							processPropertiesWithRuntime(processConfiguration, processRuntime),
							instant(result.getTimestamp(10)), instant(result.getTimestamp(11))));
				}
			}
		}
		configuration = effectiveDischargeSafetyFloor(configuration, processes);
		var referenceCurve = loadReferenceCurve(connection, eventId, null);
		return new EventDetail(event, configuration, referenceId, blockers, missing,
				stages, processes, referenceCurve, List.of());
	}

	private static ReferenceCurve loadReferenceCurve(Connection connection, long eventId, Long curveVersionId)
			throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT curve.id,curve.calibration_event_id,curve.string_id,curve.calibration_stage_id,
				curve.calibration_process_id,curve.process_attempt_number,curve.curve_schema_version,
				curve.capacity_reference_ah,curve.string_safe_usable_capacity_ah,curve.all_cells_endpoint_ah,
				curve.cell_count,curve.point_count,curve.content_hash,curve.published_at,
				(active.curve_version_id IS NOT NULL) AS active_baseline
			FROM bms.calibration_curve_version curve
			LEFT JOIN bms.calibration_active_baseline active ON active.curve_version_id=curve.id
			WHERE curve.calibration_event_id=? AND (?::bigint IS NULL OR curve.id=?)
			ORDER BY curve.published_at DESC,curve.id DESC LIMIT 1
			""")) {
			query.setLong(1, eventId);
			if (curveVersionId == null) query.setNull(2, Types.BIGINT); else query.setLong(2, curveVersionId);
			if (curveVersionId == null) query.setNull(3, Types.BIGINT); else query.setLong(3, curveVersionId);
			try (var result = query.executeQuery()) {
				if (!result.next()) return null;
				return new ReferenceCurve(result.getLong(1), result.getLong(2), result.getObject(3, UUID.class),
						result.getLong(4), result.getLong(5), result.getInt(6), result.getInt(7),
						result.getDouble(8), result.getDouble(9), result.getDouble(10), result.getInt(11),
						result.getInt(12), result.getString(13), instant(result.getTimestamp(14)), result.getBoolean(15));
			}
		}
	}

	private static JsonElement processRuntime(ResultSet result) throws SQLException {
		if (result.getObject(12, Long.class) == null) return null;
		var runtime = new JsonObject();
		var generation = result.getObject(13, UUID.class);
		addNullable(runtime, "snapshotGeneration", generation == null ? null : generation.toString());
		addNullable(runtime, "snapshotSequence", result.getObject(14, Long.class));
		addNullable(runtime, "lastCapturedAt", instant(result.getTimestamp(15)));
		addNullable(runtime, "lastCurrentMeasuredAt", instant(result.getTimestamp(16)));
		addNullable(runtime, "lastDomainCurrentA", number(result, 17));
		addNullable(runtime, "lastPeriodicPersistedAt", instant(result.getTimestamp(18)));
		addNullable(runtime, "qualitySignature", result.getString(19));
		runtime.add("checkpoint", parse(result.getString(20)));
		runtime.add("latestMeasurement", parse(result.getString(21)));
		runtime.add("blockers", parse(result.getString(22)));
		addNullable(runtime, "recommendation", result.getString(23));
		addNullable(runtime, "createdAt", instant(result.getTimestamp(24)));
		addNullable(runtime, "updatedAt", instant(result.getTimestamp(25)));
		return runtime;
	}

	private static JsonElement dischargeRuntime(ResultSet result, CalibrationEventConfiguration configuration,
			ProcessState processState) throws SQLException {
		if (result.getObject(26, Long.class) == null) return null;
		var runtime = new JsonObject();
		addNullable(runtime, "lastDeltaAh", number(result, 27));
		addNullable(runtime, "rawUsedAh", number(result, 28));
		addNullable(runtime, "officialUsedAh", number(result, 29));
		addNullable(runtime, "nextDodMilestonePercent", result.getObject(30, Integer.class));
		runtime.add("perCellState", parse(result.getString(31)));
		addNullable(runtime, "crossedCellCount", result.getObject(32, Integer.class));
		addNullable(runtime, "configuredCellCount", result.getObject(33, Integer.class));
		addNullable(runtime, "stringSafeUsableCapacityAh", number(result, 34));
		addNullable(runtime, "allCellsEndpointAh", number(result, 35));
		addNullable(runtime, "safetyState", result.getString(36));
		addNullable(runtime, "safetyTriggeredAt", instant(result.getTimestamp(37)));
		addNullable(runtime, "frozenUsedAh", number(result, 38));
		runtime.add("safetyTriggerCells", parse(result.getString(39)));
		var currentMeasuredAt = result.getTimestamp(16);
		var current = number(result, 17);
		boolean currentFresh = currentMeasuredAt != null
				&& Instant.now().minusSeconds(configuration.snapshotFreshnessSeconds())
						.isBefore(currentMeasuredAt.toInstant());
		boolean canConfirm = processState == ProcessState.ACTIVE && "LATCHED".equals(result.getString(36))
				&& currentFresh && current != null && Math.abs(current) <= configuration.currentDeadbandA();
		runtime.addProperty("canConfirmDischargeStopped", canConfirm);
		addNullable(runtime, "createdAt", instant(result.getTimestamp(40)));
		addNullable(runtime, "updatedAt", instant(result.getTimestamp(41)));
		return runtime;
	}

	/** Temporary API compatibility projection while clients move to runtime DTOs. */
	private static JsonElement processPropertiesWithRuntime(JsonElement configuration, JsonElement runtime) {
		var properties = configuration != null && configuration.isJsonObject()
				? configuration.getAsJsonObject().deepCopy() : new JsonObject();
		if (runtime == null || !runtime.isJsonObject()) return properties;
		var runtimeObject = runtime.getAsJsonObject();
		for (var key : List.of("checkpoint", "latestMeasurement", "blockers", "recommendation")) {
			if (runtimeObject.has(key)) properties.add(key, runtimeObject.get(key).deepCopy());
		}
		return properties;
	}

	private static CalibrationEventConfiguration effectiveDischargeSafetyFloor(
			CalibrationEventConfiguration configuration, List<Process> processes) {
		if (configuration.eventType() != CalibrationEventType.INITIAL_BASELINE) return configuration;
		var discharge = processes.stream().filter(value -> "CONTROLLED_DISCHARGE".equals(value.type())).findFirst();
		if (discharge.isEmpty() || discharge.get().configuration() == null
				|| !discharge.get().configuration().isJsonObject()) return configuration;
		var value = discharge.get().configuration().getAsJsonObject().get("safetyFloorVoltagePerCell");
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
				|| !value.getAsJsonPrimitive().isNumber()) return configuration;
		return configuration.withSafetyFloorVoltagePerCell(value.getAsDouble());
	}

	private static boolean isOpenForRedo(EventStatus status) {
		return status == EventStatus.ACTIVE || status == EventStatus.BLOCKED
				|| status == EventStatus.REVALIDATION_REQUIRED;
	}

	private static void appendTransition(Connection connection, long eventId, long stageId, String fromState,
			String toState, String action, String reason, AuthenticatedPrincipal actor, long version, int order,
			JsonElement evidence) throws SQLException {
		var transitionEvidence = evidence != null && evidence.isJsonObject()
				? evidence.getAsJsonObject().deepCopy() : new JsonObject();
		transitionEvidence.add("telemetry", latestTelemetryEvidence(connection, eventId, stageId));
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_transition
				(calibration_event_id,calibration_stage_id,from_state,to_state,action,reason,actor_id,actor_name,
				 evidence,resulting_event_version,transition_order)
			VALUES (?,?,?,?,?,?,?,?,?,?,?)
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setString(3, fromState);
			insert.setString(4, toState);
			insert.setString(5, action);
			insert.setString(6, trimToNull(reason));
			insert.setObject(7, actor.id());
			insert.setString(8, actor.name());
			insert.setObject(9, jsonb(transitionEvidence));
			insert.setLong(10, version);
			insert.setInt(11, order);
			insert.executeUpdate();
		}
	}

	private static void appendProcessTransition(Connection connection, long eventId, long stageId, long processId,
			String fromState, String toState, String action, AuthenticatedPrincipal actor, long version)
			throws SQLException {
		appendProcessTransition(connection, eventId, stageId, processId, fromState, toState, action, null, actor, version);
	}

	private static void appendProcessTransition(Connection connection, long eventId, long stageId, long processId,
			String fromState, String toState, String action, String reason, AuthenticatedPrincipal actor, long version)
			throws SQLException {
		appendProcessTransition(connection, eventId, stageId, processId, fromState, toState, action, reason, actor,
				version, new JsonObject());
	}

	private static void appendProcessTransition(Connection connection, long eventId, long stageId, long processId,
			String fromState, String toState, String action, String reason, AuthenticatedPrincipal actor, long version,
			JsonElement evidence) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_process_transition
				(calibration_event_id,calibration_stage_id,calibration_process_id,from_state,to_state,action,reason,
				 actor_id,actor_name,resulting_event_version,evidence)
			VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setLong(3, processId);
			insert.setString(4, fromState);
			insert.setString(5, toState);
			insert.setString(6, action);
			insert.setString(7, reason);
			insert.setObject(8, actor.id());
			insert.setString(9, actor.name());
			insert.setLong(10, version);
			insert.setObject(11, jsonb(evidence == null ? new JsonObject() : evidence));
			insert.executeUpdate();
		}
	}

	private static JsonObject appliedPolicyEvidence(Process process) {
		var result = new JsonObject();
		result.addProperty("processType", process.type());
		result.addProperty("attemptNumber", process.attemptNumber());
		var applied = process.configuration() != null && process.configuration().isJsonObject()
				? process.configuration().getAsJsonObject().deepCopy() : new JsonObject();
		result.addProperty("policyRevision", policyRevision(applied));
		result.add("appliedPolicy", applied);
		return result;
	}

	private static JsonObject appliedPolicyEvidence(Connection connection, long processId) throws SQLException {
		try (var query = connection.prepareStatement(
				"SELECT process_type,attempt_number,properties FROM bms.calibration_process WHERE id=?")) {
			query.setLong(1, processId);
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("Calibration process was not found");
				var evidence = new JsonObject();
				evidence.addProperty("processType", result.getString(1));
				evidence.addProperty("attemptNumber", result.getInt(2));
				var parsed = parse(result.getString(3));
				var applied = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
				evidence.addProperty("policyRevision", policyRevision(applied));
				evidence.add("appliedPolicy", applied.deepCopy());
				return evidence;
			}
		}
	}

	/** Captures the accepted sample as it existed in the same operator transaction. */
	private static JsonElement latestTelemetryEvidence(Connection connection, long eventId, long stageId)
			throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT to_jsonb(sample_record) FROM (
				SELECT s.*,COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.cell_index)
					FROM bms.calibration_cell_sample c WHERE c.calibration_sample_id=s.id),'[]'::jsonb) AS cells
				FROM bms.calibration_sample s
				WHERE s.calibration_event_id=? AND s.calibration_stage_id=?
				ORDER BY s.measurement_time DESC,s.id DESC LIMIT 1
			) sample_record
			""")) {
			query.setLong(1, eventId);
			query.setLong(2, stageId);
			try (var result = query.executeQuery()) {
				return result.next() ? parse(result.getString(1)) : com.google.gson.JsonNull.INSTANCE;
			}
		}
	}

	private static void appendSystemTransition(Connection connection, long eventId, long stageId, String fromState,
			String toState, String action, long version) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_transition
				(calibration_event_id,calibration_stage_id,from_state,to_state,action,evidence,
				 resulting_event_version,transition_order)
			VALUES (?,?,?,?,?,'{}'::jsonb,?,0)
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setString(3, fromState);
			insert.setString(4, toState);
			insert.setString(5, action);
			insert.setLong(6, version);
			insert.executeUpdate();
		}
	}

	private static Double validateReference(Connection connection, UUID stringId, Long referenceId) throws SQLException {
		if (referenceId == null) return null;
		try (var query = connection.prepareStatement("""
			SELECT s.properties FROM bms.calibration_event e
			JOIN bms.calibration_stage s ON s.calibration_event_id=e.id AND s.stage_type='DISCHARGE'
			WHERE e.id=? AND e.string_id=? AND e.event_type='INITIAL_BASELINE' AND e.status='COMPLETED'
				AND e.baseline_approved_at IS NOT NULL AND e.baseline_approved_by IS NOT NULL
			FOR SHARE OF e,s
			""")) {
			query.setLong(1, referenceId);
			query.setObject(2, stringId);
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Reference must be a completed Initial Baseline for this string");
				var endpoint = object(parse(result.getString(1)), "endpoint");
				var correctedAh = endpoint == null ? null : number(endpoint, "correctedAh");
				if (endpoint == null || !"CUTOFF".equals(string(endpoint, "type"))
						|| !"FULL_CUTOFF".equals(string(endpoint, "resultMethod"))
						|| correctedAh == null || correctedAh <= 0) {
					throw new IllegalArgumentException("Reference baseline has no approved full-cutoff capacity result");
				}
				return correctedAh;
			}
		}
	}

	private static EventSummary summary(ResultSet result) throws SQLException {
		return new EventSummary(result.getLong(1), result.getObject(2, UUID.class),
				CalibrationEventType.valueOf(result.getString(3)), EventStatus.valueOf(result.getString(4)),
				result.getObject(5, Long.class), result.getLong(6), result.getLong(7),
				result.getObject(8, UUID.class), result.getString(9), instant(result.getTimestamp(10)),
				instant(result.getTimestamp(11)), instant(result.getTimestamp(12)), result.getObject(13, UUID.class),
				result.getString(14));
	}

	private static Double number(ResultSet result, int column) throws SQLException {
		var value = result.getBigDecimal(column);
		return value == null ? null : value.doubleValue();
	}

	private static Long longNumber(String value) {
		if (value == null) return null;
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Long latestEvaluatedCurrentMeasuredAt(String checkpointValue, Long persistedValue) {
		var checkpoint = longNumber(checkpointValue);
		return checkpoint == null ? persistedValue : checkpoint;
	}

	private static int integer(JsonElement parent, String property, int fallback) {
		if (parent == null || !parent.isJsonObject()) return fallback;
		var value = parent.getAsJsonObject().get(property);
		return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? fallback : value.getAsInt();
	}

	private static Stage requireCurrentStage(EventDetail detail, long stageId) {
		if (detail.event().currentStageId() == null || detail.event().currentStageId() != stageId) {
			throw new CalibrationConflictException("Calibration stage changed; reload the event detail");
		}
		return detail.stages().stream().filter(stage -> stage.id() == stageId).findFirst()
				.orElseThrow(() -> new CalibrationConflictException("Current calibration stage is unavailable"));
	}

	private static void updateEventAfterEvidence(Connection connection, long eventId, String evidenceType,
			String evidenceStatus) throws SQLException {
		try (var update = connection.prepareStatement("""
			UPDATE bms.calibration_event SET
				missing_evidence=CASE WHEN ? IN ('PASS','RECORDED') THEN COALESCE(
					(SELECT jsonb_agg(value) FROM jsonb_array_elements_text(missing_evidence) value
					 WHERE value<>?), '[]'::jsonb) ELSE missing_evidence END,
				event_version=event_version+1,updated_at=now() WHERE id=?
			""")) {
			update.setString(1, evidenceStatus);
			update.setString(2, evidenceType);
			update.setLong(3, eventId);
			update.executeUpdate();
		}
	}

	private static List<String> initialMissingEvidence(CalibrationEventType eventType) {
		return switch (eventType) {
			case INITIAL_BASELINE -> List.of("BATTERY_CHARGER_CONNECTIONS_CHECKED", "LOAD_MCB_OPEN_CONFIRMED",
					"SAFETY_VISUAL_INSPECTION_CONFIRMED", "OPEN_CIRCUIT_VOLTAGE_REVIEWED", "CHARGE_SETPOINTS_REVIEWED",
					"BOOST_TARGET_REVIEWED", "FLOAT_MODE_REVIEWED", "CHARGER_MCB_OPEN_FOR_COOLDOWN",
					"AMBIENT_TEMPERATURE_REVIEWED", "COOLDOWN_SAFETY_VALVES_REVIEWED", "TEST_LOAD_CONNECTED",
					"DISCHARGE_CUTOFF_REVIEWED", "TEST_LOAD_DISCONNECTED",
					"RECOVERY_CHARGE_SETPOINTS_REVIEWED", "RECOVERY_CHARGER_MCB_CLOSED_CONFIRMED",
					"RECOVERY_FLOAT_STABILITY_REVIEWED", "CELL_RESISTANCE_SAMPLE_REVIEWED",
					"CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE", "RIPPLE_BEFORE_LOAD",
					"LOAD_CONNECTED_CONFIRMATION", "RIPPLE_AFTER_LOAD",
					"CHARGER_MCB_OPEN_CONFIRMATION", "VOC_BASELINE_CONFIRMATION", "BASELINE_SUMMARY_REVIEWED",
					"RETURN_TO_SERVICE_CONFIRMATION");
		case MONTHLY_INSPECTION -> List.of();
		case SIX_MONTH_INSPECTION -> List.of("MIDPOINT_VOLTAGE", "RIPPLE_BEFORE_LOAD",
				"LINKED_PERIODIC_CAPACITY_RECORD");
		case PERIODIC_DISCHARGE -> List.of();
		};
	}

	static CalibrationEventConfiguration configuration(String json) {
		var value = JsonParser.parseString(json).getAsJsonObject();
		addLegacyDefault(value, "floatStableDurationMinutes",
				CalibrationEventConfiguration.DEFAULT_FLOAT_STABLE_DURATION_MINUTES);
		addLegacyDefault(value, "floatVoltageToleranceV",
				CalibrationEventConfiguration.DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V);
		addLegacyDefault(value, "floatCurrentMinA", CalibrationEventConfiguration.LEGACY_FLOAT_CURRENT_MIN_A);
		addLegacyDefault(value, "floatCurrentMaxA", CalibrationEventConfiguration.LEGACY_FLOAT_CURRENT_MAX_A);
		if (!value.has("chargeCurrentLimitA") || value.get("chargeCurrentLimitA").isJsonNull()) {
			value.addProperty("chargeCurrentLimitA", CalibrationEventConfiguration
					.defaultChargeCurrentLimitA(value.get("ratedCapacityAh").getAsDouble()));
		}
		if (!value.has("safetyFloorVoltagePerCell") || value.get("safetyFloorVoltagePerCell").isJsonNull()) {
			value.addProperty("safetyFloorVoltagePerCell", CalibrationEventConfiguration
					.defaultSafetyFloorVoltagePerCell(value.get("cutoffVoltagePerCell").getAsDouble()));
		}
		return GSON.fromJson(value, CalibrationEventConfiguration.class);
	}

	private static void addLegacyDefault(JsonObject value, String property, Number fallback) {
		if (!value.has(property) || value.get(property).isJsonNull()) value.addProperty(property, fallback);
	}

	private static CalibrationEventConfiguration withReference(CalibrationEventConfiguration value,
			Double referenceBaselineAh) {
		if (referenceBaselineAh == null) return value;
		return new CalibrationEventConfiguration(value.eventType(), value.stringKey(), value.cellCount(),
				value.ratedCapacityAh(), referenceBaselineAh, referenceBaselineAh, value.cutoffVoltagePerCell(),
				value.floatVoltagePerCell(), value.boostVoltagePerCell(), value.currentDirection(), value.mappingVersion(),
				value.floatTemperatureCoefficientMvPerCellC(), value.boostTemperatureCoefficientMvPerCellC(),
				value.currentDeadbandA(), value.sampleIntervalSeconds(),
				value.snapshotFreshnessSeconds(), value.dischargeMethod(), value.dischargeEndpointMode(),
				value.targetDodPercent(), value.initialFloatCurrentLimitA(), value.periodicFloatCurrentLimitA(),
				value.floatOvercurrentReferenceA(), value.dischargeVoltageFloorPolicy(), value.floatStableDurationMinutes(),
				value.floatVoltageToleranceV(), value.floatCurrentMinA(), value.floatCurrentMaxA(),
				value.chargeCurrentLimitA(), value.safetyFloorVoltagePerCell());
	}

	private static JsonElement stageProperties(CalibrationStageEvaluation<?> evaluation) {
		var value = GSON.toJsonTree(evaluation.checkpoint());
		var result = value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
		result.addProperty("recommendation", evaluation.recommendation().name());
		if (evaluation.checkpoint() instanceof DischargeStageMonitor.Checkpoint checkpoint) {
			result.addProperty("currentDodPercent", checkpoint.dodPercent());
			if (checkpoint.endpoint() != null) {
				var endpoint = checkpoint.endpoint();
				result.addProperty("endpointType", endpoint.type().name());
				addNullable(result, "endpointCellIndex", endpoint.cellId());
				addNullable(result, "endpointVoltageV", endpoint.cellVoltageV());
				result.addProperty("endpointMeasurementTime", Instant.ofEpochMilli(endpoint.measurementTime()).toString());
				addNullable(result, "representativeDischargeTemperatureC", endpoint.representativeTemperatureC());
				addNullable(result, "effectiveKc", endpoint.kcFactor());
				addNullable(result, "correctedUsedAh", endpoint.correctedAh());
				result.addProperty("capacityMethod", endpoint.resultMethod().name());
				addNullable(result, "capacitySohPercent", endpoint.capacitySohPercent());
			}
		} else if (evaluation.checkpoint() instanceof FloatChargeStageMonitor.Checkpoint checkpoint) {
			result.addProperty("testResult", checkpoint.result().name());
		}
		return result;
	}

	private static JsonObject object(JsonElement parent, String key) {
		if (parent == null || !parent.isJsonObject()) return null;
		var child = parent.getAsJsonObject().get(key);
		return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
	}

	private static String string(JsonObject object, String key) {
		var value = object.get(key);
		return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
	}

	private static Double number(JsonObject object, String key) {
		var value = object.get(key);
		return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsDouble();
	}

	private static void addNullable(JsonObject object, String key, Number value) {
		if (value == null) object.add(key, com.google.gson.JsonNull.INSTANCE);
		else object.addProperty(key, value);
	}

	private static void addNullable(JsonObject object, String key, String value) {
		if (value == null) object.add(key, com.google.gson.JsonNull.INSTANCE);
		else object.addProperty(key, value);
	}

	private static void addNullable(JsonObject object, String key, Instant value) {
		addNullable(object, key, value == null ? null : value.toString());
	}

	private static JsonElement parse(String json) {
		return json == null ? new JsonObject() : JsonParser.parseString(json);
	}

	private static List<String> strings(JsonElement value) {
		if (value == null || !value.isJsonArray()) return List.of();
		var result = new ArrayList<String>();
		for (var item : value.getAsJsonArray()) result.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
		return List.copyOf(result);
	}

	private static List<String> acknowledgements(JsonElement evidence) {
		if (evidence == null || !evidence.isJsonObject()
				|| !evidence.getAsJsonObject().has("acknowledgedEvidence")) return List.of();
		return strings(evidence.getAsJsonObject().get("acknowledgedEvidence"));
	}

	private static PGobject jsonb(JsonElement value) throws SQLException {
		var object = new PGobject();
		object.setType("jsonb");
		object.setValue(GSON.toJson(value == null ? new JsonObject() : value));
		return object;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("Unable to hash calibration report", e);
		}
	}

	private static Double dod(Object checkpoint) {
		return checkpoint instanceof DischargeStageMonitor.Checkpoint value ? value.dodPercent() : null;
	}

	private static Double intervalSeconds(DischargeStageMonitor.Checkpoint checkpoint, Double domainCurrentA) {
		if (checkpoint == null || domainCurrentA == null || domainCurrentA <= 0) return null;
		return checkpoint.lastDeltaAh() / domainCurrentA * 3600.0;
	}

	private static Double target(Object checkpoint, boolean floatTarget) {
		CalibrationCalculations.TemperatureTargets targets = null;
		if (checkpoint instanceof ChargeStageMonitor.Checkpoint value) targets = value.targets();
		if (checkpoint instanceof FloatChargeStageMonitor.Checkpoint value) targets = value.targets();
		if (checkpoint instanceof InspectionStageMonitor.Checkpoint value) targets = value.targets();
		if (targets == null) return null;
		return floatTarget ? targets.floatStringVoltageV() : targets.boostStringVoltageV();
	}

	private static Instant instant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private static Long timestampMillis(Timestamp value) {
		return value == null ? null : value.toInstant().toEpochMilli();
	}

	private static String requireText(String value, String name) {
		var normalized = trimToNull(value);
		if (normalized == null) throw new IllegalArgumentException(name + " is required");
		return normalized;
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) return null;
		return value.trim();
	}

	private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
			throws SQLException {
		if (value == null) statement.setNull(index, Types.BIGINT);
		else statement.setLong(index, value);
	}

	private static void setNullableDouble(java.sql.PreparedStatement statement, int index, Double value)
			throws SQLException {
		if (value == null) statement.setNull(index, Types.NUMERIC);
		else statement.setDouble(index, value);
	}

	private static <T> T transaction(Connection connection, SqlWork<T> work) throws SQLException {
		connection.setAutoCommit(false);
		try {
			var result = work.run(connection);
			connection.commit();
			return result;
		} catch (SQLException | RuntimeException e) {
			try {
				connection.rollback();
			} catch (SQLException rollback) {
				e.addSuppressed(rollback);
			}
			throw e;
		}
	}

	@FunctionalInterface
	private interface SqlWork<T> {
		T run(Connection connection) throws SQLException;
	}
}
