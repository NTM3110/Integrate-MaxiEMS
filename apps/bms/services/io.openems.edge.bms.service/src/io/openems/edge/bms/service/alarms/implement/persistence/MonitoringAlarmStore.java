package io.openems.edge.bms.service.alarms.implement.persistence;

import io.openems.edge.bms.service.alarms.implement.AlarmConditionResolver;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecyclePolicy;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.State;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Status;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Transition;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService.AcknowledgeResult;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Transactional durable alarm lifecycle driven by atomic monitoring frames. */
public final class MonitoringAlarmStore {

	public enum ProcessResult {
		PROCESSED, DUPLICATE, LATE, STRING_NOT_FOUND
	}

	private final BmsDatabase database;

	public MonitoringAlarmStore(BmsDatabase database) {
		this.database = database;
	}

	public List<MonitoringAlarmService.AlarmOccurrence> list(String stringKey, boolean includeCleared, int offset,
			int limit) throws SQLException {
		return listWithCheckpoint(stringKey, includeCleared, offset, limit).alarms();
	}

	public MonitoringAlarmService.AlarmListResult listWithCheckpoint(String stringKey, boolean includeCleared,
			int offset, int limit) throws SQLException {
		if (offset < 0) throw new IllegalArgumentException("Alarm offset must not be negative");
		if (limit < 1 || limit > 200) throw new IllegalArgumentException("Alarm limit must be between 1 and 200");
		if (stringKey != null && stringKey.isBlank()) throw new IllegalArgumentException("stringKey must not be blank");
		try (var connection = this.database.getConnection()) {
			connection.setReadOnly(true);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			connection.setAutoCommit(false);
			try {
				var checkpoint = readProcessorCheckpoint(connection, stringKey);
				var alarms = readOccurrences(connection, stringKey, includeCleared, offset, limit);
				connection.commit();
				return new MonitoringAlarmService.AlarmListResult(alarms, checkpoint);
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	private static MonitoringAlarmService.ProcessorCheckpoint readProcessorCheckpoint(Connection connection,
			String stringKey) throws SQLException {
		if (stringKey == null) return MonitoringAlarmService.ProcessorCheckpoint.unavailable();
		try (var statement = connection.prepareStatement("""
			SELECT p.last_publication_generation,p.last_publication_revision,p.updated_at
			FROM bms.monitoring_alarm_processor_state p
			JOIN bms.bms_string s ON s.id=p.string_id WHERE s.string_key=?
			""")) {
			statement.setString(1, stringKey);
			try (var rows = statement.executeQuery()) {
				if (!rows.next()) return MonitoringAlarmService.ProcessorCheckpoint.unavailable();
				var generation = rows.getObject(1, UUID.class);
				var revision = (Long) rows.getObject(2);
				if (generation == null || revision == null) {
					return MonitoringAlarmService.ProcessorCheckpoint.unavailable();
				}
				return new MonitoringAlarmService.ProcessorCheckpoint(generation, revision, instant(rows, 3));
			}
		}
	}

	private static List<MonitoringAlarmService.AlarmOccurrence> readOccurrences(Connection connection,
			String stringKey, boolean includeCleared, int offset, int limit) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT o.id,s.string_key,o.alarm_type,o.subject_key,o.cell_index,o.status,o.condition_active,
				o.raised_at,o.condition_cleared_at,o.cleared_at,o.acknowledged_at,o.acknowledged_by,
				o.trigger_observed_value,o.trigger_threshold_value,o.trigger_reason,o.trigger_generation,
				o.trigger_sequence,o.trigger_captured_at,o.reference_event_id,o.reference_curve_version_id,
				o.reference_process_id,o.reference_process_attempt,o.reference_float_sample_id,
				o.reference_voc_sample_id,o.reference_content_hash,o.active_calibration_event_id,
				o.active_calibration_stage_id,o.active_calibration_process_id,o.active_event_type,
				o.active_stage_type,o.active_process_type,o.active_charge_current_limit_a,o.policy_version,
				o.raise_samples,o.clear_samples,o.hysteresis_value,o.hysteresis_unit,o.latching
			FROM bms.monitoring_alarm_occurrence o
			JOIN bms.bms_string s ON s.id=o.string_id
			WHERE (? IS NULL OR s.string_key=?) AND (? OR o.cleared_at IS NULL)
			ORDER BY o.raised_at DESC,o.id DESC OFFSET ? LIMIT ?
			""")) {
			statement.setString(1, stringKey);
			statement.setString(2, stringKey);
			statement.setBoolean(3, includeCleared);
			statement.setInt(4, offset);
			statement.setInt(5, limit);
			var values = new ArrayList<MonitoringAlarmService.AlarmOccurrence>();
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					var reference = rows.getObject(19) == null ? null : new BmsMonitoringSnapshot.ReferenceIdentity(rows.getLong(19), rows.getLong(20),
							rows.getLong(21), rows.getInt(22), rows.getLong(23), rows.getLong(24), rows.getString(25));
					var active = new io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext(
							(Long) rows.getObject(26), (Long) rows.getObject(27), (Long) rows.getObject(28),
							rows.getString(29), rows.getString(30), rows.getString(31), (Double) rows.getObject(32));
					var occurrenceId = rows.getLong(1);
					values.add(new MonitoringAlarmService.AlarmOccurrence(occurrenceId, rows.getString(2),
							Alarm.valueOf(rows.getString(3)), rows.getString(4), (Integer) rows.getObject(5),
							rows.getString(6), rows.getBoolean(7), instant(rows, 8), instant(rows, 9), instant(rows, 10),
							instant(rows, 11), rows.getString(12), (Double) rows.getObject(13),
							(Double) rows.getObject(14), rows.getString(15), rows.getObject(16, UUID.class),
							rows.getLong(17), instant(rows, 18), reference, active, rows.getInt(33), rows.getInt(34),
							rows.getInt(35), rows.getDouble(36), rows.getString(37), rows.getBoolean(38),
							transitions(connection, occurrenceId)));
				}
			}
			return List.copyOf(values);
		}
	}

	private static List<MonitoringAlarmService.AlarmTransition> transitions(Connection connection, long occurrenceId)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT id,transition_type,transitioned_at,actor,observed_value,threshold_value,reason,
				source_generation,source_sequence,source_captured_at,reference_event_id,
				reference_curve_version_id,reference_process_id,reference_process_attempt,
				reference_float_sample_id,reference_voc_sample_id,reference_content_hash,policy_version
			FROM bms.monitoring_alarm_transition WHERE occurrence_id=? ORDER BY id
			""")) {
			statement.setLong(1, occurrenceId);
			var result = new ArrayList<MonitoringAlarmService.AlarmTransition>();
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					var reference = rows.getObject(11) == null ? null : new BmsMonitoringSnapshot.ReferenceIdentity(rows.getLong(11), rows.getLong(12),
							rows.getLong(13), rows.getInt(14), rows.getLong(15), rows.getLong(16), rows.getString(17));
					result.add(new MonitoringAlarmService.AlarmTransition(rows.getLong(1), rows.getString(2),
							instant(rows, 3), rows.getString(4), (Double) rows.getObject(5),
							(Double) rows.getObject(6), rows.getString(7), reference, rows.getObject(8, UUID.class),
							rows.getLong(9), instant(rows, 10), rows.getInt(18)));
				}
			}
			return List.copyOf(result);
		}
	}

	public ProcessResult process(BmsMonitoringSnapshot frame, AlarmLifecyclePolicy policy, Instant processedAt)
			throws SQLException {
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				var stringId = findStringId(connection, frame.stringKey());
				if (stringId == null) {
					connection.rollback();
					return ProcessResult.STRING_NOT_FOUND;
				}
				ensureProcessorState(connection, stringId);
				var checkpoint = lockProcessorState(connection, stringId);
				var replay = replayResult(checkpoint, frame);
				if (replay != null) {
					connection.commit();
					return replay;
				}
				var states = loadStates(connection, stringId);
				var keys = subjects(frame, states);
				for (var key : keys) {
					var stored = states.getOrDefault(key, StoredState.inactive(key));
					var rule = policy.rule(key.alarm());
					var observation = AlarmConditionResolver.resolve(frame, key.alarm(), key.cellIndex(),
							stored.threshold(), stored.occurrenceId() != null, rule);
					boolean newMeasurement = !java.util.Objects.equals(checkpoint.generation(), frame.snapshotGeneration())
							|| !java.util.Objects.equals(checkpoint.sequence(), frame.snapshotSequence());
					// Re-evaluation is not another physical debounce sample. A quality loss
					// can still raise the sensor failure immediately on the stale publication.
					var result = newMeasurement || key.alarm() == Alarm.SENSOR_MODULE_FAILURE
							&& observation.observation() == AlarmLifecycleStateMachine.Observation.ACTIVE
							? AlarmLifecycleStateMachine.apply(stored.state(), observation.observation(), rule)
							: new AlarmLifecycleStateMachine.Result(stored.state(), List.of());
					var applied = applyTransitions(connection, stringId, stored.occurrenceId(), key, result,
							observation, frame, rule, processedAt);
					upsertState(connection, stringId, key,
							applied.forceInactive() ? State.inactive() : result.state(), applied.occurrenceId(),
							observation, frame);
				}
				updateProcessorState(connection, stringId, frame);
				connection.commit();
				return ProcessResult.PROCESSED;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	public AcknowledgeResult acknowledge(long occurrenceId, String actor, Instant acknowledgedAt)
			throws SQLException {
		if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Acknowledgement actor is required");
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				// Keep the same state -> occurrence lock order as frame processing to avoid
				// deadlocks between acknowledgement and a simultaneous clear transition.
				var state = lockStateForOccurrence(connection, occurrenceId);
				var occurrence = lockOccurrence(connection, occurrenceId);
				if (occurrence == null) return rollback(connection, AcknowledgeResult.NOT_FOUND);
				if (occurrence.cleared()) return rollback(connection, AcknowledgeResult.NOT_ACTIVE);
				if (occurrence.acknowledged()) {
					return rollback(connection, AcknowledgeResult.ALREADY_ACKNOWLEDGED);
				}
				if (state == null) return rollback(connection, AcknowledgeResult.NOT_ACTIVE);
				markAcknowledged(connection, occurrenceId, actor, acknowledgedAt);
				insertAckTransition(connection, occurrence, state, actor, acknowledgedAt);
				if (!state.state().conditionActive()) {
					clearOccurrence(connection, occurrenceId, acknowledgedAt);
					insertTransition(connection, occurrenceId, "CLEARED", state.observed(), state.threshold(),
							"Latched alarm cleared after acknowledgement", state.frame(), occurrence.reference(),
							occurrence.rule(), acknowledgedAt, actor);
					setInactive(connection, occurrence.stringId(), occurrence.alarm(), occurrence.subjectKey());
					connection.commit();
					return AcknowledgeResult.ACKNOWLEDGED_AND_CLEARED;
				}
				connection.commit();
				return AcknowledgeResult.ACKNOWLEDGED_ACTIVE;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	private static ProcessResult replayResult(Checkpoint state, BmsMonitoringSnapshot frame) {
		if (java.util.Objects.equals(state.publicationGeneration(), frame.publicationGeneration())
				&& state.publicationRevision() != null && frame.publicationRevision() <= state.publicationRevision()) {
			return frame.publicationRevision() == state.publicationRevision() ? ProcessResult.DUPLICATE : ProcessResult.LATE;
		}
		if (state.generation() == null) return null;
		if (state.generation().equals(frame.snapshotGeneration())) {
			if (state.sequence() != null && frame.snapshotSequence() == state.sequence()) {
				return null;
			}
			if (state.sequence() != null && frame.snapshotSequence() < state.sequence()) return ProcessResult.LATE;
		}
		if (state.capturedAt() != null && !frame.capturedAt().isAfter(state.capturedAt())) return ProcessResult.LATE;
		return null;
	}

	private static LinkedHashSet<SubjectKey> subjects(BmsMonitoringSnapshot frame,
			Map<SubjectKey, StoredState> states) {
		var result = new LinkedHashSet<>(states.keySet());
		// The sensor alarm can be derived from frame quality even if a malformed or
		// older producer omitted its AlarmDetail. Always evaluate its string subject.
		result.add(new SubjectKey(Alarm.SENSOR_MODULE_FAILURE, null));
		for (var detail : frame.alarmDetails()) {
			result.add(new SubjectKey(detail.alarm(), detail.cellIndex()));
		}
		return result;
	}

	private static AppliedTransitions applyTransitions(Connection connection, UUID stringId, Long previousOccurrenceId,
			SubjectKey key, AlarmLifecycleStateMachine.Result result, AlarmConditionResolver.Result observation,
			BmsMonitoringSnapshot frame, AlarmLifecyclePolicy.Rule rule, Instant at) throws SQLException {
		Long occurrenceId = previousOccurrenceId;
		boolean forceInactive = false;
		for (var transition : result.transitions()) {
			if (transition == Transition.RAISED) {
				occurrenceId = insertOccurrence(connection, stringId, key, observation, frame, rule, at);
			}
			if (occurrenceId == null) throw new SQLException("Alarm transition has no open occurrence");
			insertTransition(connection, occurrenceId, transition.name(), observation.observed(),
					observation.threshold(), observation.reason(), FrameIdentity.of(frame), frame.reference(), rule,
					at, null);
			if (transition == Transition.CONDITION_CLEARED) {
				markConditionCleared(connection, occurrenceId, result.state().status(), at);
				if (rule.latching() && occurrenceAcknowledged(connection, occurrenceId)) {
					clearOccurrence(connection, occurrenceId, at);
					insertTransition(connection, occurrenceId, Transition.CLEARED.name(), observation.observed(),
							observation.threshold(), "Condition cleared after prior acknowledgement",
							FrameIdentity.of(frame), frame.reference(), rule, at, null);
					occurrenceId = null;
					forceInactive = true;
				}
			}
			if (transition == Transition.CONDITION_REACTIVATED) reactivateOccurrence(connection, occurrenceId);
			if (transition == Transition.CLEARED) {
				clearOccurrence(connection, occurrenceId, at);
				occurrenceId = null;
			}
		}
		return new AppliedTransitions(occurrenceId == null ? 0 : occurrenceId, forceInactive);
	}

	private static UUID findStringId(Connection connection, String stringKey) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT id FROM bms.bms_string WHERE string_key=?")) {
			statement.setString(1, stringKey);
			try (var result = statement.executeQuery()) {
				return result.next() ? result.getObject(1, UUID.class) : null;
			}
		}
	}

	private static void ensureProcessorState(Connection connection, UUID stringId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_alarm_processor_state(string_id) VALUES (?) ON CONFLICT DO NOTHING
			""")) {
			statement.setObject(1, stringId);
			statement.executeUpdate();
		}
	}

	private static Checkpoint lockProcessorState(Connection connection, UUID stringId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT last_generation,last_sequence,last_captured_at,last_publication_generation,last_publication_revision
			FROM bms.monitoring_alarm_processor_state WHERE string_id=? FOR UPDATE
			""")) {
			statement.setObject(1, stringId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new SQLException("Alarm processor state was not created");
				return new Checkpoint(result.getObject(1, UUID.class), (Long) result.getObject(2), instant(result, 3),
						result.getObject(4, UUID.class), (Long) result.getObject(5));
			}
		}
	}

	private static Map<SubjectKey, StoredState> loadStates(Connection connection, UUID stringId)
			throws SQLException {
		var result = new LinkedHashMap<SubjectKey, StoredState>();
		try (var statement = connection.prepareStatement("""
			SELECT alarm_type,cell_index,lifecycle_status,raise_count,clear_count,condition_active,
				occurrence_id,last_observed_value,last_threshold_value,last_reason,last_generation,
				last_sequence,last_captured_at
			FROM bms.monitoring_alarm_state WHERE string_id=? FOR UPDATE
			""")) {
			statement.setObject(1, stringId);
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					var key = new SubjectKey(Alarm.valueOf(rows.getString(1)), (Integer) rows.getObject(2));
					var state = new State(Status.valueOf(rows.getString(3)), rows.getInt(4), rows.getInt(5),
							rows.getBoolean(6));
					result.put(key, new StoredState(key, state, (Long) rows.getObject(7),
							(Double) rows.getObject(8), (Double) rows.getObject(9), rows.getString(10),
							new FrameIdentity(rows.getObject(11, UUID.class), value(rows, 12), instant(rows, 13))));
				}
			}
		}
		return result;
	}

	private static void updateProcessorState(Connection connection, UUID stringId, BmsMonitoringSnapshot frame)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_processor_state
			SET last_generation=?,last_sequence=?,last_captured_at=?,last_publication_generation=?,
				last_publication_revision=?,updated_at=now() WHERE string_id=?
			""")) {
			statement.setObject(1, frame.snapshotGeneration());
			statement.setLong(2, frame.snapshotSequence());
			statement.setTimestamp(3, Timestamp.from(frame.capturedAt()));
			statement.setObject(4, frame.publicationGeneration());
			statement.setLong(5, frame.publicationRevision());
			statement.setObject(6, stringId);
			statement.executeUpdate();
		}
	}

	private static void upsertState(Connection connection, UUID stringId, SubjectKey key, State state,
			long occurrenceId, AlarmConditionResolver.Result observation, BmsMonitoringSnapshot frame) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_alarm_state (
				string_id,alarm_type,subject_key,cell_index,lifecycle_status,raise_count,clear_count,
				condition_active,occurrence_id,last_observed_value,last_threshold_value,last_reason,
				last_generation,last_sequence,last_captured_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON CONFLICT (string_id,alarm_type,subject_key) DO UPDATE SET
				lifecycle_status=EXCLUDED.lifecycle_status,raise_count=EXCLUDED.raise_count,
				clear_count=EXCLUDED.clear_count,condition_active=EXCLUDED.condition_active,
				occurrence_id=EXCLUDED.occurrence_id,last_observed_value=COALESCE(EXCLUDED.last_observed_value,
					bms.monitoring_alarm_state.last_observed_value),
				last_threshold_value=COALESCE(EXCLUDED.last_threshold_value,
					bms.monitoring_alarm_state.last_threshold_value),last_reason=EXCLUDED.last_reason,
				last_generation=EXCLUDED.last_generation,last_sequence=EXCLUDED.last_sequence,
				last_captured_at=EXCLUDED.last_captured_at,updated_at=now()
			""")) {
			int index = 1;
			statement.setObject(index++, stringId);
			statement.setString(index++, key.alarm().name());
			statement.setString(index++, key.subjectKey());
			setInteger(statement, index++, key.cellIndex());
			statement.setString(index++, state.status().name());
			statement.setInt(index++, state.raiseCount());
			statement.setInt(index++, state.clearCount());
			statement.setBoolean(index++, state.conditionActive());
			if (occurrenceId == 0) statement.setNull(index++, Types.BIGINT); else statement.setLong(index++, occurrenceId);
			setDouble(statement, index++, observation.observed());
			setDouble(statement, index++, observation.threshold());
			statement.setString(index++, observation.reason());
			statement.setObject(index++, frame.snapshotGeneration());
			statement.setLong(index++, frame.snapshotSequence());
			statement.setTimestamp(index, Timestamp.from(frame.capturedAt()));
			statement.executeUpdate();
		}
	}

	private static long insertOccurrence(Connection connection, UUID stringId, SubjectKey key,
			AlarmConditionResolver.Result observation, BmsMonitoringSnapshot frame, AlarmLifecyclePolicy.Rule rule,
			Instant at) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_alarm_occurrence (
				string_id,alarm_type,subject_key,cell_index,status,condition_active,raised_at,
				trigger_observed_value,trigger_threshold_value,trigger_reason,trigger_generation,
				trigger_sequence,trigger_captured_at,reference_event_id,reference_curve_version_id,
				reference_process_id,reference_process_attempt,reference_float_sample_id,reference_voc_sample_id,
				reference_content_hash,active_calibration_event_id,active_calibration_stage_id,
				active_calibration_process_id,active_event_type,active_stage_type,active_process_type,
				active_charge_current_limit_a,policy_version,raise_samples,clear_samples,hysteresis_value,
				hysteresis_unit,latching)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
			""")) {
			int index = 1;
			statement.setObject(index++, stringId);
			statement.setString(index++, key.alarm().name());
			statement.setString(index++, key.subjectKey());
			setInteger(statement, index++, key.cellIndex());
			statement.setString(index++, "ACTIVE");
			statement.setBoolean(index++, true);
			statement.setTimestamp(index++, Timestamp.from(at));
			setDouble(statement, index++, observation.observed());
			setDouble(statement, index++, observation.threshold());
			statement.setString(index++, observation.reason());
			statement.setObject(index++, frame.snapshotGeneration());
			statement.setLong(index++, frame.snapshotSequence());
			statement.setTimestamp(index++, Timestamp.from(frame.capturedAt()));
			index = setReference(statement, index, frame.reference());
			var context = frame.activeCalibration();
			setLong(statement, index++, context.calibrationEventId());
			setLong(statement, index++, context.calibrationStageId());
			setLong(statement, index++, context.calibrationProcessId());
			statement.setString(index++, context.eventType());
			statement.setString(index++, context.stageType());
			statement.setString(index++, context.processType());
			setDouble(statement, index++, context.chargeCurrentLimitA());
			setRule(statement, index, rule);
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private static void insertTransition(Connection connection, long occurrenceId, String type, Double observed,
			Double threshold, String reason, FrameIdentity frame, BmsMonitoringSnapshot.ReferenceIdentity reference,
			AlarmLifecyclePolicy.Rule rule, Instant at, String actor) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_alarm_transition (
				occurrence_id,transition_type,transitioned_at,actor,observed_value,threshold_value,reason,
				source_generation,source_sequence,source_captured_at,reference_event_id,
				reference_curve_version_id,reference_process_id,reference_process_attempt,
				reference_float_sample_id,reference_voc_sample_id,reference_content_hash,
				policy_version,raise_samples,clear_samples,hysteresis_value,hysteresis_unit,latching)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
			""")) {
			int index = 1;
			statement.setLong(index++, occurrenceId);
			statement.setString(index++, type);
			statement.setTimestamp(index++, Timestamp.from(at));
			statement.setString(index++, actor);
			setDouble(statement, index++, observed);
			setDouble(statement, index++, threshold);
			statement.setString(index++, reason);
			statement.setObject(index++, frame.generation());
			statement.setLong(index++, frame.sequence());
			statement.setTimestamp(index++, Timestamp.from(frame.capturedAt()));
			index = setReference(statement, index, reference);
			setRule(statement, index, rule);
			statement.executeUpdate();
		}
	}

	private static void markConditionCleared(Connection connection, long occurrenceId, Status status, Instant at)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_occurrence SET condition_active=FALSE,condition_cleared_at=?,status=? WHERE id=?
			""")) {
			statement.setTimestamp(1, Timestamp.from(at));
			statement.setString(2, status == Status.LATCHED ? "LATCHED" : "ACTIVE");
			statement.setLong(3, occurrenceId);
			statement.executeUpdate();
		}
	}

	private static void reactivateOccurrence(Connection connection, long occurrenceId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_occurrence
			SET condition_active=TRUE,condition_cleared_at=NULL,status='ACTIVE' WHERE id=?
			""")) {
			statement.setLong(1, occurrenceId);
			statement.executeUpdate();
		}
	}

	private static void clearOccurrence(Connection connection, long occurrenceId, Instant at) throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_occurrence SET condition_active=FALSE,status='CLEARED',cleared_at=? WHERE id=?
			""")) {
			statement.setTimestamp(1, Timestamp.from(at));
			statement.setLong(2, occurrenceId);
			statement.executeUpdate();
		}
	}

	private static boolean occurrenceAcknowledged(Connection connection, long occurrenceId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"SELECT acknowledged_at IS NOT NULL FROM bms.monitoring_alarm_occurrence WHERE id=?")) {
			statement.setLong(1, occurrenceId);
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getBoolean(1);
			}
		}
	}

	private static Occurrence lockOccurrence(Connection connection, long occurrenceId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT string_id,alarm_type,subject_key,cleared_at IS NOT NULL,acknowledged_at IS NOT NULL,
				reference_event_id,reference_curve_version_id,reference_process_id,reference_process_attempt,
				reference_float_sample_id,reference_voc_sample_id,reference_content_hash,
				raise_samples,clear_samples,hysteresis_value,hysteresis_unit,latching
			FROM bms.monitoring_alarm_occurrence WHERE id=? FOR UPDATE
			""")) {
			statement.setLong(1, occurrenceId);
			try (var row = statement.executeQuery()) {
				if (!row.next()) return null;
				var reference = row.getObject(6) == null ? null : new BmsMonitoringSnapshot.ReferenceIdentity(row.getLong(6), row.getLong(7), row.getLong(8),
						row.getInt(9), row.getLong(10), row.getLong(11), row.getString(12));
				var rule = new AlarmLifecyclePolicy.Rule(row.getInt(13), row.getInt(14), row.getDouble(15),
						AlarmLifecyclePolicy.HysteresisUnit.valueOf(row.getString(16)), row.getBoolean(17));
				return new Occurrence(row.getObject(1, UUID.class), Alarm.valueOf(row.getString(2)), row.getString(3),
						row.getBoolean(4), row.getBoolean(5), reference, rule);
			}
		}
	}

	private static StoredState lockStateForOccurrence(Connection connection, long occurrenceId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT alarm_type,cell_index,lifecycle_status,raise_count,clear_count,condition_active,
				last_observed_value,last_threshold_value,last_reason,last_generation,last_sequence,last_captured_at
			FROM bms.monitoring_alarm_state WHERE occurrence_id=? FOR UPDATE
			""")) {
			statement.setLong(1, occurrenceId);
			try (var row = statement.executeQuery()) {
				if (!row.next()) return null;
				var key = new SubjectKey(Alarm.valueOf(row.getString(1)), (Integer) row.getObject(2));
				return new StoredState(key, new State(Status.valueOf(row.getString(3)), row.getInt(4), row.getInt(5),
						row.getBoolean(6)), occurrenceId, (Double) row.getObject(7), (Double) row.getObject(8),
						row.getString(9), new FrameIdentity(row.getObject(10, UUID.class), row.getLong(11), instant(row, 12)));
			}
		}
	}

	private static void markAcknowledged(Connection connection, long occurrenceId, String actor, Instant at)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_occurrence SET acknowledged_at=?,acknowledged_by=? WHERE id=?
			""")) {
			statement.setTimestamp(1, Timestamp.from(at));
			statement.setString(2, actor);
			statement.setLong(3, occurrenceId);
			statement.executeUpdate();
		}
	}

	private static void insertAckTransition(Connection connection, Occurrence occurrence, StoredState state,
			String actor, Instant at) throws SQLException {
		insertTransition(connection, state.occurrenceId(), "ACKNOWLEDGED", state.observed(), state.threshold(),
				"Alarm acknowledged", state.frame(), occurrence.reference(), occurrence.rule(), at, actor);
	}

	private static void setInactive(Connection connection, UUID stringId, Alarm alarm, String subjectKey)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_alarm_state SET lifecycle_status='INACTIVE',raise_count=0,clear_count=0,
				condition_active=FALSE,occurrence_id=NULL,updated_at=now()
			WHERE string_id=? AND alarm_type=? AND subject_key=?
			""")) {
			statement.setObject(1, stringId);
			statement.setString(2, alarm.name());
			statement.setString(3, subjectKey);
			statement.executeUpdate();
		}
	}

	private static <T> T rollback(Connection connection, T result) throws SQLException {
		connection.rollback();
		return result;
	}

	private static int setReference(PreparedStatement statement, int index,
			BmsMonitoringSnapshot.ReferenceIdentity reference) throws SQLException {
		if (reference == null) {
			for (int offset = 0; offset < 7; offset++) statement.setNull(index++, offset == 6 ? Types.VARCHAR
					: offset == 3 ? Types.INTEGER : Types.BIGINT);
			return index;
		}
		statement.setLong(index++, reference.calibrationEventId());
		statement.setLong(index++, reference.curveVersionId());
		statement.setLong(index++, reference.calibrationProcessId());
		statement.setInt(index++, reference.processAttemptNumber());
		statement.setLong(index++, reference.recoveryFloatSampleId());
		statement.setLong(index++, reference.vocSampleId());
		statement.setString(index++, reference.contentHash());
		return index;
	}

	private static int setRule(PreparedStatement statement, int index, AlarmLifecyclePolicy.Rule rule)
			throws SQLException {
		statement.setInt(index++, AlarmLifecyclePolicy.VERSION);
		statement.setInt(index++, rule.raiseSamples());
		statement.setInt(index++, rule.clearSamples());
		statement.setDouble(index++, rule.hysteresis());
		statement.setString(index++, rule.hysteresisUnit().name());
		statement.setBoolean(index++, rule.latching());
		return index;
	}

	private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
		if (value == null || !Double.isFinite(value)) statement.setNull(index, Types.DOUBLE);
		else statement.setDouble(index, value);
	}

	private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
		if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
	}

	private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
		if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
	}

	private static Instant instant(ResultSet result, int index) throws SQLException {
		var value = result.getTimestamp(index);
		return value == null ? null : value.toInstant();
	}

	private static long value(ResultSet result, int index) throws SQLException {
		var value = (Long) result.getObject(index);
		return value == null ? 0 : value;
	}

	private record SubjectKey(Alarm alarm, Integer cellIndex) {
		String subjectKey() {
			return this.cellIndex == null ? "STRING" : "CELL:" + this.cellIndex;
		}
	}

	private record Checkpoint(UUID generation, Long sequence, Instant capturedAt,
			UUID publicationGeneration, Long publicationRevision) {
	}

	private record FrameIdentity(UUID generation, long sequence, Instant capturedAt) {
		static FrameIdentity of(BmsMonitoringSnapshot frame) {
			return new FrameIdentity(frame.snapshotGeneration(), frame.snapshotSequence(), frame.capturedAt());
		}
	}

	private record StoredState(SubjectKey key, State state, Long occurrenceId, Double observed, Double threshold,
			String reason, FrameIdentity frame) {
		static StoredState inactive(SubjectKey key) {
			return new StoredState(key, State.inactive(), null, null, null, null, null);
		}
	}

	private record Occurrence(UUID stringId, Alarm alarm, String subjectKey, boolean cleared, boolean acknowledged,
			BmsMonitoringSnapshot.ReferenceIdentity reference, AlarmLifecyclePolicy.Rule rule) {
	}

	private record AppliedTransitions(long occurrenceId, boolean forceInactive) {
	}
}
