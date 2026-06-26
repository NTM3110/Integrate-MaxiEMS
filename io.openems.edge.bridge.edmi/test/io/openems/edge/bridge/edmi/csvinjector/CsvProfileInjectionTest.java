package io.openems.edge.bridge.edmi.csvinjector;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.bridge.edmi.ProfileIngestionSettings;

/**
 * Unit test for CSV Profile Injection.
 * 
 * This test verifies that CSV profile rows are written to the EDMI profile measurement for the controller pipeline.
 * It does NOT require a running InfluxDB instance.
 * 
 * To test with real InfluxDB:
 * 1. Start InfluxDB container
 * 2. Configure CsvProfileInjector OSGi component with your CSV path
 * 3. Enable the component in OpenEMS Edge
 * 4. Check results in InfluxDB
 */
public class CsvProfileInjectionTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private Path tempCsvPath;
    private MockEdmiBridge mockBridge;
    
    @Before
    public void setUp() throws Exception {
        // Create temporary CSV file with test data for 3 intervals
        tempCsvPath = Files.createTempFile("test_edmi_profile_", ".csv");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempCsvPath.toFile()))) {
            // Write header
            writer.write("meter_id,timestamp,record_status,total_energy_tot_imp_wh,total_energy_tot_exp_wh,total_energy_tot_imp_va,total_energy_tot_exp_va");
            writer.newLine();
            
            // Interval 1: 2025-01-01 00:00:00 - baseline (no calculation)
            writeRow(writer, "GRID_001", "2025-01-01 00:00:00", 1, 1000.0, 500.0, 1100.0, 550.0);
            writeRow(writer, "SELF_001", "2025-01-01 00:00:00", 1, 800.0, 200.0, 880.0, 220.0);
            writeRow(writer, "BESS_001", "2025-01-01 00:00:00", 1, 100.0, 300.0, 110.0, 330.0);
            writeRow(writer, "SOLAR_001", "2025-01-01 00:00:00", 1, 50.0, 400.0, 55.0, 440.0);
            writeRow(writer, "DEST_001", "2025-01-01 00:00:00", 1, 600.0, 100.0, 660.0, 110.0);
            
            // Interval 2: 2025-01-01 00:30:00 - first calculation
            // Grid: imp=1200, exp=600 -> delta: imp=200, exp=100
            // Self: imp=950, exp=250 -> delta: imp=150, exp=50
            // BESS: imp=150, exp=450 -> delta: imp=50, exp=150
            // Solar: imp=80, exp=550 -> delta: imp=30, exp=150
            // DEST: imp=750, exp=150 -> delta: imp=150, exp=50
            writeRow(writer, "GRID_001", "2025-01-01 00:30:00", 1, 1200.0, 600.0, 1320.0, 660.0);
            writeRow(writer, "SELF_001", "2025-01-01 00:30:00", 1, 950.0, 250.0, 1045.0, 275.0);
            writeRow(writer, "BESS_001", "2025-01-01 00:30:00", 1, 150.0, 450.0, 165.0, 495.0);
            writeRow(writer, "SOLAR_001", "2025-01-01 00:30:00", 1, 80.0, 550.0, 88.0, 605.0);
            writeRow(writer, "DEST_001", "2025-01-01 00:30:00", 1, 750.0, 150.0, 825.0, 165.0);
            
            // Interval 3: 2025-01-01 01:00:00 - second calculation
            writeRow(writer, "GRID_001", "2025-01-01 01:00:00", 1, 1400.0, 700.0, 1540.0, 770.0);
            writeRow(writer, "SELF_001", "2025-01-01 01:00:00", 1, 1100.0, 300.0, 1210.0, 330.0);
            writeRow(writer, "BESS_001", "2025-01-01 01:00:00", 1, 200.0, 600.0, 220.0, 660.0);
            writeRow(writer, "SOLAR_001", "2025-01-01 01:00:00", 1, 110.0, 700.0, 121.0, 770.0);
            writeRow(writer, "DEST_001", "2025-01-01 01:00:00", 1, 900.0, 200.0, 990.0, 220.0);
        }
        
        mockBridge = new MockEdmiBridge();
    }
    
    private void writeRow(BufferedWriter writer, String meterId, String timestamp, int recordStatus,
            double impWh, double expWh, double impVa, double expVa) throws Exception {
        writer.write(String.format("%s,%s,%d,%.1f,%.1f,%.1f,%.1f", 
                meterId, timestamp, recordStatus, impWh, expWh, impVa, expVa));
        writer.newLine();
    }
    
    @Test
    public void testCsvInjectionAndCalculation() throws Exception {
        // Parse CSV file
        var rowsByTimestamp = CsvProfileInjectionService.readCsvFile(tempCsvPath, 0, null);
        
        System.out.println("Found " + rowsByTimestamp.size() + " intervals in CSV");
        
        // Inject data
        var options = new CsvProfileInjectionService.Options(
                tempCsvPath, 100, 50, 0, () -> false);
        
        var result = CsvProfileInjectionService.inject(mockBridge, options, null);
        
        System.out.println("Injection result: " + result);
        System.out.println("Registered meters: " + mockBridge.registeredMeters);
        System.out.println("Written points: " + mockBridge.writtenPoints.size());
        System.out.println("Controller input points: " + mockBridge.writtenPoints.size());
        
        // Verify results
        assert result.intervalCount() == 3 : "Expected 3 intervals, got " + result.intervalCount();
        assert result.rowCount() == 15 : "Expected 15 rows, got " + result.rowCount();
        assert mockBridge.writtenPoints.size() == 15 : "Expected 15 points written, got " + mockBridge.writtenPoints.size();
        
        // Verify first timestamp is correct (should be 2025-01-01 00:00:00 UTC)
        Instant expectedFirst = LocalDateTime.parse("2025-01-01 00:00:00", FORMATTER)
                .toInstant(ZoneOffset.UTC);
        assert result.firstTimestamp().equals(expectedFirst) : 
            "Expected first timestamp " + expectedFirst + ", got " + result.firstTimestamp();
        
        // Verify last timestamp is correct (should be 2025-01-01 01:00:00 UTC)
        Instant expectedLast = LocalDateTime.parse("2025-01-01 01:00:00", FORMATTER)
                .toInstant(ZoneOffset.UTC);
        assert result.lastTimestamp().equals(expectedLast) : 
            "Expected last timestamp " + expectedLast + ", got " + result.lastTimestamp();
        
        // Cleanup
        Files.deleteIfExists(tempCsvPath);
    }
    
    @Test
    public void testRawFourColumnCsvFormat() throws Exception {
        // Create temporary CSV file in the raw 4-column demo format
        // (ts,meter_serial,import_kwh,export_kwh)
        var rawCsvPath = Files.createTempFile("test_edmi_raw_profile_", ".csv");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rawCsvPath.toFile()))) {
            writer.write("ts,meter_serial,import_kwh,export_kwh");
            writer.newLine();
            writer.write("2025-01-01 00:00:00,GRID_001,1000.0,500.0");
            writer.newLine();
            writer.write("2025-01-01 00:00:00,SELF_001,800.0,200.0");
            writer.newLine();
            writer.write("2025-01-01 00:30:00,GRID_001,1200.0,600.0");
            writer.newLine();
            writer.write("2025-01-01 00:30:00,SELF_001,950.0,250.0");
            writer.newLine();
        }
        
        var bridge = new MockEdmiBridge();
        var options = new CsvProfileInjectionService.Options(
                rawCsvPath, 0, 0, 0, () -> false);
        
        var result = CsvProfileInjectionService.inject(bridge, options, null);
        
        System.out.println("Raw CSV injection result: " + result);
        
        // Verify all four rows were parsed and written
        assert result.intervalCount() == 2 : "Expected 2 intervals, got " + result.intervalCount();
        assert result.rowCount() == 4 : "Expected 4 rows, got " + result.rowCount();
        assert bridge.writtenPoints.size() == 4 : "Expected 4 points written, got " + bridge.writtenPoints.size();
        
        // Verify defaults for missing columns
        var firstPoint = bridge.writtenPoints.get(0);
        assert firstPoint.toLineProtocol().contains("record_status=0") : 
            "Expected record_status default of 0 in " + firstPoint.toLineProtocol();
        assert firstPoint.toLineProtocol().contains("total_energy_tot_imp_va=0.0") : 
            "Expected total_energy_tot_imp_va default of 0.0 in " + firstPoint.toLineProtocol();
        assert firstPoint.toLineProtocol().contains("total_energy_tot_exp_va=0.0") : 
            "Expected total_energy_tot_exp_va default of 0.0 in " + firstPoint.toLineProtocol();
        
        // Cleanup
        Files.deleteIfExists(rawCsvPath);
    }
    
    /**
     * Mock implementation of EdmiBridge for testing
     */
    private static class MockEdmiBridge implements EdmiBridge {
        List<String> registeredMeters = new ArrayList<>();
        List<Point> writtenPoints = new ArrayList<>();
        
        @Override
        public void addTask(io.openems.edge.bridge.edmi.api.EdmiTask task) {}
        
        @Override
        public void removeTask(io.openems.edge.bridge.edmi.api.EdmiTask task) {}
        
        @Override
        public ProfileIngestionSettings getProfileIngestionSettings() {
            return new ProfileIngestionSettings(
                (short) 0x0305,  // survey
                30,              // intervalMinutes
                5,               // firstReadDelayMinutes
                5,               // retryMinutes
                30,              // finalizeAfterMinutes
                5,               // maxRecords
                List.of("timestamp"),           // timestampAliases
                List.of("record_status"),       // recordStatusAliases
                List.of("total_energy_tot_imp_wh"),  // importWhAliases
                List.of("total_energy_tot_exp_wh"),  // exportWhAliases
                List.of("total_energy_tot_imp_va"),  // importVahAliases
                List.of("total_energy_tot_exp_va")     // exportVahAliases
            );
        }
        
        @Override
        public void registerEnergyMeter(String meterId, String role, String sourceType) {
            registeredMeters.add(meterId + "=" + role + "=" + sourceType);
        }
        
        @Override
        public void unregisterEnergyMeter(String meterId) {}
        
        @Override
        public Object readProfile(com.atdigital.imr.EdmiDateTime.ByValue from, 
                com.atdigital.imr.EdmiDateTime.ByValue to, int serialNumber, 
                String username, String password, short survey) throws Exception {
            return null;
        }
        
        @Override
        public Object readProfileImmediately(com.atdigital.imr.EdmiDateTime.ByValue from, 
                com.atdigital.imr.EdmiDateTime.ByValue to, int serialNumber, 
                String username, String password, short survey) throws Exception {
            return null;
        }
        
        @Override
        public List<Object> readBillingValues(String username, String password, int serialNumber) throws Exception {
            return null;
        }
        
        @Override
        public void writeToInflux(Point point) {
            writtenPoints.add(point);
        }
        
        @Override
        public void writeToInfluxSync(Point point) {
            writtenPoints.add(point);
        }
        
        @Override
        public com.google.gson.JsonArray queryBillingValuesFromInflux(String meterId, Instant start, 
                Instant end, List<String> fields) throws Exception {
            return new com.google.gson.JsonArray();
        }
        
        @Override
        public com.google.gson.JsonArray queryProfileValuesFromInflux(String meterId, Instant start, 
                Instant end, List<String> fields) throws Exception {
            return new com.google.gson.JsonArray();
        }
        
        @Override
        public String id() { return "mockBridge"; }
        
        @Override
        public String alias() { return "Mock Bridge"; }
        
        @Override
        public boolean isEnabled() { return true; }
        
        @Override
        public io.openems.edge.common.channel.Channel<?> channel(String channelId) { return null; }
        
        @Override
        public io.openems.edge.common.channel.Channel<?> channel(
                io.openems.edge.common.channel.ChannelId channelId) { return null; }
        
        @Override
        public java.util.Collection<io.openems.edge.common.channel.Channel<?>> channels() { return java.util.List.of(); }
        
        @Override
        public org.osgi.service.component.ComponentContext getComponentContext() { return null; }
        
        @Override
        public io.openems.edge.common.channel.Channel<?> _channel(String channelName) { return null; }
    }
}
