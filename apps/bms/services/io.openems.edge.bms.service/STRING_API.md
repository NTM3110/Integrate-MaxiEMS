# Core BMS string API

This is the first MaxiEMS BMS vertical slice. It is served through the Edge WebSocket JSON-RPC endpoint and is intended for the existing Svelte BMS UI.

## Ownership

| Data | Owner |
|---|---|
| String name, cell count, battery metadata and thresholds | PostgreSQL `bms.bms_string` and `bms.string_threshold` |
| TA, TC and Modbus bridge references | OpenEMS ConfigurationAdmin |
| Current measurements and calculated values | OpenEMS live channels, calculated per request |

PostgreSQL does not mirror TA/TC component properties. Each string supplies its dedicated bridge ID, while the TC component always uses Modbus unit `206`.

## Calls

Every call requires the PostgreSQL-authenticated opaque `token`. `create` and `delete` require `BMS_OPERATOR`; reading requires `BMS_VIEWER` or a higher role.

### `bms.strings.create`

```json
{
  "method": "bms.strings.create",
  "params": {
    "token": "opaque-session-token",
    "stringName": "Battery string 1",
    "cellQty": 24,
    "cellBrand": "Huasu",
    "cellModel": "TA",
    "ratedCapacity": 200,
    "cutoffVoltage": 1.8,
    "floatVoltage": 2.25,
    "boostVoltage": 2.4,
    "rNew": 1450,
    "highResistanceThreshold": 1900,
    "highTemperatureThreshold": 50,
    "lowVoltageThreshold": 1.8,
    "highVoltageThreshold": 2.5,
    "serialPortId": "modbus-string-1",
    "startTaUnitId": 1
  }
}
```

The response includes the stored string definition, `active`, `statusMessage`, `stringData`, and one `cellsData` entry per configured TA. It can initially be inactive while Modbus components are activating or have not received values.

### `bms.strings.list`

Returns `{ "strings": [...] }` with Svelte-compatible `BatteryString` fields.

### `bms.strings.getData`

```json
{
  "method": "bms.strings.getData",
  "params": { "token": "opaque-session-token", "stringIndex": 1 }
}
```

The service reads each TA and its TC. It converts mV to V, deci-Celsius to Celsius, milliamps to amps, calculates per-cell IR as `internalResistance_uOhm * rawCurrent_mA / 10000000`, sums cell voltage, averages available cell values, and uses TC SoC/SoH only when no cell SoC/SoH is present.

### `bms.strings.delete`

Deletes only the selected string's TA/TC configurations and its PostgreSQL metadata. It never recreates or deletes unrelated strings.

## Activation conditions

1. Configure the `MaxiEMS BMS PostgreSQL` OpenEMS component with the PostgreSQL host, database, user, and password.
2. Configure the platform authentication bootstrap account, then authenticate with `platformAuthenticate` to obtain the opaque token.
3. Ensure the bridge named by `serialPortId` is already configured and dedicated to that string.
4. Create the string, then poll `bms.strings.getData` from Svelte while its components activate.
