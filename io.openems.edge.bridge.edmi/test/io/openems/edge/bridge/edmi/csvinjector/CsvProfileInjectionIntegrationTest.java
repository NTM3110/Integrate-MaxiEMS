package io.openems.edge.bridge.edmi.csvinjector;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.bridge.edmi.ProfileIngestionSettings;
import io.openems.shared.influxdb.InfluxConnector;
import io.openems.shared.influxdb.QueryLanguageConfig;

/**
 * Integration test for writing CSV profile data to the controller input measurement.
 */
public class CsvProfileInjectionIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(CsvProfileInjectionIntegrationTest.class);

	private Path tempCsvPath;
	private InfluxConnector influxConnector;

	@Before
	public void setUp() throws Exception {
		String influxUrl = System.getenv().getOrDefault("EDMI_INFLUX_URL", "http://localhost:8086");
		String influxToken = System.getenv().getOrDefault("EDMI_INFLUX_TOKEN", "");
		String org = System.getenv().getOrDefault("EDMI_INFLUX_ORG", "AT");
		String bucket = System.getenv().getOrDefault("EDMI_INFLUX_BUCKET", "demo");

		try {
			this.influxConnector = new InfluxConnector("test-csv-injector", QueryLanguageConfig.FLUX,
					URI.create(influxUrl), org, influxToken, bucket, "test", false, 5, 1000,
					e -> log.error("Write error: {}", e.getMessage()), false);
			this.influxConnector.writeSync(Point.measurement("test_connection").addTag("test", "true")
					.addField("value", 1).time(Instant.now(), WritePrecision.MS));
		} catch (Exception e) {
			Assume.assumeTrue("InfluxDB not available at " + influxUrl + ": " + e.getMessage(), false);
			return;
		}

		this.tempCsvPath = Files.createTempFile("test_edmi_profile_", ".csv");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(this.tempCsvPath.toFile()))) {
			writer.write("meter_id,timestamp,record_status,total_energy_tot_imp_wh,total_energy_tot_exp_wh,total_energy_tot_imp_va,total_energy_tot_exp_va");
			writer.newLine();
			writeRow(writer, "GRID_001", "2025-01-01 00:00:00", 1, 1000.0, 500.0, 1100.0, 550.0);
			writeRow(writer, "SELF_001", "2025-01-01 00:00:00", 1, 800.0, 200.0, 880.0, 220.0);
			writeRow(writer, "BESS_001", "2025-01-01 00:00:00", 1, 100.0, 300.0, 110.0, 330.0);
			writeRow(writer, "SOLAR_001", "2025-01-01 00:00:00", 1, 50.0, 400.0, 55.0, 440.0);
			writeRow(writer, "DEST_001", "2025-01-01 00:00:00", 1, 600.0, 100.0, 660.0, 110.0);
			writeRow(writer, "GRID_001", "2025-01-01 00:30:00", 1, 1200.0, 600.0, 1320.0, 660.0);
			writeRow(writer, "SELF_001", "2025-01-01 00:30:00", 1, 950.0, 250.0, 1045.0, 275.0);
			writeRow(writer, "BESS_001", "2025-01-01 00:30:00", 1, 150.0, 450.0, 165.0, 495.0);
			writeRow(writer, "SOLAR_001", "2025-01-01 00:30:00", 1, 80.0, 550.0, 88.0, 605.0);
			writeRow(writer, "DEST_001", "2025-01-01 00:30:00", 1, 750.0, 150.0, 825.0, 165.0);
		}
	}

	private static void writeRow(BufferedWriter writer, String meterId, String timestamp, int recordStatus,
			double impWh, double expWh, double impVa, double expVa) throws Exception {
		writer.write(String.format("%s,%s,%d,%.1f,%.1f,%.1f,%.1f", meterId, timestamp, recordStatus, impWh, expWh,
				impVa, expVa));
		writer.newLine();
	}

	@Test
	public void testCsvInjectionWritesControllerInputProfiles() throws Exception {
		Assume.assumeTrue("InfluxDB not available", this.influxConnector != null);

		var bridge = new RealEdmiBridge(this.influxConnector);
		var options = new CsvProfileInjectionService.Options(this.tempCsvPath, 0, 0, 0, () -> false);
		var result = CsvProfileInjectionService.inject(bridge, options, log);

		assert result.intervalCount() == 2 : "Expected 2 intervals, got " + result.intervalCount();
		assert result.rowCount() == 10 : "Expected 10 rows, got " + result.rowCount();
		assert bridge.registeredMeters.size() == 10 : "Expected 10 registered rows, got " + bridge.registeredMeters.size();

		Files.deleteIfExists(this.tempCsvPath);
	}

	private static class RealEdmiBridge implements EdmiBridge {
		private final InfluxConnector connector;
		private final List<String> registeredMeters = new ArrayList<>();

		private RealEdmiBridge(InfluxConnector connector) {
			this.connector = connector;
		}

		@Override public void registerEnergyMeter(String meterId, String role, String sourceType) {
			this.registeredMeters.add(meterId + "=" + role + "=" + sourceType);
		}
		@Override public void writeToInflux(Point point) { this.connector.writeSync(point); }
		@Override public void writeToInfluxSync(Point point) { this.connector.writeSync(point); }
		@Override public ProfileIngestionSettings getProfileIngestionSettings() {
			return new ProfileIngestionSettings((short) 0x0305, 30, 5, 5, 30, 5, List.of("timestamp"),
					List.of("record_status"), List.of("total_energy_tot_imp_wh"), List.of("total_energy_tot_exp_wh"),
					List.of("total_energy_tot_imp_va"), List.of("total_energy_tot_exp_va"));
		}
		@Override public void addTask(io.openems.edge.bridge.edmi.api.EdmiTask task) {}
		@Override public void removeTask(io.openems.edge.bridge.edmi.api.EdmiTask task) {}
		@Override public void unregisterEnergyMeter(String meterId) {}
		@Override public Object readProfile(com.atdigital.imr.EdmiDateTime.ByValue from,
				com.atdigital.imr.EdmiDateTime.ByValue to, int serialNumber, String username, String password,
				short survey) throws Exception { return null; }
		@Override public Object readProfileImmediately(com.atdigital.imr.EdmiDateTime.ByValue from,
				com.atdigital.imr.EdmiDateTime.ByValue to, int serialNumber, String username, String password,
				short survey) throws Exception { return null; }
		@Override public List<Object> readBillingValues(String username, String password, int serialNumber) throws Exception { return null; }
		@Override public com.google.gson.JsonArray queryBillingValuesFromInflux(String meterId, Instant start,
				Instant end, List<String> fields) throws Exception { return new com.google.gson.JsonArray(); }
		@Override public com.google.gson.JsonArray queryProfileValuesFromInflux(String meterId, Instant start,
				Instant end, List<String> fields) throws Exception { return new com.google.gson.JsonArray(); }
		@Override public String id() { return "realBridge"; }
		@Override public String alias() { return "Real Bridge"; }
		@Override public boolean isEnabled() { return true; }
		@Override public io.openems.edge.common.channel.Channel<?> channel(String channelId) { return null; }
		@Override public io.openems.edge.common.channel.Channel<?> channel(
				io.openems.edge.common.channel.ChannelId channelId) { return null; }
		@Override public java.util.Collection<io.openems.edge.common.channel.Channel<?>> channels() { return java.util.List.of(); }
		@Override public org.osgi.service.component.ComponentContext getComponentContext() { return null; }
		@Override public io.openems.edge.common.channel.Channel<?> _channel(String channelName) { return null; }
	}
}