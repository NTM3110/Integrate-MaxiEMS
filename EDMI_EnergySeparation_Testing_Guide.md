# EDMI Energy Separation Testing in OpenEMS - Implementation Guide

## Overview

This guide explains how to test the EDMI Energy Separation Calculation in OpenEMS using CSV demo data from the existing Python demo application (`E:\ATEnergy\Meter\EDMI_Demo_App\energy_app`).

## The Problem

You have:
- A Python demo app that reads CSV files and calculates energy separation
- An OpenEMS EDMI driver that expects real physical meters connected via serial port
- **No physical meters** to test with

## The Solution

Create a **CSV Profile Injector** that:
1. Reads the same CSV file used by the Python demo
2. Injects profile data directly into InfluxDB (bypassing the serial communication)
3. Triggers the existing `EdmiEnergySeparationCalculator` to process the data

## Demo CSV Data Format

The CSV file (`reformatted_demo_lp_2025_01_to_26.csv`) contains:

```csv
meter_id,time_stamp,record_status,total_energy_tot_imp_wh,total_energy_tot_exp_wh,total_energy_tot_imp_va,total_energy_tot_exp_va
BESS_01,2025-01-01 00:30:00,0,0.0,1.064,0,0
BESS_02,2025-01-01 00:30:00,0,0.0,1.187,0,0
...
```

### Meter Mapping (from demo app)

| Meter ID | Role | Source Type | Description |
|----------|------|-------------|-------------|
| BESS_01 | SOURCE | BESS | Battery 1 |
| BESS_02 | SOURCE | BESS | Battery 2 |
| BESS_03 | SOURCE | BESS | Battery 3 |
| BESS_04 | SOURCE | BESS | Battery 4 |
| SOLAR_01 | SOURCE | RTS | Solar 1 |
| SOLAR_02 | SOURCE | RTS | Solar 2 |
| SOLAR_03 | SOURCE | RTS | Solar 3 |
| SOLAR_04 | SOURCE | RTS | Solar 4 |
| SELF_01 | SELF_USE | - | Self-consumption |
| GRID_01 | GRID_POINT | - | Grid connection |
| DEST_01 | INTERCONNECT | - | Interconnect/Destination |

## Approach 1: CSV-to-InfluxDB Direct Injector (Recommended)

### Concept

Instead of creating fake meter components, directly inject the CSV data into InfluxDB in the exact format the `EdmiEnergySeparationCalculator` expects.

### Data Flow

```
CSV File
    ↓
CsvProfileInjector (new component)
    ↓
InfluxDB (edmi_profile measurement)
    ↓
EdmiEnergySeparationCalculator (existing)
    ↓
InfluxDB (edmi_separated_energy_interval)
```

### Implementation Steps

#### Step 1: Create the CsvProfileInjector Component

Create a new OpenEMS component that reads CSV and writes to InfluxDB:

