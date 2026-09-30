package io.openems.edge.bms.controllers.modbus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.msg.ReadInputRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadInputRegistersResponse;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.test.TestUtils;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;

public class BmsModbusProcessImageTest {

	@Test
	public void shouldTranslateWorkbookAddressesAndScaleValues() throws Exception {
		var image = new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame()));
		assertEquals(1450, image.getInputRegister(1000).getValue());
		assertEquals(2801, image.getInputRegister(1300).getValue());
		assertEquals(253, image.getInputRegister(1600).getValue());
		assertEquals(55, image.getInputRegister(1900).getValue());
		assertEquals(91, image.getInputRegister(2200).getValue());
		assertEquals(0xffe2, image.getInputRegister(3000).getValue()); // -3.0 A
		assertEquals(731, image.getInputRegister(3200).getValue());
		assertEquals(11234, image.getInputRegister(3400).getValue());
	}

	@Test
	public void shouldCoverCell110AndStringStride() throws Exception {
		var image = new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame(), 2, monitoringFrame()));
		assertEquals(BmsModbusRegisterMapper.UNAVAILABLE, image.getInputRegister(1109).getValue());
		assertEquals(1450, image.getInputRegister(11_000).getValue());
	}

	@Test
	public void shouldUseSentinelAndBinaryAlarm() throws Exception {
		var base = snapshot();
		var cells = new LinkedHashMap<>(base.cells());
		cells.put(1, new BmsCalculationSnapshot.Cell(1, null, null, null, null, null, null, "missing"));
		var warning = copy(base, cells, "tc communication failed");
		var image = new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame(warning)));
		assertEquals(BmsModbusRegisterMapper.UNAVAILABLE, image.getInputRegister(1300).getValue());
		assertEquals(1, image.getInputRegister(5000).getValue());
	}

	@Test
	public void shouldReserveSentinelForUnavailableValues() throws Exception {
		var base = snapshot();
		var negativeTenthAmp = new BmsCalculationSnapshot(base.generation(), base.sequence(), base.capturedAt(),
				base.configuredCellCount(), base.ambientTemperature(), -0.1, base.currentMeasuredAt(),
				base.currentQuality(), base.currentWarning(), base.stringSoc(), base.stringSoh(), base.stringVoltage(),
				base.averageVoltage(), base.averageTemperature(), base.averageResistance(), base.maxVoltage(),
				base.maxVoltageCellIndex(), base.minVoltage(), base.minVoltageCellIndex(), base.maxTemperature(),
				base.maxTemperatureCellIndex(), base.minTemperature(), base.minTemperatureCellIndex(), base.maxResistance(),
				base.maxResistanceCellIndex(), base.minResistance(), base.minResistanceCellIndex(), base.cells(),
				base.stringWarning());
		var image = new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame(negativeTenthAmp)));

		assertEquals(BmsModbusRegisterMapper.UNAVAILABLE, image.getInputRegister(3000).getValue());
	}

	@Test
	public void shouldServeFc4UsingTheConfiguredUnitId() throws Exception {
		var port = TestUtils.findRandomOpenPortOnAllLocalInterfaces();
		ModbusSlave slave = null;
		TCPMasterConnection connection = null;
		try {
			slave = ModbusSlaveFactory.createTCPSlave(port, 1);
			slave.addProcessImage(7, new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame())));
			slave.open();

			connection = new TCPMasterConnection(InetAddress.getLoopbackAddress());
			connection.setPort(port);
			connection.connect();
			var request = new ReadInputRegistersRequest(1300, 1);
			request.setUnitID(7);
			var transaction = new ModbusTCPTransaction(connection);
			transaction.setRequest(request);
			transaction.execute();
			var response = (ReadInputRegistersResponse) transaction.getResponse();

			assertEquals(2801, response.getRegisters()[0].getValue());

			request = new ReadInputRegistersRequest(6100, 2);
			request.setUnitID(7);
			transaction = new ModbusTCPTransaction(connection);
			transaction.setRequest(request);
			transaction.execute();
			response = (ReadInputRegistersResponse) transaction.getResponse();
			assertEquals(25_325,
					(response.getRegisters()[0].getValue() << 16) | response.getRegisters()[1].getValue());
		} finally {
			if (connection != null) {
				connection.close();
			}
			if (slave != null) {
				slave.close();
			}
		}
	}

	@Test
	public void shouldRejectUnmappedAndWritableRegisters() {
		var image = new BmsModbusProcessImage(() -> Map.of(1, monitoringFrame()));
		assertThrows(IllegalAddressException.class, () -> image.getInputRegister(0));
		assertThrows(IllegalAddressException.class, () -> image.getInputRegisterRange(1000, 126));
		assertThrows(IllegalAddressException.class, () -> image.getRegister(1000));
	}

	@Test
	public void shouldServeOneAtomicMonitoringFrameAndKeepLegacyMap() throws Exception {
		var frame = monitoringFrame();
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		var image = new BmsModbusProcessImage(() -> {
			reads.incrementAndGet();
			return Map.of(1, frame);
		});
		var identity = image.getInputRegisterRange(6018, 4);
		assertEquals(77, identity[3].getValue());
		var voltage = image.getInputRegisterRange(6100, 2);
		assertEquals(25_325, (voltage[0].getValue() << 16) | voltage[1].getValue());
		assertEquals(2801, image.getInputRegister(1300).getValue());
		assertEquals(3, reads.get()); // One capture per request, not per register.
		assertEquals(0xffe2, image.getInputRegister(3000).getValue()); // Raw -3 A, not domain -15.75.
		assertEquals(925, image.getInputRegister(3300).getValue()); // Legacy SoH, not monitoring average.
	}

	@Test
	public void shouldExposeMonitoringSentinelsButRejectMissingProvider() throws Exception {
		var configured = new BmsModbusProcessImage(() -> java.util.Collections.singletonMap(1, null));
		assertEquals(1, configured.getInputRegister(6000).getValue());
		assertEquals(0, configured.getInputRegister(6001).getValue());
		assertEquals(0x8000, configured.getInputRegister(6100).getValue());
		assertEquals(0xffff, configured.getInputRegister(1300).getValue());
		var missing = new BmsModbusProcessImage(Map::of);
		assertThrows(IllegalAddressException.class, () -> missing.getInputRegister(6000));
	}

	@Test
	public void shouldRejectAmbiguousOrCrossStringMonitoringProviders() {
		var controller = new ControllerBmsModbusTcpImpl();
		var first = monitoringProvider("str1", monitoringFrame());
		var second = monitoringProvider("str1", monitoringFrame());
		controller.addMonitoringProvider(first);
		controller.addMonitoringProvider(second);
		assertEquals(Map.of(), controller.monitoringFrames());

		controller.removeMonitoringProvider(second);
		assertEquals(Set.of(1), controller.monitoringFrames().keySet());
		controller.removeMonitoringProvider(first);
		controller.addMonitoringProvider(monitoringProvider("str1", copyWithStringKey(monitoringFrame(), "str2")));
		assertEquals(Map.of(), controller.monitoringFrames());
	}

	private static BmsCalculationSnapshot snapshot() {
		var cells = new LinkedHashMap<Integer, BmsCalculationSnapshot.Cell>();
		cells.put(1, new BmsCalculationSnapshot.Cell(1, 2.801, 25.3, 1450, 55.0, 91.0, null, null));
		return new BmsCalculationSnapshot(UUID.randomUUID(), 1, 1, 110, 22.1, -3.0, 1L,
				BmsCalculationSnapshot.CurrentQuality.FRESH, null, 73.1, 92.5, 11.234, 2.801, 25.3, 1450.0,
				2.801, 1, 2.7, 2, 25.3, 1, 20.0, 2, 1450, 1, 1200, 2, cells, null);
	}

	private static BmsMonitoringSnapshot monitoringFrame() {
		return monitoringFrame(snapshot());
	}

	private static BmsMonitoringSnapshot monitoringFrame(BmsCalculationSnapshot calculation) {
		var generation = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000");
		var at = java.time.Instant.ofEpochMilli(1_789_000_000_123L);
		var values = new BmsMonitoringSnapshot.StringMeasurements(253.25, 126.6, -15.75, -3989.0, 0.15,
				2.291, 2.309, 2.302, 1901, 1800.4, 25.1, 26.0, 25.4, 99.95, 10.25, 87.56, 80.01);
		return new BmsMonitoringSnapshot("str1", generation, 77, at, at.toEpochMilli(),
				BmsCalculationSnapshot.CurrentQuality.FRESH, false,
				new BmsMonitoringSnapshot.ReferenceIdentity(42, 2, 100, 1, 200, 201, "curve-hash"), values,
				Map.of(), new BmsMonitoringSnapshot.AppliedThresholds(0.15, 0.15, 200, 242, 1.25, 4, 6, 3,
						0.3, 40, 70, 40, 2), ActiveCalibrationContext.none(),
				ModeEvidence.unknown(generation, 77, at, "test"), BmsCurrentDirectionMapping.NEGATIVE,
				Set.of(), 0, java.util.List.of(), null, calculation, generation, 1, Map.of());
	}

	private static BmsMonitoringSnapshot copyWithStringKey(BmsMonitoringSnapshot frame, String stringKey) {
		return new BmsMonitoringSnapshot(stringKey, frame.snapshotGeneration(), frame.snapshotSequence(),
				frame.capturedAt(), frame.currentMeasuredAt(), frame.currentQuality(), frame.stale(), frame.reference(),
				frame.string(), frame.cells(), frame.thresholds(), frame.activeCalibration(), frame.operatingMode(),
				frame.currentDirectionMapping(), frame.activeAlarms(), frame.alarmMask(), frame.alarmDetails(),
				frame.qualityWarning());
	}

	private static BmsMonitoringSnapshotProvider monitoringProvider(String stringKey, BmsMonitoringSnapshot frame) {
		return (BmsMonitoringSnapshotProvider) Proxy.newProxyInstance(BmsMonitoringSnapshotProvider.class.getClassLoader(),
				new Class<?>[] { BmsMonitoringSnapshotProvider.class }, (proxy, method, arguments) -> switch (method.getName()) {
				case "stringKey" -> stringKey;
				case "latestFrame" -> frame;
				case "id" -> "test-" + stringKey;
				case "alias" -> "test";
				case "isEnabled" -> true;
				case "equals" -> proxy == arguments[0];
				case "hashCode" -> System.identityHashCode(proxy);
				case "toString" -> "test-monitoring-provider-" + stringKey;
				default -> null;
				});
	}

	private static BmsCalculationSnapshot copy(BmsCalculationSnapshot value, Map<Integer, BmsCalculationSnapshot.Cell> cells,
			String warning) {
		return new BmsCalculationSnapshot(value.generation(), value.sequence(), value.capturedAt(),
				value.configuredCellCount(), value.ambientTemperature(), value.stringCurrent(), value.currentMeasuredAt(),
				value.currentQuality(), value.currentWarning(), value.stringSoc(), value.stringSoh(), value.stringVoltage(),
				value.averageVoltage(), value.averageTemperature(), value.averageResistance(), value.maxVoltage(),
				value.maxVoltageCellIndex(), value.minVoltage(), value.minVoltageCellIndex(), value.maxTemperature(),
				value.maxTemperatureCellIndex(), value.minTemperature(), value.minTemperatureCellIndex(), value.maxResistance(),
				value.maxResistanceCellIndex(), value.minResistance(), value.minResistanceCellIndex(), cells, warning);
	}
}
