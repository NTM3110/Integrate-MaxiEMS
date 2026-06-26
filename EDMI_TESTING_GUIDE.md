# EDMI Energy Separation Testing Guide

## Prerequisites

1. **Java 21** installed
2. **Gradle** installed (or use the wrapper `./gradlew.bat`)
3. **InfluxDB** running locally (or accessible)
4. **CSV file** with demo data at `E:\ATEnergy\Meter\EDMI_Demo_App\energy_app\reformatted_demo_lp_2025_01_to_26.csv`

## Step 1: Build the Project

### Option A: Build Everything (Recommended for first time)

```powershell
# Navigate to project root
cd E:\ATEnergy\Integrate-MAXiEMS

# Build the fat JAR (this includes all edge bundles including the new CSV injector)
.\gradlew.bat buildEdge

# Or with memory constraints (if you get OutOfMemory errors)
.\gradlew.bat --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:CICompilerCount=2' buildEdge
```

**Output:** `build/openems-edge.jar`

### Option B: Build Only Changed Modules (Faster for incremental builds)

```powershell
# Compile only the changed modules
.\gradlew.bat :io.openems.edge.bridge.edmi:compileJava
.\gradlew.bat :io.openems.shared.influxdb:compileJava

# Then build the full JAR
.\gradlew.bat buildEdge
```

## Step 2: Configure InfluxDB

### Create the InfluxDB Bucket

```bash
# Using influx CLI
influx bucket create --name edmi --org my-org

# Or via InfluxDB UI at http://localhost:8086
```

### Get API Token

1. Go to InfluxDB UI → Load Data → API Tokens
2. Generate an All-Access token
3. Copy the token for configuration

## Step 3: Configure OpenEMS Components

Create configuration files in the `config/` folder:

### 3.1 Bridge.EDMI Configuration

Create file: `config/Bridge.EDMI/bridge0.config`

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
  "apiKey": "YOUR_INFLUXDB_TOKEN",
  "bucket": "edmi",
  "queryLanguage": "FLUX",
  "interval": 30,
  "survey": 805,
  "retryMillis": 5000,
  "finalizeAfterMillis": 60000,
  "maxRecords": 100
}
```

### 3.2 CSV Injector Configuration

Create file: `config/Bridge.EDMI.CsvInjector/csvInjector0.config`

```json
{
  "class": "Bridge.EDMI.CsvInjector",
  "id": "csvInjector0",
  "alias": "EDMI CSV Injector",
  "enabled": true,
  "csvPath": "E:/ATEnergy/Meter/EDMI_Demo_App/energy_app/reformatted_demo_lp_2025_01_to_26.csv",
  "injectionDelayMs": 200,
  "triggerCalculationPerRow": true
}
```

**Note:** The CSV injector will automatically register all 11 meters with the bridge.

## Step 4: Start OpenEMS

```powershell
# Option A: Use the built JAR with embedded config
java -jar build/openems-edge.jar

# Option B: Use external config directory (recommended for testing)
java "-Dfelix.cm.dir=E:/ATEnergy/Integrate-MAXiEMS/config" "-Dopenems.data.dir=E:/ATEnergy/Integrate-MAXiEMS/data" -jar build/openems-edge.jar
```

## Step 5: Verify Components are Active

### Check Felix Console

1. Open browser: http://localhost:8080/system/console
2. Login with default credentials (admin/admin)
3. Go to **Components** tab
4. Verify these components are active:
   - `Bridge.EDMI` (bridge0)
   - `Bridge.EDMI.CsvInjector` (csvInjector0)

### Check Logs

Look for these log messages in the console:

```
Starting CSV profile injection from: E:\ATEnergy\Meter\EDMI_Demo_App\energy_app\reformatted_demo_lp_2025_01_to_26.csv
Loaded X intervals from CSV
Injected profile row for meter BESS_01 at 2025-01-01T00:30:00Z
Energy separation calculated for interval 1 (2025-01-01T00:30:00Z)
CSV injection complete. Processed X intervals.
```

## Step 6: Verify Results in InfluxDB

### Query Profile Data

```flux
from(bucket: "edmi")
  |> range(start: 2025-01-01T00:00:00Z, stop: 2025-01-31T23:59:59Z)
  |> filter(fn: (r) => r._measurement == "edmi_profile")
  |> filter(fn: (r) => r.meter_id == "BESS_01")
  |> limit(n: 10)
```

### Query Energy Separation Results

```flux
from(bucket: "edmi")
  |> range(start: 2025-01-01T00:00:00Z, stop: 2025-01-31T23:59:59Z)
  |> filter(fn: (r) => r._measurement == "edmi_separated_energy_interval")
  |> filter(fn: (r) => r._field == "rts_to_lmv_kwh" or r._field == "bess_to_lmv_kwh" or r._field == "k_factor")
  |> limit(n: 10)
```

### Expected Results

You should see:
- `rts_to_lmv_kwh` - Energy from solar to load
- `bess_to_lmv_kwh` - Energy from battery to load
- `k_factor` - Loss factor (0 to 1)
- `scenario_code` - e.g., "ALL_OK", "BESS_FAULTY", etc.
- `formula_code` - e.g., "F01_NORMAL", "F03_NO_INTERCONNECT", etc.

## Step 7: Compare with Python Demo

Run the Python demo to compare results:

```powershell
cd E:\ATEnergy\Meter\EDMI_Demo_App\energy_app
python run.py demo-reset
```

Then query the Python API:
```powershell
Invoke-RestMethod 'http://localhost:8000/api/energy/separated-calculation'
```

Compare the `rts_to_lmv_kwh` and `bess_to_lmv_kwh` values between Python and OpenEMS.

## Troubleshooting

### Issue: "No data found in CSV file"

**Check:**
- CSV path is correct and file exists
- File permissions allow reading

### Issue: "Failed to sync write to InfluxDB"

**Check:**
- InfluxDB is running
- URL, org, token, bucket are correct
- Bucket exists

### Issue: "Energy separation calculation failed"

**Check:**
- Profile data exists in InfluxDB
- All required meters are registered (GRID, SELF, INTERCONNECT, BESS, RTS)
- Previous interval data exists (needed for delta calculation)

### Issue: "Component not found in Felix console"

**Check:**
- Build completed successfully
- JAR file contains the bundle
- Check `build/openems-edge.jar` exists and is recent

## Quick Test Commands

```powershell
# Build
.\gradlew.bat buildEdge

# Start with config
java "-Dfelix.cm.dir=E:/ATEnergy/Integrate-MAXiEMS/config" -jar build/openems-edge.jar

# Query InfluxDB via CLI
influx query 'from(bucket: "edmi") |> range(start: -1h) |> filter(fn: (r) => r._measurement == "edmi_separated_energy_interval")'
```

## Files Changed

1. `io.openems.edge.bridge.edmi/src/.../EdmiEnergySeparationCalculator.java` - Updated formulas
2. `io.openems.edge.bridge.edmi/src/.../csvinjector/CsvProfileInjector.java` - New component
3. `io.openems.edge.bridge.edmi/src/.../csvinjector/Config.java` - Configuration
4. `io.openems.shared.influxdb/src/.../InfluxConnector.java` - Added sync write
5. `io.openems.edge.bridge.edmi/src/.../EdmiBridge.java` - Added sync write interface
6. `io.openems.edge.bridge.edmi/src/.../EdmiBridgeImpl.java` - Implemented sync write