```java
package io.openems.edge.bridge.edmi.demo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Demo component that reads EDMI profile data from CSV and injects it into
 * InfluxDB for energy separation testing.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Bridge.EDMI.CsvInjector", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class CsvProfileInjector extends AbstractOpenemsComponent implements OpenemsComponent {

    @Reference
    private EdmiBridge edmiBridge;

    private Path csvPath;
    private boolean enabled = false;
    private Thread injectorThread;
    private volatile boolean stopThread = false;

    public CsvProfileInjector() {
        super(OpenemsComponent.ChannelId.values());
    }

    @Activate
    void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.csvPath = Path.of(config.csvPath());
        this.enabled = config.enabled();

        if (this.enabled) {
            startInjection();
        }
    }

    @Deactivate
    protected void deactivate() {
        stopThread = true;
        if (injectorThread != null) {
            injectorThread.interrupt();
        }
        super.deactivate();
    }

    private void startInjection() {
        injectorThread = new Thread(this::runInjection, "csv-injector");
        injectorThread.start();
    }

    private void runInjection() {
        try {
            // Read CSV and inject into InfluxDB
            Map<Instant, Map<String, CsvRow>> rowsByTimestamp = readCsvFile();

            // Process each timestamp
            for (Map.Entry<Instant, Map<String, CsvRow>> entry : rowsByTimestamp.entrySet()) {
                if (stopThread) break;

                Instant timestamp = entry.getKey();
                Map<String, CsvRow> meterRows = entry.getValue();

                // Write each meter's data to InfluxDB
                for (CsvRow row : meterRows.values()) {
                    Point point = Point.measurement("edmi_profile")
                            .addTag("meter_id", row.meterId)
                            .addTag("meter_role", row.role)
                            .addTag("source_type", row.sourceType)
                            .time(timestamp, WritePrecision.MS)
                            .addField("record_status", row.recordStatus)
                            .addField("total_energy_tot_imp_wh", row.impWh)
                            .addField("total_energy_tot_exp_wh", row.expWh)
                            .addField("total_energy_tot_imp_va", row.impVa)
                            .addField("total_energy_tot_exp_va", row.expVa);

                    edmiBridge.writeToInflux(point);
                }

                // Register meters with the bridge (if not already registered)
                for (CsvRow row : meterRows.values()) {
                    edmiBridge.registerEnergyMeter(row.meterId, row.role, row.sourceType);
                }

                // Trigger energy separation calculation
                edmiBridge.processProfileTimestamp(timestamp);

                // Simulate real-time delay (optional)
                Thread.sleep(100); // 100ms between intervals
            }

        } catch (Exception e) {
            logError(this.log, "CSV injection failed: " + e.getMessage());
        }
    }

    private Map<Instant, Map<String, CsvRow>> readCsvFile() throws Exception {
        Map<Instant, Map<String, CsvRow>> result = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String header = reader.readLine(); // Skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 7) continue;

                String meterId = parts[0];
                LocalDateTime ldt = LocalDateTime.parse(parts[1], formatter);
                Instant timestamp = ldt.atZone(ZoneId.systemDefault()).toInstant();

                CsvRow row = new CsvRow(
                    meterId,
                    getRoleForMeter(meterId),
                    getSourceTypeForMeter(meterId),
                    Integer.parseInt(parts[2]),
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]),
                    Double.parseDouble(parts[6])
                );

                result.computeIfAbsent(timestamp, k -> new HashMap<>()).put(meterId, row);
            }
        }
        return result;
    }

    private String getRoleForMeter(String meterId) {
        if (meterId.startsWith("BESS_") || meterId.startsWith("SOLAR_")) return "SOURCE";
        if (meterId.startsWith("SELF_")) return "SELF_USE";
        if (meterId.startsWith("GRID_")) return "GRID_POINT";
        if (meterId.startsWith("DEST_")) return "INTERCONNECT";
        return "UNKNOWN";
    }

    private String getSourceTypeForMeter(String meterId) {
        if (meterId.startsWith("BESS_")) return "BESS";
        if (meterId.startsWith("SOLAR_")) return "RTS";
        return "";
    }

    private record CsvRow(String meterId, String role, String sourceType,
                         int recordStatus, double impWh, double expWh,
                         double impVa, double expVa) {}
}
```

#### Step 2: Create the Configuration

```java
package io.openems.edge.bridge.edmi.demo;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Bridge EDMI CSV Profile Injector", //
        description = "Injects EDMI profile data from CSV into InfluxDB for testing energy separation")
@interface Config {

    @AttributeDefinition(name = "Component ID", description = "Unique ID of this component")
    String id() default "csvInjector0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name")
    String alias() default "EDMI CSV Injector";

    @AttributeDefinition(name = "Enabled", description = "Enable this component")
    boolean enabled() default true;

    @AttributeDefinition(name = "CSV Path", description = "Absolute path to the CSV file")
    String csvPath() default "C:/demo/reformatted_demo_lp_2025_01_to_26.csv";

    @AttributeDefinition(name = "Injection Speed", description = "Delay between intervals in ms (0 = no delay)")
    int injectionDelayMs() default 100;

    String webconsole_configurationFactory_nameHint() default "Bridge EDMI CSV Profile Injector [{id}]";
}
```

#### Step 3: Register the Component

Create `bnd.bnd` in the new bundle:

```bnd
Bundle-Name: OpenEMS Edge Bridge EDMI CSV Injector
Bundle-Vendor: OpenEMS Association e.V.
Bundle-License: https://opensource.org/licenses/EPL-2.0
Bundle-Version: 1.0.0

-buildpath: \
    ${buildpath},\
    io.openems.edge.bridge.edmi,\
    io.openems.edge.common,\
    com.influxdb.client

-testpath: \
    ${testpath}
```

## Approach 2: Mock EDMI Bridge (Alternative)

### Concept

Create a mock implementation of `EdmiBridge` that returns CSV data instead of reading from serial port.

### When to Use

- When you want to test the full component stack (Meter + Bridge)
- When you need to test the task scheduling logic

### Implementation

Create `MockEdmiBridgeImpl` that implements `EdmiBridge`:

