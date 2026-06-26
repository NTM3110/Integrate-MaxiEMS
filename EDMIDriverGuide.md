# EDMI Driver Guide for OpenEMS

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Communication Protocol](#communication-protocol)
4. [Module Structure](#module-structure)
5. [Data Flow](#data-flow)
6. [Task Scheduling](#task-scheduling)
7. [Energy Separation Calculation](#energy-separation-calculation)
8. [Configuration](#configuration)
9. [JSON-RPC API](#json-rpc-api)
10. [Comparison with Other Meter Drivers](#comparison-with-other-meter-drivers)
11. [Troubleshooting](#troubleshooting)
12. [File Reference](#file-reference)

---

## Overview

The EDMI driver is an OpenEMS integration for **EDMI smart meters** (Atlas/Genius product line). Unlike typical Modbus-based meter drivers, the EDMI driver uses a **proprietary serial protocol** via the external `com.atdigital.imr` (IMR) Java client library.

### Key Characteristics

- **Protocol**: Serial (RS-232/RS-485) with proprietary IMR library
- **Data Storage**: InfluxDB for both raw and processed data
- **Energy Separation**: Post-processes profile data to calculate RTS vs BESS energy contributions
- **Scheduling**: DelayQueue-based task scheduler for periodic meter reads

---

## Architecture

### Two-Module Design

The EDMI driver is split into two OSGi modules:

| Module | Bundle | Purpose |
|--------|--------|---------|
| **Bridge** | `io.openems.edge.bridge.edmi` | Serial communication, task scheduling, InfluxDB storage, energy separation math |
| **Meter** | `io.openems.edge.meter.edmi` | Component wrapper, JSON-RPC API, task registration |

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    OpenEMS Edge                             │
│                                                             │
│  ┌─────────────────┐      ┌─────────────────────────────┐  │
│  │  Meter.EDMI     │      │      Bridge.EDMI            │  │
│  │  (EdmiMeterImpl)│──────│      (EdmiBridgeImpl)       │  │
│  │                 │      │                             │  │
│  │ • Registers     │      │ • Serial Communication      │  │
│  │   tasks         │      │ • Task Scheduler          │  │
│  │ • JSON-RPC API  │      │ • InfluxDB Read/Write     │  │
│  │                 │      │ • Energy Separation         │  │
│  └─────────────────┘      │   Calculator              │  │
│                           │                             │  │
│                           │  ┌───────────────────────┐  │  │
│                           │  │   EdmiWorker          │  │  │
│                           │  │   (DelayQueue)        │  │  │
│                           │  └───────────────────────┘  │  │
│                           └─────────────────────────────┘  │
│                                    │                        │
│                           ┌────────▼────────┐              │
│                           │  IMR Library      │              │
│                           │  (MeterClient)    │              │
│                           └────────┬────────┘              │
│                                    │ Serial Port           │
└────────────────────────────────────┼──────────────────────┘
                                     │
                            ┌────────▼────────┐
                            │   EDMI Meter    │
                            │  (Atlas/Genius) │
                            └─────────────────┘
```

---

## Communication Protocol

### Protocol Stack

| Layer | Technology |
|-------|-----------|
| Physical | Serial (RS-232 / RS-485) |
| Data Link | Proprietary IMR protocol |
| Library | `com.atdigital.imr.MeterClient` |

### Connection Flow

```java
// EdmiBridgeImpl.java
MeterClient client = new MeterClient(portName, baudRate, dataBit, stopBit, parityBit);
client.connect();
client.login(username, password);

// Read data
Object billing = client.readBillingValues();
Object profile = client.readProfile(from, to, survey);

client.logout();
client.disconnect();
```

### Serial Parameters

| Parameter | Config Key | Typical Value |
|-----------|-----------|---------------|
| Port Name | `portName` | `COM3` (Windows) or `/dev/ttyUSB0` (Linux) |
| Baud Rate | `baudRate` | `9600` |
| Data Bits | `databits` | `8` |
| Stop Bits | `stopbits` | `1` |
| Parity | `parity` | `NONE` |

### Meter Credentials

| Credential | Source |
|-----------|--------|
| Username | Config (`username`) |
| Password | Config (`password`) |
| Serial Number | Config (`serial_number`) |

---

## Module Structure

### Bridge Module (`io.openems.edge.bridge.edmi`)

```
io.openems.edge.bridge.edmi/
├── src/io/openems/edge/bridge/edmi/
│   ├── EdmiBridge.java                    # Bridge interface
│   ├── EdmiBridgeImpl.java                # Main implementation
│   ├── Config.java                        # OSGi config
│   ├── EdmiEnergySeparationCalculator.java # Energy separation math
│   ├── EdmiEnergyMeter.java               # Meter classification
│   ├── EdmiProtocol.java                  # Protocol container
│   ├── EdmiWorker.java                    # Task scheduler
│   ├── ProfileIngestionSettings.java      # Profile config
│   ├── Stopbit.java                       # Stop bit enum
│   ├── Parity.java                        # Parity enum
│   └── api/
│       ├── AbstractEdmiTask.java          # Task base class
│       ├── AbstractOpenemsEdmiComponent.java # Component base
│       ├── EdmiElement.java               # Element abstraction
│       ├── EdmiTask.java                  # Task interface
│       ├── FormatHelper.java              # Timestamp formatting
│       ├── ReadBillingTask.java           # Billing read task
│       ├── ReadProfileTask.java           # Profile read task
│       └── ReadRegistersTask.java         # Register read stub
```

### Meter Module (`io.openems.edge.meter.edmi`)

```
io.openems.edge.meter.edmi/
├── src/io/openems/edge/meter/edmi/
│   ├── EdmiMeterImpl.java                 # Main meter component
│   ├── Config.java                        # OSGi config
│   └── EdmiRegisters.java                 # Register constants
```

---

## Data Flow

### Complete Data Flow Diagram

```
EDMI Meter
    │
    ▼
┌─────────────────┐
│  MeterClient    │  (IMR Library)
│  (Serial)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ EdmiBridgeImpl  │
│                 │
│ • readBilling() │
│ • readProfile() │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐  ┌────────┐
│Billing │  │Profile │
│ Task   │  │ Task   │
└───┬────┘  └───┬────┘
    │           │
    ▼           ▼
┌─────────────────────┐
│     InfluxDB        │
│                     │
│ • edmi_billing_values│
│ • edmi_profile       │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────────────┐
│ EdmiEnergySeparationCalculator│
│                             │
│ • Reads current+previous   │
│ • Calculates deltas        │
│ • Applies formulas         │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│     InfluxDB                │
│                             │
│ • edmi_separated_energy_    │
│   interval                  │
└─────────────────────────────┘
```

### Data Measurements

| Measurement | Content | Interval |
|------------|---------|----------|
| `edmi_billing_values` | Real-time billing registers | Every 30 seconds |
| `edmi_profile` | Historical profile records | Every 30 minutes |
| `edmi_separated_energy_interval` | Calculated energy separation | Every 30 minutes |

---

## Task Scheduling

### EdmiWorker

The `EdmiWorker` uses a `DelayQueue` to manage periodic tasks:

```java
public class EdmiWorker {
    private final DelayQueue<EdmiTask> queue = new DelayQueue<>();
    
    public void addTask(EdmiTask task) {
        queue.put(task);
    }
    
    // Worker thread continuously polls queue
    // Tasks execute when their delay expires
}
```

### Task Types

#### 1. ReadBillingTask

- **Priority**: HIGH
- **Interval**: 30 seconds
- **Purpose**: Read real-time billing registers
- **Data**: 14 values including import/export kWh, kvar

```java
public class ReadBillingTask extends AbstractEdmiTask {
    private static final long BILLING_INTERVAL_MILLIS = 30 * 1000L;
    private static final int BILLING_VALUE_COUNT = 14;
    
    // Fields stored:
    // [0] meter_serial_number
    // [1] error_code
    // [2] date
    // [3] time
    // [4] rate1_imp_wh
    // [5] rate2_imp_kwh
    // [6] rate3_imp_kwh
    // [7] total_imp_kwh
    // [8] total_imp_kvar
    // [9] rate1_exp_kwh
    // [10] rate2_exp_kwh
    // [11] rate3_exp_kwh
    // [12] total_exp_kwh
    // [13] total_exp_kvar
}
```

#### 2. ReadProfileTask

- **Priority**: HIGH
- **Interval**: 30 minutes (configurable)
- **Purpose**: Read historical profile records
- **Data**: Import/export Wh per interval

```java
public class ReadProfileTask extends AbstractEdmiTask {
    // Fields stored per record:
    // • record_status
    // • total_energy_tot_imp_wh
    // • total_energy_tot_exp_wh
    // • total_energy_tot_imp_vah (optional)
    // • total_energy_tot_exp_vah (optional)
}
```

---

## Energy Separation Calculation

### Overview

The energy separation calculator determines how much energy came from:
- **RTS** (Rooftop Solar)
- **BESS** (Battery Energy Storage System)
- **Grid**

### Meter Classification

Each meter is classified by `role` and `sourceType`:

```java
public record EdmiEnergyMeter(String meterId, String role, String sourceType) {
    public boolean isBessSource()   { return "SOURCE".equals(role) && "BESS".equals(sourceType); }
    public boolean isRtsSource()    { return "SOURCE".equals(role) && "RTS".equals(sourceType); }
    public boolean isSelfUse()      { return "SELF_USE".equals(role); }
    public boolean isGridPoint()    { return "GRID_POINT".equals(role); }
    public boolean isInterconnect() { return "INTERCONNECT".equals(role); }
}
```

#### Roles

| Role | Description |
|------|-------------|
| `SOURCE` | Energy source (BESS or RTS) |
| `SELF_USE` | Self-consumption meter |
| `GRID_POINT` | Grid connection point |
| `INTERCONNECT` | Interconnection point |

#### Source Types

| Source Type | Description |
|-------------|-------------|
| `BESS` | Battery Energy Storage System |
| `RTS` | Rooftop Solar |

### Calculation Process

#### Step 1: Read Profile Data

```java
private ProfileRow readProfileRow(String meterId, Instant timestamp) {
    JsonArray rows = bridge.queryProfileValuesFromInflux(meterId, timestamp, timestamp, 
        List.of("record_status", "total_energy_tot_imp_wh", "total_energy_tot_exp_wh"));
    // Returns import and export Wh for the timestamp
}
```

#### Step 2: Calculate Deltas

```java
private static double deltaKwh(double startWh, double endWh) {
    return Math.max(0, endWh - startWh) / 1000.0;  // Convert Wh to kWh
}
```

For each meter, calculate:
- **BESS Discharge**: `delta(previous.expWh, current.expWh)`
- **BESS Charge**: `delta(previous.impWh, current.impWh)`
- **RTS Export**: `delta(previous.expWh, current.expWh)`
- **Self Use**: `delta(previous.impWh, current.impWh)`
- **Grid Export**: `delta(previous.expWh, current.expWh)`
- **Interconnect Import**: `delta(previous.impWh, current.impWh)`

#### Step 3: Detect Scenario

Based on which meters are available:

```java
private static String detectScenario(int bessMissing, int rtsMissing, 
        boolean self, boolean grid, boolean interconnect) {
    if (!grid)           return "INQUALIFIED";
    if (!interconnect)   return "NO_INTERCONNECT";
    if (!self)           return "NO_SELF";
    if (bessMissing > 0 && rtsMissing > 0) return "BESS_RTS_FAULTY";
    if (bessMissing > 0) return "BESS_FAULTY";
    if (rtsMissing > 0)  return "RTS_FAULTY";
    return "ALL_OK";
}
```

#### Step 4: Select Formula

| Scenario | Formula Code | Description |
|----------|-------------|-------------|
| ALL_OK | F01_NORMAL | All meters available |
| NO_INTERCONNECT | F03_NO_INTERCONNECT | Missing interconnect meter |
| RTS_FAULTY | F04_ONLY_RTS_FAULTY | RTS meter missing |
| NO_SELF | F05_NO_SELF | Self-use meter missing |
| BESS_FAULTY | F06_ONLY_BESS_FAULTY | BESS meter missing |
| BESS_RTS_FAULTY | F07_BOTH_BESS_RTS_FAULTY | Both BESS and RTS missing |
| INQUALIFIED | F99_INQUALIFIED | No grid meter |

#### Step 5: Calculate K Factor

```java
double e = inputs.gridExportKwh();           // Grid export
double eLmv = inputs.interconnectImportKwh(); // Interconnect import
double k = e > 0 && eLmv > 0 ? Math.max(0, (e - eLmv) / e) : lastK;
```

The K factor represents the **loss factor** between grid export and interconnect import.

#### Step 6: Apply Formula

**F01_NORMAL / F03_NO_INTERCONNECT:**
```java
rtsToLmv = (rtsExport - bessCharge - selfUse) * (1 - k);
bessToLmv = gridExport * (1 - k) - rtsToLmv;
```

**F02_NO_GRID:**
```java
rtsToLmv = (rtsExport - bessCharge - selfUse) * (1 - k);
bessToLmv = eLmv - rtsToLmv;
```

**F04/F05/F06/F07 (Faulty Meters):**
```java
rtsToLmv = (gridExport - bessDischarge) * (1 - k);
bessToLmv = bessDischarge * (1 - k);
```

#### Step 7: Store Results

```java
bridge.writeToInflux(Point.measurement("edmi_separated_energy_interval")
    .time(timestamp, WritePrecision.MS)
    .addTag("scope", "site")
    .addField("interval_minutes", settings.intervalMinutes())
    .addField("rts_to_lmv_kwh", result.rtsToLmvKwh())
    .addField("bess_to_lmv_kwh", result.bessToLmvKwh())
    .addField("total_to_lmv_kwh", result.rtsToLmvKwh() + result.bessToLmvKwh())
    .addField("k_factor", result.k())
    .addField("scenario_code", state.scenarioCode())
    .addField("formula_code", formula)
    // ... availability flags ...
);
```

### Example Calculation

Given:
- Grid export: 100 kWh
- Interconnect import: 80 kWh
- RTS export: 60 kWh
- BESS charge: 10 kWh
- Self use: 30 kWh

Calculation:
```
K = (100 - 80) / 100 = 0.2

RTS to LMV = (60 - 10 - 30) * (1 - 0.2) = 20 * 0.8 = 16 kWh
BESS to LMV = 100 * 0.8 - 16 = 80 - 16 = 64 kWh
Total = 16 + 64 = 80 kWh
```

---

## Configuration

### Bridge Configuration (`Bridge.EDMI`)

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Component ID |
| `alias` | String | Human-readable name |
| `enabled` | boolean | Enable/disable |
| `portName` | String | Serial port (e.g., `COM3`) |
| `baudRate` | int | Baud rate (e.g., `9600`) |
| `databits` | int | Data bits (e.g., `8`) |
| `stopbits` | Stopbit | Stop bits (`ONE`, `TWO`) |
| `parity` | Parity | Parity (`NONE`, `EVEN`, `ODD`) |
| `url` | String | InfluxDB URL |
| `org` | String | InfluxDB organization |
| `apiKey` | String | InfluxDB API key |
| `bucket` | String | InfluxDB bucket |
| `queryLanguage` | QueryLanguageConfig | `INFLUX_QL` or `FLUX` |
| `interval` | int | Profile interval in minutes |
| `survey` | short | Profile survey ID (default: `0x0325`) |
| `retryMillis` | long | Retry delay in milliseconds |
| `finalizeAfterMillis` | long | Finalization delay |
| `maxRecords` | int | Max profile records per read |

### Meter Configuration (`Meter.EDMI`)

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Component ID |
| `alias` | String | Human-readable name |
| `enabled` | boolean | Enable/disable |
| `serial_number` | int | Meter serial number |
| `username` | String | Meter login username |
| `password` | String | Meter login password |
| `energyRole` | EnergyMeterRole | Meter role (`SOURCE`, `SELF_USE`, `GRID_POINT`, `INTERCONNECT`) |
| `energySourceType` | EnergySourceType | Source type (`BESS`, `RTS`) |

### Example Configuration

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

```json
{
  "class": "Meter.EDMI",
  "id": "meter0",
  "alias": "Grid Meter",
  "enabled": true,
  "serial_number": 12345678,
  "username": "admin",
  "password": "secret",
  "energyRole": "GRID_POINT",
  "energySourceType": "RTS"
}
```

---

## JSON-RPC API

### Available Methods

#### 1. getEdmiProfile

Read profile data directly from the meter.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "uuid",
  "method": "componentJsonApi",
  "params": {
    "componentId": "meter0",
    "payload": {
      "method": "getEdmiProfile",
      "params": {
        "startDate": "24-01-15T00:00:00",
        "endDate": "24-01-15T23:59:59"
      }
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "uuid",
  "result": {
    "objects": {
      "channels": ["timestamp", "status", "import_wh", "export_wh"],
      "data": [
        ["24-01-15T00:00:00", "OK", 1000, 500]
      ]
    }
  }
}
```

#### 2. queryEdmiBillingHistory

Query billing history from InfluxDB.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "uuid",
  "method": "componentJsonApi",
  "params": {
    "componentId": "meter0",
    "payload": {
      "method": "queryEdmiBillingHistory",
      "params": {
        "meterId": "meter0",
        "startDate": "2024-01-01T00:00:00",
        "endDate": "2024-01-31T23:59:59",
        "fields": ["total_imp_kwh", "total_exp_kwh"]
      }
    }
  }
}
```

#### 3. queryEdmiProfileHistory

Query profile history from InfluxDB.

**Request:**
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
        "startDate": "2024-01-01T00:00:00",
        "endDate": "2024-01-31T23:59:59",
        "fields": ["total_energy_tot_imp_wh", "total_energy_tot_exp_wh"]
      }
    }
  }
}
```

---

## Comparison with Other Meter Drivers

### Protocol Comparison

| Driver | Protocol | Library |
|--------|----------|---------|
| **EDMI** | Serial (IMR) | `com.atdigital.imr` |
| Janitza UMG 96RME | Modbus RTU | j2mod |
| Siemens PAC1600 | Modbus TCP | j2mod |
| Eastron SDM630 | Modbus RTU | j2mod |
| Hager ECR380D | Modbus RTU | j2mod |
| Weidmueller 525 | Modbus TCP | j2mod |

### Energy Handling Comparison

| Driver | Energy Separation | Approach |
|--------|-------------------|----------|
| **EDMI** | **Post-processing** | Profile-based delta calculation with formulas |
| Janitza | Direct mapping | Separate import/export registers |
| Siemens | Direct mapping | Separate import/export registers |
| Eastron | Derived from power | Calculate energy from power sign |
| Hager | Direct mapping | Separate import/export registers |
| Weidmueller | Direct mapping | Separate inductive/capacitive reactive |

### Key Differences

1. **EDMI** is the only driver that performs complex energy separation calculations
2. **EDMI** uses an external proprietary library (IMR)
3. **EDMI** stores data in InfluxDB before processing
4. **Other drivers** typically map Modbus registers directly to OpenEMS channels

---

## Troubleshooting

### Common Issues

#### 1. Connection Failed

**Symptoms:**
- `Unable to connect to EDMI meter` in logs
- `SLAVE_COMMUNICATION_FAILED` channel is true

**Solutions:**
- Check serial port name (Windows: `COM3`, Linux: `/dev/ttyUSB0`)
- Verify baud rate matches meter configuration
- Check physical cable connection
- Verify meter credentials (username, password, serial number)

#### 2. No Profile Data

**Symptoms:**
- `edmi_profile` measurement empty in InfluxDB
- Energy separation not calculating

**Solutions:**
- Check profile survey ID (default: `0x0325`)
- Verify interval setting matches meter configuration
- Check `finalizeAfterMillis` (data is only read after this delay)
- Ensure meter has historical profile data

#### 3. Energy Separation Not Working

**Symptoms:**
- `edmi_separated_energy_interval` empty
- All scenarios show `INQUALIFIED` or `NO_INTERCONNECT`

**Solutions:**
- Verify all required meters are configured:
  - At least one `GRID_POINT` meter
  - At least one `SELF_USE` meter
  - At least one `INTERCONNECT` meter
  - `SOURCE` meters for BESS/RTS
- Check that meters have data in `edmi_profile`
- Verify `interval` setting is consistent across meters

#### 4. InfluxDB Connection Issues

**Symptoms:**
- Data not appearing in InfluxDB
- Timeout errors in logs

**Solutions:**
- Verify InfluxDB URL is accessible
- Check API key has write permissions
- Verify bucket exists
- Check network connectivity

### Debug Logging

Enable debug logging for the EDMI packages:

```properties
# In OpenEMS logging configuration
log4j.logger.io.openems.edge.bridge.edmi=DEBUG
log4j.logger.io.openems.edge.meter.edmi=DEBUG
```

### Useful Log Messages

| Message | Meaning |
|---------|---------|
| `Successfully connected to IEC 104 Slave` | Connection established |
| `Received ASDU [type] COT=cause #IOA=n` | ASDU received from server |
| `Reading Profile from meter [id]` | Profile read task starting |
| `Write billing data to InfluxDB` | Billing data stored |
| `Failed to calculate EDMI separated energy` | Energy separation error |

---

## File Reference

### Key Source Files

| File | Path | Purpose |
|------|------|---------|
| `EdmiBridgeImpl.java` | `io.openems.edge.bridge.edmi/src/.../edmi/EdmiBridgeImpl.java` | Main bridge implementation |
| `EdmiEnergySeparationCalculator.java` | `io.openems.edge.bridge.edmi/src/.../edmi/EdmiEnergySeparationCalculator.java` | Energy separation math |
| `EdmiEnergyMeter.java` | `io.openems.edge.bridge.edmi/src/.../edmi/EdmiEnergyMeter.java` | Meter classification |
| `ReadProfileTask.java` | `io.openems.edge.bridge.edmi/src/.../edmi/api/ReadProfileTask.java` | Profile reading |
| `ReadBillingTask.java` | `io.openems.edge.bridge.edmi/src/.../edmi/api/ReadBillingTask.java` | Billing reading |
| `EdmiMeterImpl.java` | `io.openems.edge.meter.edmi/src/.../meter/edmi/EdmiMeterImpl.java` | Meter component |
| `Config.java` (Bridge) | `io.openems.edge.bridge.edmi/src/.../edmi/Config.java` | Bridge config |
| `Config.java` (Meter) | `io.openems.edge.meter.edmi/src/.../meter/edmi/Config.java` | Meter config |
| `EdmiRegisters.java` | `io.openems.edge.meter.edmi/src/.../meter/edmi/EdmiRegisters.java` | Register constants |
| `EdmiWorker.java` | `io.openems.edge.bridge.edmi/src/.../edmi/EdmiWorker.java` | Task scheduler |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| `com.atdigital.imr` | IMR meter client library |
| `jSerialComm` | Serial port communication |
| `influxdb-client-java` | InfluxDB read/write |

---

## Additional Resources

- [EDMI Product Page](https://www.edmi-meters.com/product-lines/atlas-genius/)
- [OpenEMS Meter API Documentation](https://openems.github.io/openems.io/openems/latest/edge/nature.d/io.openems.edge.meter.api.html)
- [InfluxDB Documentation](https://docs.influxdata.com/)

---

*This guide was generated for the OpenEMS EDMI driver implementation.*
