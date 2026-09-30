package io.openems.edge.bms.service.maintenance.implement;

import io.openems.edge.bms.service.maintenance.contracts.BmsMaintenanceService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.io.ModbusSerialTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.SerialConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.SerialParameters;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringLifecycle;
import io.openems.edge.bridge.modbus.api.BridgeModbusSerial;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.startstop.StartStop;

/**
 * Runs the legacy diagnostic register operations only while the shared bridge
 * is stopped.  It never modifies TA, TC or calculator configurations.
 */
@Component(name = "io.openems.edge.bms.core.maintenance.BmsMaintenanceServiceImpl", service = BmsMaintenanceService.class)
public class BmsMaintenanceServiceImpl implements BmsMaintenanceService {
	private static final int TC_ID = 206;
	private static final int TC_MODULE_CHECK_REGISTER = 0x0000;
	private static final int TC_CURRENT_REGISTER = 0x0003;
	private static final int TC_RANGE_REGISTER = 0x000e;
	private static final int TC_CALIBRATION_REGISTER = 0x000f;
	private static final int TC_WRITE_RETRIES = 3;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Object lock = new Object();
	private volatile Mutable status = new Mutable();
	private volatile boolean stopRequested;
	private volatile SerialConnection connection;

	@Reference private BmsStringLifecycle strings;
	@Reference private ComponentManager componentManager;

	@Deactivate
	private void deactivate() {
		this.stopRequested = true;
		this.close();
		this.executor.shutdownNow();
	}

	@Override public TaskStatus status() { synchronized (this.lock) { return this.status.snapshot(); } }
	@Override public TaskStatus scanIr(int stringIndex, int startId, int endId) throws OpenemsException {
		validateRange(startId, endId);
		return this.start("ir-scan", stringIndex, null, startId, endId);
	}
	@Override public TaskStatus activateIr(int stringIndex, int startId, int endId) throws OpenemsException {
		validateRange(startId, endId);
		return this.start("ir-activate", stringIndex, null, startId, endId);
	}
	@Override public TaskStatus tcTask(int stringIndex, String action, Integer value) throws OpenemsException {
		if (!List.of("tc-read-range", "tc-write-range", "tc-read-current", "tc-calibrate").contains(action)) throw new OpenemsException("Unsupported TC action");
		if ((action.equals("tc-write-range") || action.equals("tc-calibrate")) && value == null) throw new OpenemsException("A value is required");
		if (action.equals("tc-write-range") && (value < 0 || value > 0xffff)) throw new OpenemsException("TC range must be between 0 and 65535");
		if (action.equals("tc-calibrate") && (value < -32767 || value > 32767)) throw new OpenemsException("TC calibration must be between -32767 and 32767");
		return this.start(action, stringIndex, value, null, null);
	}
	@Override public TaskStatus stop() throws OpenemsException { this.stopRequested = true; this.close(); synchronized (this.lock) { this.status.log("Stop requested"); return this.status.snapshot(); } }
	@Override public String wifi(String action, String ssid, String bssid, String password) throws OpenemsException {
		if (!List.of("status", "scan", "connect").contains(action)) throw new OpenemsException("Unsupported Wi-Fi action");
		var args = new ArrayList<String>(); args.add("sudo"); args.add("-n"); args.add("/usr/local/sbin/maxiems-wifi"); args.add(action);
		if ("connect".equals(action)) { if (ssid == null || ssid.isBlank() || bssid == null || bssid.isBlank()) throw new OpenemsException("SSID and BSSID are required"); args.add(ssid); args.add(bssid); }
		try {
			var p = new ProcessBuilder(args).redirectErrorStream(true).start();
			try (var stdin = p.getOutputStream()) {
				if ("connect".equals(action)) {
					stdin.write((password == null ? "" : password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
			}
			var out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			if (p.waitFor() != 0) throw new OpenemsException(out.isBlank() ? "Wi-Fi helper failed" : out.trim());
			return out;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Wi-Fi request interrupted");
		} catch (java.io.IOException e) {
			throw new OpenemsException("Wi-Fi helper failed: " + e.getMessage());
		}
	}
	@Override public String gpio(String action, String settingsJson) throws OpenemsException {
		if (!List.of("status", "save").contains(action)) throw new OpenemsException("Unsupported GPIO action");
		try { var p = new ProcessBuilder("sudo", "-n", "/usr/local/sbin/maxiems-gpio", action).redirectErrorStream(true).start(); if (settingsJson != null) { p.getOutputStream().write(settingsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)); p.getOutputStream().close(); } var out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); if (p.waitFor() != 0) throw new OpenemsException(out.isBlank() ? "GPIO helper failed" : out.trim()); return out; } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OpenemsException("GPIO request interrupted"); } catch (java.io.IOException e) { throw new OpenemsException("GPIO helper failed: " + e.getMessage()); }
	}