```java
@Component(name = "Bridge.EDMI.Mock", ...)
public class MockEdmiBridgeImpl extends AbstractOpenemsComponent implements EdmiBridge {
    
    private Map<String, MockMeterData> meterData = new HashMap<>();
    
    @Override
    public Object readProfile(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, 
            int serialNumber, String username, String password, short survey) {
        // Return profile data from CSV instead of real meter
        return loadProfileFromCsv(serialNumber, from, to);
    }
    
    @Override
    public List<Object> readBillingValues(String username, String password, int serialNumber) {
        // Return billing data from CSV
        return loadBillingFromCsv(serialNumber);
    }
    
    // ... other methods delegate to real InfluxDB
}
```

## Approach 3: Direct InfluxDB Import (Quick Test)

### Using InfluxDB CLI

Convert CSV to InfluxDB Line Protocol and import directly:

```bash
# Convert CSV to line protocol
python csv_to_influxdb.py \
    --input reformatted_demo_lp_2025_01_to_26.csv \
    --output edmi_profile.lp \
    --measurement edmi_profile

# Import to InfluxDB
influx write --bucket edmi --file edmi_profile.lp
```

### Python Conversion Script

```python
#!/usr/bin/env python3
"""Convert EDMI demo CSV to InfluxDB line protocol."""

import csv
import sys
from datetime import datetime

def convert_csv_to_line_protocol(input_file, output_file):
    with open(input_file, 'r') as f_in, open(output_file, 'w') as f_out:
        reader = csv.DictReader(f_in)
        
        for row in reader:
            # Parse timestamp
            ts = datetime.strptime(row['time_stamp'], '%Y-%m-%d %H:%M:%S')
            timestamp_ns = int(ts.timestamp() * 1e9)
            
            # Determine role and source type from meter_id
            meter_id = row['meter_id']
            if meter_id.startswith('BESS_'):
                role = 'SOURCE'
                source_type = 'BESS'
            elif meter_id.startswith('SOLAR_'):
                role = 'SOURCE'
                source_type = 'RTS'
            elif meter_id.startswith('SELF_'):
                role = 'SELF_USE'
                source_type = ''
            elif meter_id.startswith('GRID_'):
                role = 'GRID_POINT'
                source_type = ''
            elif meter_id.startswith('DEST_'):
                role = 'INTERCONNECT'
                source_type = ''
            else:
                continue
            
            # Write line protocol
            line = (
                f"edmi_profile,"
                f"meter_id={meter_id},"
                f"meter_role={role},"
                f"source_type={source_type} "
                f"record_status={row['record_status']}i,"
                f"total_energy_tot_imp_wh={row['total_energy_tot_imp_wh']},"
                f"total_energy_tot_exp_wh={row['total_energy_tot_exp_wh']},"
                f"total_energy_tot_imp_va={row['total_energy_tot_imp_va']},"
                f"total_energy_tot_exp_va={row['total_energy_tot_exp_va']} "
                f"{timestamp_ns}\n"
            )
            f_out.write(line)

if __name__ == '__main__':
    convert_csv_to_line_protocol(sys.argv[1], sys.argv[2])
```

## Configuration for Testing

### 1. Create InfluxDB Bucket

```bash
influx bucket create --name edmi --org my-org
```

### 2. Configure EDMI Bridge

Create `Bridge.EDMI` config:

```json
{
  "class": "Bridge.EDMI",
  "id": "bridge0",
  "alias": "EDMI Bridge",
  "enabled": true,
  "portName": "COM3",
  "baudRate": 9600,
  "databits": 8,
  "stopbits": "ONE",
  "parity": "NONE",
  "url": "http://localhost:8086",
  "org": "my-org",
  "apiKey": "my-token",
  "bucket": "edmi",
  "interval": 30,
  "survey": 805
}
```

### 3. Configure CSV Injector (if using Approach 1)

```json
{
  "class": "Bridge.EDMI.CsvInjector",
  "id": "csvInjector0",
  "alias": "CSV Injector",
  "enabled": true,
  "csvPath": "E:/ATEnergy/Meter/EDMI_Demo_App/energy_app/reformatted_demo_lp_2025_01_to_26.csv",
  "injectionDelayMs": 100
}
```

### 4. Verify Energy Separation Calculation

Query the results from InfluxDB:

```flux
from(bucket: "edmi")
  |> range(start: -1h)
  |> filter(fn: (r) => r._measurement == "edmi_separated_energy_interval")
  |> filter(fn: (r) => r._field == "rts_to_lmv_kwh" or r._field == "bess_to_lmv_kwh")
```

Or via JSON-RPC:

```json
{
  "jsonrpc": "2.0",
  "id": "uuid",
  "method": "componentJsonApi",
  "params": {
    "componentId": "meter0",
    "payload": {
      "method": "queryEdmiProfileHistory",
      "params": {
        "meterId": "meter0",
        "startDate": "2025-01-01T00:00:00",
        "endDate": "2025-01-31T23:59:59",
        "fields": ["rts_to_lmv_kwh", "bess_to_lmv_kwh", "k_factor"]
      }
    }
  }
}
```

## Comparison: Python Demo vs OpenEMS

| Aspect | Python Demo | OpenEMS |
|--------|-------------|---------|
| **Storage** | PostgreSQL | InfluxDB |
| **Data Format** | `MeterReading` table | `edmi_profile` measurement |
| **Calculation** | `build_interval_state()` + `apply_formula()` | `EdmiEnergySeparationCalculator.process()` |
| **Scenarios** | `detect_scenario()` | `detectScenario()` |
| **Formulas** | `apply_formula()` | `applyFormula()` |
| **K Factor** | `calc_K()` | `loadLastK()` + inline calc |
| **Results** | `MonthlyEnergySummary` | `edmi_separated_energy_interval` |

## Key Differences to Handle

### 1. Data Storage Format

**Python Demo (PostgreSQL):**
```sql
INSERT INTO profile_reading_demo 
(meter_id, time_stamp, record_status, total_energy_tot_imp_wh, total_energy_tot_exp_wh)
VALUES (1, '2025-01-01 00:30:00', 0, 0.0, 1.064);
```

**OpenEMS (InfluxDB):**
```flux
edmi_profile,meter_id=BESS_01,meter_role=SOURCE,source_type=BESS 
record_status=0,total_energy_tot_imp_wh=0.0,total_energy_tot_exp_wh=1.064 1704067200000000000
```

### 2. Meter Registration

**Python Demo:**
- Meters are stored in PostgreSQL `meters` table
- Join with `energy_roles` and `energy_sources` tables

**OpenEMS:**
- Meters are registered via `edmiBridge.registerEnergyMeter(meterId, role, sourceType)`
- Stored in `ConcurrentHashMap<String, EdmiEnergyMeter>`

### 3. Calculation Trigger

**Python Demo:**
- `build_interval_state(db, ts)` called after each interval insertion
- `build_periods()` and `build_monthly_summary()` called after all intervals

**OpenEMS:**
- `edmiBridge.processProfileTimestamp(timestamp)` called after profile read
- `EdmiEnergySeparationCalculator.process()` reads from InfluxDB and writes results

## Testing Checklist

- [ ] CSV file is accessible from OpenEMS runtime
- [ ] InfluxDB bucket `edmi` exists
- [ ] EDMI Bridge is configured and enabled
- [ ] CSV Injector (or mock) is configured and enabled
- [ ] All 11 meters are registered with correct roles
- [ ] Profile data appears in InfluxDB `edmi_profile` measurement
- [ ] Energy separation results appear in `edmi_separated_energy_interval`
- [ ] K factor is calculated correctly (0 <= K <= 1)
- [ ] RTS + BESS energy equals total export * (1 - K)
- [ ] Scenario codes match expected values
- [ ] Results match Python demo calculation within 0.1% tolerance

## Troubleshooting

### Issue: No data in InfluxDB

**Check:**
1. CSV path is correct and accessible
2. InfluxDB connection parameters are correct
3. Bucket exists and API token has write permission

### Issue: Energy separation not calculating

**Check:**
1. All required meters are registered (GRID, SELF, INTERCONNECT, BESS, RTS)
2. Profile data exists for the timestamp being processed
3. Previous interval data exists (needed for delta calculation)

### Issue: K factor is 0 or wrong

**Check:**
1. Grid export (expWh) > 0
2. Interconnect import (impWh) > 0
3. Grid export > Interconnect import (otherwise K = 0)

### Issue: Results don't match Python demo

**Check:**
1. Meter roles and source types match exactly
2. Timestamps are aligned correctly (30-minute intervals)
3. Delta calculation uses same formula: `max(0, end - start) / 1000`

## Next Steps

1. **Implement Approach 1** (CSV Injector) for automated testing
2. **Add unit tests** comparing OpenEMS results with Python demo results
3. **Create visualization** in OpenEMS UI for energy separation results
4. **Add alerting** for scenario changes (e.g., BESS_FAULTY)

## References

- [EDMI Driver Guide](EDMIDriverGuide.md)
- [Python Demo App](E:/ATEnergy/Meter/EDMI_Demo_App/energy_app/run.py)
- [OpenEMS EDMI Bridge](io.openems.edge.bridge.edmi)
- [OpenEMS EDMI Meter](io.openems.edge.meter.edmi)
