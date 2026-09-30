
package io.openems.edge.bms.service.discharge.implement;

import io.openems.edge.bms.service.discharge.implement.persistence.VoltageSocCurveRepository;

import io.openems.edge.bms.service.discharge.contracts.DischargeCalibrationOperations;

import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.strings.contracts.BmsStringManager;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Persists discharge tests without participating in the Modbus polling cycle. */
@Component(name = "io.openems.edge.bms.platform.discharge.DischargeCalibrationService", service = { DischargeCalibrationService.class, DischargeCalibrationOperations.class }, immediate = true)
public class DischargeCalibrationService implements DischargeCalibrationOperations {

	@Reference private BmsDatabase database;
	@Reference private BmsStringManager strings;
	@Reference private ComponentManager componentManager;
	@Reference private VoltageSocCurveRepository curves;
	private ScheduledExecutorService worker;

	@Activate protected void activate() {
		this.worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "bms-discharge-calibration"));
		this.worker.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
	}
	@Deactivate protected void deactivate() { if (this.worker != null) this.worker.shutdownNow(); }

	public void create(int stringIndex, Instant start, boolean automatic, double startCurrent, String type,
			Double ratedCapacityAh) throws Exception {
		this.strings.reconcile();
		var definition = this.strings.get(stringIndex);
		var stringId = this.ensureString(definition);
		double referenceCapacity = ratedCapacityAh != null && ratedCapacityAh > 0 ? ratedCapacityAh : definition.ratedCapacity();
		if (referenceCapacity <= 0) throw new IllegalArgumentException("Rated capacity must be greater than zero.");
		Long baseline = null;
		if ("SOH_CHECK".equals(type)) {
			baseline = this.activeBaseline(stringId);
		} else type = "BASELINE_CALIBRATION";
		var settings = this.samplingSettings();
		try (var c = this.database.getConnection(); var s = c.prepareStatement("""
			INSERT INTO bms.discharge_event(string_id,event_type,baseline_event_id,status,automatic_mode,automatic_start_current_a,automatic_stop_current_a,nominal_capacity_ah,baseline_reference_ah,sample_interval_seconds,time_scale_factor,start_time,current_direction,current_provenance)
			VALUES (?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,'EVENT_DIRECTION')""")) {
			s.setObject(1, stringId); s.setString(2, type); if (baseline == null) s.setNull(3, java.sql.Types.BIGINT); else s.setLong(3, baseline);
			s.setBoolean(4, automatic); s.setDouble(5, Math.max(0, startCurrent)); s.setDouble(6, settings.stopCurrent());
			s.setDouble(7, referenceCapacity); s.setDouble(8, referenceCapacity); s.setInt(9, settings.intervalSeconds());
			s.setDouble(10, settings.timeScaleFactor()); s.setTimestamp(11, Timestamp.from(start));
			s.setString(12, settings.direction().name()); s.executeUpdate();
		}
	}

	public void stop(long eventId) throws Exception {
		try (var c = this.database.getConnection(); var s = c.prepareStatement(
				"""
				UPDATE bms.discharge_event
				SET status=CASE WHEN status='PENDING' THEN 'FAILED' ELSE 'STOPPED' END,
					failure_reason=CASE WHEN status='PENDING' THEN 'Stopped before the discharge start condition was reached.' ELSE NULL END,
					end_time=now(), updated_at=now()
				WHERE id=? AND status IN ('PENDING','RUNNING')
				""")) {
			s.setLong(1, eventId);
			if (s.executeUpdate() != 1) throw new IllegalArgumentException("Only a pending or running event can be stopped.");
		}
	}

	public void delete(long eventId) throws Exception {
		try (var c = this.database.getConnection()) {
			try (var check = c.prepareStatement("SELECT status,event_type FROM bms.discharge_event WHERE id=?")) {
				check.setLong(1, eventId);
				try (var r = check.executeQuery()) {
					if (!r.next()) throw new IllegalArgumentException("Discharge event was not found.");
					if ("PENDING".equals(r.getString(1)) || "RUNNING".equals(r.getString(1))) throw new IllegalArgumentException("Stop the active event before deleting it.");
				}
			}
			try (var dependent = c.prepareStatement("SELECT 1 FROM bms.discharge_event WHERE baseline_event_id=? LIMIT 1")) {
				dependent.setLong(1, eventId);
				try (var r = dependent.executeQuery()) { if (r.next()) throw new IllegalArgumentException("Remove dependent SoH checks before removing this baseline."); }
			}
			try (var delete = c.prepareStatement("DELETE FROM bms.discharge_event WHERE id=?")) { delete.setLong(1, eventId); delete.executeUpdate(); }
		}
	}

	public void activateBaseline(int stringIndex, long eventId) throws Exception {
		this.strings.reconcile();
		var stringId = this.ensureString(this.strings.get(stringIndex));
		try (var c = this.database.getConnection()) {
			try (var locked = c.prepareStatement("SELECT 1 FROM bms.discharge_event WHERE string_id=? AND event_type='SOH_CHECK' AND status='SUCCESS' LIMIT 1")) {
				locked.setObject(1, stringId);
				try (var r = locked.executeQuery()) { if (r.next()) throw new IllegalArgumentException("Remove all successful SoH checks before choosing another baseline."); }
			}
			try (var verify = c.prepareStatement("SELECT 1 FROM bms.discharge_event WHERE id=? AND string_id=? AND event_type='BASELINE_CALIBRATION' AND status='SUCCESS'")) {
				verify.setLong(1, eventId); verify.setObject(2, stringId);
				try (var r = verify.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Choose a successful baseline for this string."); }
			}
			try (var clear = c.prepareStatement("UPDATE bms.discharge_event SET active_baseline=false WHERE string_id=? AND event_type='BASELINE_CALIBRATION'")) { clear.setObject(1, stringId); clear.executeUpdate(); }
			try (var set = c.prepareStatement("UPDATE bms.discharge_event SET active_baseline=true,updated_at=now() WHERE id=?")) { set.setLong(1, eventId); set.executeUpdate(); }
			try (var clear = c.prepareStatement("UPDATE bms.soc_voltage_curve SET active=false WHERE string_id=?")) { clear.setObject(1, stringId); clear.executeUpdate(); }
			try (var set = c.prepareStatement("UPDATE bms.soc_voltage_curve SET active=true WHERE source_event_id=?")) { set.setLong(1, eventId); set.executeUpdate(); }
		}
		this.curves.refresh(stringIndex);
	}

	private UUID ensureString(io.openems.edge.bms.service.strings.model.BmsStringModels.Definition d) throws Exception {
		try (var c = this.database.getConnection()) {
			try (var q = c.prepareStatement("SELECT id FROM bms.bms_string WHERE string_key=?")) { q.setString(1, d.id()); try (var r=q.executeQuery()) { if(r.next()) return r.getObject(1, UUID.class); } }
			throw new SQLException("Synchronized BMS string is missing from PostgreSQL: " + d.id());
		}
	}
	private Long activeBaseline(UUID id) throws Exception { try(var c=this.database.getConnection();var s=c.prepareStatement("SELECT id FROM bms.discharge_event WHERE string_id=? AND event_type='BASELINE_CALIBRATION' AND status='SUCCESS' AND active_baseline=true")){s.setObject(1,id);try(var r=s.executeQuery()){return r.next()?r.getLong(1):null;}} }
	private void tickSafely() { try { this.tick(); } catch (Exception ignored) { } }
	private void tick() throws Exception {
		if (!this.database.isAvailable()) return;
		try(var c=this.database.getConnection();var s=c.prepareStatement("SELECT e.id,s.string_index,e.event_type,e.baseline_event_id,e.status,e.automatic_mode,e.automatic_start_current_a,e.automatic_stop_current_a,e.used_ah,e.last_sample_at,e.sample_interval_seconds,e.time_scale_factor,e.string_id,e.current_direction FROM bms.discharge_event e JOIN bms.bms_string s ON s.id=e.string_id WHERE e.status IN ('PENDING','RUNNING') AND e.start_time<=now()" );var r=s.executeQuery()) {
			while (r.next()) {
				var eventId = r.getLong(1);
				try {
					var directionValue = r.getString(14);
					// A pre-migration active event has no trustworthy sign mapping. Leave it
					// untouched for an operator to inspect/stop instead of guessing a sign.
					if (directionValue == null) continue;
					this.process(c, eventId, r.getInt(2), r.getString(3), r.getObject(4, Long.class), r.getString(5), r.getBoolean(6), r.getDouble(7), number(r, 8), number(r, 9), r.getTimestamp(10), r.getInt(11), r.getDouble(12), r.getObject(13, UUID.class), DischargeCurrentDirection.parse(directionValue));
				} catch (Exception e) {
					this.fail(c, eventId, "Calibration processing failed: " + safeMessage(e));
				}
			}
		}
	}
	private void process(java.sql.Connection c,long id,int index,String type,Long baseline,String status,boolean automatic,double startCurrent,Double stopCurrent,Double prior,Timestamp last,int sampleIntervalSeconds,double timeScaleFactor,UUID stringId,DischargeCurrentDirection direction) throws Exception {
		BmsStringCalculator calc=this.componentManager.getComponent("ctrlBmsHuasuCalc-str"+index); var snap=calc.latestSnapshot();
		if(snap==null || System.currentTimeMillis()-snap.capturedAt()>60_000) return;
		if (snap.stringCurrent() == null) return;
		double rawCurrent=snap.stringCurrent();
		double domainCurrent=direction.toDomainCurrent(rawCurrent);
		if ("PENDING".equals(status)) {
			// Manual events start at their scheduled time. Automatic events wait for
			// a real discharge current before their first sample is recorded.
			if (automatic && domainCurrent < startCurrent) return;
			if (!this.transitionToRunning(c, id)) return;
			status = "RUNNING";
		}
		if (domainCurrent < 0) {
			this.chargeDetected(c, id, "String current changed to charging while discharge calibration was running.");
			return;
		}
		if (automatic && stopCurrent != null && domainCurrent <= Math.max(0, stopCurrent)) {
			this.automaticStop(c, id, domainCurrent, Math.max(0, stopCurrent));
			return;
		}
		var scale = Math.max(0.000001, timeScaleFactor);
		var realIntervalMillis = Math.max(1_000L, Math.round(Math.max(1, sampleIntervalSeconds) * 1_000d / scale));
		if (last == null) {
			// Establish the first sampling timestamp without writing a zero-Ah
			// seed point. The first persisted point must represent elapsed discharge.
			try (var update = c.prepareStatement("UPDATE bms.discharge_event SET current_a=?,raw_current_a=?,domain_current_a=?,last_sample_at=now(),updated_at=now() WHERE id=? AND status='RUNNING'")) {
				update.setDouble(1, domainCurrent);
				update.setDouble(2, rawCurrent);
				update.setDouble(3, domainCurrent);
				update.setLong(4, id);
				update.executeUpdate();
			}
			return;
		}
		if (last != null && System.currentTimeMillis() - last.getTime() < realIntervalMillis) return;
		long now=System.currentTimeMillis(); double elapsed=(now-last.getTime())/1000d*scale; double used=(prior==null?0:prior)+Math.max(0,domainCurrent)*elapsed/3600d;
		try(var sample=c.prepareStatement("INSERT INTO bms.discharge_sample(event_id,sample_time,avg_voltage_v,current_a,delta_seconds,used_ah,temperature_c,raw_current_a,domain_current_a,current_direction,current_provenance,sample_valid) VALUES(?,now(),?,?,?,?,?,?,?,?,'EVENT_DIRECTION',true)");var update=c.prepareStatement("UPDATE bms.discharge_event SET used_ah=?,current_a=?,raw_current_a=?,domain_current_a=?,last_sample_at=now(),updated_at=now() WHERE id=?")){
			sample.setLong(1,id); sample.setObject(2,snap.averageVoltage()); sample.setDouble(3,domainCurrent); sample.setDouble(4,elapsed); sample.setDouble(5,used); sample.setObject(6,snap.averageTemperature()); sample.setDouble(7,rawCurrent); sample.setDouble(8,domainCurrent); sample.setString(9,direction.name()); sample.executeUpdate(); update.setDouble(1,used);update.setDouble(2,domainCurrent);update.setDouble(3,rawCurrent);update.setDouble(4,domainCurrent);update.setLong(5,id);update.executeUpdate(); }
		var d=this.strings.get(index); if(snap.minVoltage()!=null && snap.minVoltage()<=d.cutoffVoltage() && used>0) this.complete(c,id,index,stringId,type,baseline,used,d.ratedCapacity());
	}
	private void complete(java.sql.Connection c,long id,int stringIndex,UUID stringId,String type,Long baseline,double used,double nominal) throws Exception {
		double reference=nominal;
		if("SOH_CHECK".equals(type) && baseline != null) { try(var q=c.prepareStatement("SELECT baseline_reference_ah FROM bms.discharge_event WHERE id=?")){q.setLong(1,baseline);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("Baseline missing");reference=r.getDouble(1);}} }
		else { try(var q=c.prepareStatement("SELECT baseline_reference_ah FROM bms.discharge_event WHERE id=?")){q.setLong(1,id);try(var r=q.executeQuery()){if(r.next() && r.getObject(1)!=null) reference=r.getDouble(1);}} }
		if (reference <= 0) {
			this.fail(c, id, "No positive capacity reference is available for this calibration.");
			return;
		}
		double soh="BASELINE_CALIBRATION".equals(type)?100:Math.max(0,Math.min(100,used/reference*100));
		try(var u=c.prepareStatement("UPDATE bms.discharge_event SET status='SUCCESS',endpoint_reached=true,used_ah=?,baseline_reference_ah=?,computed_soh=?,failure_reason=NULL,end_time=now(),active_baseline=? WHERE id=? AND status='RUNNING'")){u.setDouble(1,used);u.setDouble(2,reference);u.setDouble(3,soh);u.setBoolean(4,"BASELINE_CALIBRATION".equals(type));u.setLong(5,id);if(u.executeUpdate()!=1)return;}
		if ("BASELINE_CALIBRATION".equals(type)) {
			this.buildVoltageSocCurve(c, stringId, id, reference);
			this.curves.refresh(stringIndex);
		}
	}

	/**
	 * Converts the completed baseline's average-voltage samples into a compact
	 * monotonic voltage-to-SoC map. One-percent SoC buckets remove sampling
	 * density bias; monotonic enforcement prevents noisy voltage reversals from
	 * producing ambiguous lookup results.
	 */
	private void buildVoltageSocCurve(java.sql.Connection connection, UUID stringId, long eventId, double referenceAh) throws Exception {
		var buckets = new TreeMap<Integer, ArrayList<Double>>();
		try (var query = connection.prepareStatement("SELECT used_ah,avg_voltage_v FROM bms.discharge_sample WHERE event_id=? AND used_ah>0 AND avg_voltage_v>0 ORDER BY used_ah")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var usedAh = result.getBigDecimal(1).doubleValue();
					var voltage = result.getBigDecimal(2).doubleValue();
					var soc = (int) Math.round(Math.max(0, Math.min(100, (1 - usedAh / referenceAh) * 100)));
					buckets.computeIfAbsent(soc, ignored -> new ArrayList<>()).add(voltage);
				}
			}
		}
		if (buckets.size() < 2) return;
		try (var delete = connection.prepareStatement("DELETE FROM bms.soc_voltage_curve WHERE source_event_id=?")) {
			delete.setLong(1, eventId);
			delete.executeUpdate();
		}
		try (var deactivate = connection.prepareStatement("UPDATE bms.soc_voltage_curve SET active=false WHERE string_id=?")) {
			deactivate.setObject(1, stringId);
			deactivate.executeUpdate();
		}
		try (var insert = connection.prepareStatement("INSERT INTO bms.soc_voltage_curve(string_id,source_event_id,soc_pct,voltage_v,sample_count,active) VALUES(?,?,?,?,?,true)")) {
			double previousVoltage = Double.NEGATIVE_INFINITY;
			for (var entry : buckets.entrySet()) {
				var voltage = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
				voltage = Math.max(voltage, previousVoltage);
				previousVoltage = voltage;
				insert.setObject(1, stringId);
				insert.setLong(2, eventId);
				insert.setDouble(3, entry.getKey());
				insert.setDouble(4, voltage);
				insert.setInt(5, entry.getValue().size());
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private void fail(java.sql.Connection connection, long eventId, String reason) throws java.sql.SQLException {
		try (var update = connection.prepareStatement("UPDATE bms.discharge_event SET status='FAILED',failure_reason=?,end_time=now(),updated_at=now() WHERE id=? AND status IN ('PENDING','RUNNING')")) {
			update.setString(1, reason);
			update.setLong(2, eventId);
			update.executeUpdate();
		}
	}

	private void chargeDetected(java.sql.Connection connection, long eventId, String reason) throws java.sql.SQLException {
		try (var update = connection.prepareStatement("UPDATE bms.discharge_event SET status='CHARGE_DETECTED',failure_reason=?,end_time=now(),updated_at=now() WHERE id=? AND status='RUNNING'")) {
			update.setString(1, reason);
			update.setLong(2, eventId);
			update.executeUpdate();
		}
	}

	private void automaticStop(java.sql.Connection connection, long eventId, double domainCurrent, double stopCurrent)
			throws java.sql.SQLException {
		try (var update = connection.prepareStatement("UPDATE bms.discharge_event SET status='STOPPED',failure_reason=?,end_time=now(),updated_at=now() WHERE id=? AND status='RUNNING'")) {
			update.setString(1, "Automatic stop threshold reached: domain current " + domainCurrent
					+ " A <= " + stopCurrent + " A.");
			update.setLong(2, eventId);
			update.executeUpdate();
		}
	}

	private boolean transitionToRunning(java.sql.Connection connection, long eventId) throws java.sql.SQLException {
		try (var update = connection.prepareStatement("""
				UPDATE bms.discharge_event
				SET status='RUNNING', updated_at=now(), last_sample_at=now()
				WHERE id=? AND status='PENDING'
				""")) {
			update.setLong(1, eventId);
			return update.executeUpdate() == 1;
		}
	}
	private static Double number(java.sql.ResultSet result, int column) throws java.sql.SQLException {
		var value = result.getBigDecimal(column);
		return value == null ? null : value.doubleValue();
	}

	private SamplingSettings samplingSettings() throws Exception {
		try (var connection = this.database.getConnection(); var query = connection.prepareStatement("SELECT sample_interval_seconds,time_scale_factor,direction,automatic_stop_current_a FROM bms.discharge_calibration_config WHERE id=1"); var result = query.executeQuery()) {
			if (result.next()) return new SamplingSettings(Math.max(1, result.getInt(1)), Math.max(0.000001, result.getDouble(2)), DischargeCurrentDirection.parse(result.getString(3)), Math.max(0, result.getDouble(4)));
		}
		return new SamplingSettings(1800, 1, DischargeCurrentDirection.NEGATIVE, 1);
	}

	private record SamplingSettings(int intervalSeconds, double timeScaleFactor, DischargeCurrentDirection direction,
			double stopCurrent) {}

	private static String safeMessage(Exception exception) {
		var message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 450));
	}
}