	private TaskStatus start(String type, int stringIndex, Integer value, Integer startId, Integer endId) throws OpenemsException {
		synchronized (this.lock) {
			if (this.status.running) throw new OpenemsException("Maintenance task already running");
			io.openems.edge.bms.service.strings.model.BmsStringModels.Definition definition;
			try {
				definition = this.strings.get(stringIndex);
			} catch (Exception e) {
				throw new OpenemsException("Unable to resolve BMS string " + stringIndex + ": " + e.getMessage());
			}
			this.status = new Mutable(type, stringIndex, definition.bridgeId(), startId, endId); this.stopRequested = false;
			this.executor.submit(() -> this.run(type, definition, value, startId, endId));
			return this.status.snapshot();
		}
	}

	private void run(String type, io.openems.edge.bms.service.strings.model.BmsStringModels.Definition definition, Integer value,
			Integer startId, Integer endId) {
		BridgeModbusSerial bridge = null; StartStop previous = StartStop.START;
		String state = "completed";
		String error = null;
		try {
			bridge = this.componentManager.getComponent(definition.bridgeId());
			previous = bridge.getStartStop();
			bridge.setStartStop(StartStop.STOP);
			this.status.serialPort = bridge.getPortName(); this.status.log("Polling paused on bridge " + definition.bridgeId());
			this.connection = open(bridge); this.status.log("Opened exclusive serial connection");
			if (type.equals("ir-scan")) scan(startId, endId, false);
			else if (type.equals("ir-activate")) scan(startId, endId, true);
			else tc(type, value);
			state = this.stopRequested ? "stopped" : "completed";
		} catch (Exception e) {
			state = this.stopRequested ? "stopped" : "failed";
			error = e.getMessage();
		} finally {
			close();
			if (bridge != null) try {
				bridge.setStartStop(previous == StartStop.STOP ? StartStop.STOP : StartStop.START);
				this.status.log("Bridge runtime state restored");
			} catch (Exception e) {
				this.status.log("Bridge restore failed: " + e.getMessage());
				state = "failed";
				error = "Bridge restore failed: " + e.getMessage();
			}
			// Do not admit the next task until this task has released the port and restored polling.
			finish(state, error);
		}
	}

	private static SerialConnection open(BridgeModbusSerial b) throws Exception {
		var p = new SerialParameters(); p.setPortName(b.getPortName()); p.setEncoding(Modbus.SERIAL_ENCODING_RTU); p.setBaudRate(b.getBaudrate()); p.setDatabits(b.getDatabits()); p.setStopbits(b.getStopbits().getValue()); p.setParity(b.getParity().getValue()); p.setEcho(false);
		var c = new SerialConnection(p); c.open(); c.setTimeout(1000); return c;
	}
	private void scan(int startId, int endId, boolean activate) {
		for (int id=startId; id<=endId && !this.stopRequested; id++) {
			this.status.currentId=id; this.status.nextId=id;
			Integer ir;
			try {
				// Keep the normal TA register sequence: request three registers starting
				// at register 1; the IR value is the third item in the response.
				ir = read(id, 1, 3)[2];
			} catch (Exception e) {
				this.status.missing.add(id);
				this.status.log("TA " + id + " did not respond: " + e.getMessage());
				continue;
			}
			this.status.active.add(id);
			this.status.log("TA " + id + " IR: " + ir);
			if (ir > 0) { this.status.activated.add(id); this.status.log("TA " + id + " already activated; skipped"); continue; }
			this.status.notActivated.add(id); if (!activate) continue;
			boolean ok=false; for (int attempt=1; attempt<=3 && !this.stopRequested; attempt++) try { write(id,0x000c,0xf0f0); ok=true; break; } catch (Exception e) { this.status.log("TA " + id + " activation write " + attempt + " failed: " + e.getMessage()); sleep(500); }
			if (!ok) { this.status.log("TA " + id + " write unconfirmed; polling"); }
			long deadline=System.currentTimeMillis()+30_000; boolean activated=false; while (!this.stopRequested && System.currentTimeMillis()<deadline) { try { int polledIr=read(id, 1, 3)[2]; this.status.log("TA " + id + " IR poll: " + polledIr); if (polledIr > 0) { this.status.activated.add(id); this.status.log("TA " + id + " activated"); activated=true; break; } } catch (Exception ignored) {} sleep(1000); }
			if (!activated && !this.stopRequested) this.status.log("TA " + id + " activation timeout after 30 seconds");
			this.status.nextId=id+1; if (id<endId) sleep(20_000);
		}
		for (int id=startId; id<=endId; id++) if (!this.status.active.contains(id) && !this.status.missing.contains(id)) this.status.missing.add(id);
	}
	private static void validateRange(int startId, int endId) throws OpenemsException {
		if (startId < 1 || startId > 247 || endId < 1 || endId > 247) {
			throw new OpenemsException("IR Modbus unit IDs must be between 1 and 247");
		}
		if (startId > endId) throw new OpenemsException("IR startId must be less than or equal to endId");
	}
	private void tc(String type, Integer value) throws Exception {
		if (type.equals("tc-read-range")) {
			readTcRange();
			return;
		}
		if (type.equals("tc-write-range")) {
			writeAndVerifyTcRange(value);
			return;
		}
		if (type.equals("tc-calibrate")) {
			int moduleValue = read(TC_ID, TC_MODULE_CHECK_REGISTER, 1)[0];
			this.status.log("TC 206 module check 0x0000 = " + decimalAndHex(moduleValue));
			try {
				readTcRange();
			} catch (Exception e) {
				this.status.log("TC 206 range read before calibration failed: " + e.getMessage() + "; continuing");
			}
			int raw = value < 0 ? 0x8000 | Math.abs(value) : value;
			this.status.calibration = value;
			this.status.calibrationRaw = raw;
			writeAndVerifyTcCalibration(raw);
		}
		readTcCurrentSamples();
	}
	private void readTcRange() throws Exception {
		int range = read(TC_ID, TC_RANGE_REGISTER, 1)[0];
		this.status.range = range;
		this.status.log("TC 206 range 0x000E = " + decimalAndHex(range));
	}
	private void writeAndVerifyTcRange(int value) throws Exception {
		this.status.log("TC 206 requested range " + decimalAndHex(value) + " to 0x000E");
		writeAndVerifyTcRegister(TC_RANGE_REGISTER, value, "range");
		this.status.range = value;
	}
	private void writeAndVerifyTcCalibration(int raw) throws Exception {
		this.status.log("TC 206 requested calibration " + this.status.calibration + ", raw " + decimalAndHex(raw) + " to 0x000F");
		writeAndVerifyTcRegister(TC_CALIBRATION_REGISTER, raw, "calibration");
	}
	private void writeAndVerifyTcRegister(int register, int value, String label) throws Exception {
		Exception lastFailure = null;
		for (int attempt = 1; attempt <= TC_WRITE_RETRIES && !this.stopRequested; attempt++) {
			try {
				write(TC_ID, register, value);
				this.status.log("TC 206 " + label + " write attempt " + attempt + "/3 confirmed");
			} catch (Exception e) {
				lastFailure = e;
				this.status.log("TC 206 " + label + " write attempt " + attempt + "/3 failed: " + e.getMessage());
			}
			sleep(200);
			try {
				int readback = read(TC_ID, register, 1)[0];
				this.status.log("TC 206 " + label + " readback attempt " + attempt + "/3 = " + decimalAndHex(readback));
				if (readback == value) {
					this.status.log("TC 206 " + label + " verified");
					return;
				}
				lastFailure = new Exception("TC " + label + " readback did not match requested value");
			} catch (Exception e) {
				lastFailure = e;
				this.status.log("TC 206 " + label + " readback attempt " + attempt + "/3 failed: " + e.getMessage());
			}
			if (attempt < TC_WRITE_RETRIES) sleep(1000);
		}
		if (this.stopRequested) return;
		throw new Exception("TC " + label + " verification failed after 3 attempts", lastFailure);
	}
	private void readTcCurrentSamples() {
		this.status.log("TC 206 sampling current 0x0003 every second for 20 seconds");
		for (int sample = 1; sample <= 20 && !this.stopRequested; sample++) {
			try {
				int raw = read(TC_ID, TC_CURRENT_REGISTER, 1)[0];
				int signed = (raw & 0x8000) != 0 ? -(raw & 0x7fff) : raw;
				if (this.status.firstCurrent == null) this.status.firstCurrent = signed;
				else if (!this.status.firstCurrent.equals(signed)) this.status.changed = true;
				this.status.latestCurrentRaw = raw;
				this.status.latestCurrentSigned = signed;
				this.status.latestCurrent = signed / 1000.0;
				this.status.samples = sample;
				this.status.successful++;
				this.status.log("TC 206 current register 0x0003 sample " + sample + "/20: raw "
						+ decimalAndHex(raw) + ", decoded " + signed + " = " + this.status.latestCurrent + " A");
			} catch (Exception e) {
				this.status.log("TC 206 current register 0x0003 sample " + sample + "/20 failed: " + e.getMessage());
			}
			if (sample < 20) sleep(1000);
		}
	}
	private static String decimalAndHex(int value) { return value + " (0x" + String.format("%04X", value & 0xffff) + ")"; }
	private int[] read(int id,int start,int count) throws Exception { var r=new ReadMultipleRegistersRequest(start,count); r.setUnitID(id); r.setHeadless(); var t=new ModbusSerialTransaction(this.connection); t.setRequest(r); t.execute(); var x=(ReadMultipleRegistersResponse)t.getResponse(); int[] out=new int[count]; for(int i=0;i<count;i++) out[i]=x.getRegisterValue(i)&0xffff; return out; }
	private void write(int id,int register,int value) throws Exception { var r=new WriteSingleRegisterRequest(register,new SimpleRegister(value)); r.setUnitID(id); r.setHeadless(); var t=new ModbusSerialTransaction(this.connection); t.setRequest(r); t.execute(); }
	private void close() { var c=this.connection; if(c!=null) try {c.close();} catch(Exception ignored) {} finally {this.connection=null;} }
	private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); this.stopRequested=true; } }
	private void finish(String state,String error) { synchronized(this.lock) { this.status.running=false; this.status.state=state; this.status.error=error; } }
	private static class Mutable { volatile String type="idle",state="idle",bridgeId,serialPort,error; volatile Integer stringIndex,startId,endId,currentId,nextId,range,calibration,calibrationRaw,firstCurrent,latestCurrentRaw,latestCurrentSigned,samples=0,successful=0; volatile Double latestCurrent; volatile boolean running,changed; List<Integer> active=new CopyOnWriteArrayList<>(),missing=new CopyOnWriteArrayList<>(),activated=new CopyOnWriteArrayList<>(),notActivated=new CopyOnWriteArrayList<>(); List<String> output=new CopyOnWriteArrayList<>(); Mutable(){} Mutable(String t,int i,String b,Integer start,Integer end){type=t;stringIndex=i;bridgeId=b;startId=start;endId=end;running=true;state="running";} void log(String s){output.add(Instant.now()+" "+s);if(output.size()>200)output.remove(0);} TaskStatus snapshot(){return new TaskStatus(type,state,stringIndex,bridgeId,serialPort,startId,endId,running,currentId,nextId,List.copyOf(active),List.copyOf(missing),List.copyOf(activated),List.copyOf(notActivated),range,calibration,calibrationRaw,firstCurrent,latestCurrentRaw,latestCurrentSigned,latestCurrent,samples,successful,changed,error,List.copyOf(output));} }
}
