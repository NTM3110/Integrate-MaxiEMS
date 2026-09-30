# BMS code ownership

The BMS application keeps transport, contracts, scheduled controllers, business
services and JSON-RPC presentation separate. Contracts, models and business
implementations now share one physical service bundle; drivers, scheduled
controllers and endpoints remain separate bundles under `apps/bms`.
Service Java packages are rooted at `io.openems.edge.bms.service.<feature>`;
for example, alarm implementation classes use
`io.openems.edge.bms.service.alarms.implement`.

| Bundle | Owns | Depends on |
|---|---|---|
| `io.openems.edge.bms.huasu.ta`, `.tc` | Modbus device channels for cell and string measurements | OpenEMS Modbus |
| `io.openems.edge.bms.service` | `<feature>/contracts`, `<feature>/model`, and `<feature>/implement` including BMS schema migrations | OpenEMS common, drivers, modem, shared PostgreSQL |
| `io.openems.edge.shared.postgresql` | Independent PostgreSQL connection pools, one ConfigAdmin factory instance per connection | PostgreSQL JDBC, HikariCP |
| `io.openems.edge.controller.bms` | Scheduled calculation, monitoring and Modbus controllers | Service contracts/models, drivers |
| `io.openems.edge.bms.api` | All BMS `endpoints/<feature>/*JsonApi` WebSocket facades | Service contracts/models |

The normal request path is:

```text
Frontend JSON-RPC
  → endpoints/<feature>/*JsonApi (authentication, validation, response)
  → <feature>/contracts/*Service or *Operations
  → <feature>/implement/*Impl (business orchestration)
  → <feature>/implement/persistence/* (PostgreSQL, when required)
```

Live telemetry has a separate producer path:

```text
TA/TC channels → calculation controller → BmsCalculationSnapshot
  → monitoring controller → BmsMonitoringSnapshot
  → string/monitoring APIs, alarms, history and Modbus
```

The calibration worker consumes the calculation snapshot directly so it can
record acquisition data without treating monitoring publication revisions as
new physical samples. The history writer and alarm processor are background
services, not JSON endpoints.

Representative contract-to-implementation pairs:

| Contract in `service/<feature>/contracts` | Implementation in `service/<feature>/implement` |
|---|---|
| `PlatformAuthenticationService` | `auth/platform/PlatformAuthenticationServiceImpl` |
| `CalibrationEventOperations` | `calibration/CalibrationEventService` |
| `StringManagementOperations` | `strings/persistence/StringManagementService` |
| `BmsStringManager` | `strings/persistence/PersistentBmsStringManager` |
| `MonitoringAlarmService` | `alarms/MonitoringAlarmProcessor` |
| `BmsSerialBridgeService` | `serial/BmsSerialBridgeServiceImpl` |

An interface is placed in `service/<feature>/contracts` when another bundle needs it.
Private repository interfaces and helper classes stay beside their
implementation in `service/<feature>/implement`; this avoids exposing JDBC and
internal transaction details to API and controllers. No BMS `JsonApi` class
belongs in the service bundle.

Java cannot use `interface` as a package name, so the interface role is named
`contracts`. For example, email code is grouped as `email/contracts`,
`email/model` (when needed), and `email/implement`; the same pattern applies
to discharge, network, and the other BMS features. Scheduled controllers,
physical drivers, and JSON endpoints stay in their separate bundles.

`VoltageSocCurve`, `CapacityTemperatureFactor` and `EstimatorMode` live in
`service/calculation/model` because both business services and scheduled
controllers use them. This keeps the production dependency one-way:
`controller.bms` and `bms.api` depend on `bms.service`, never the reverse.

The BMS database component keeps its historical ConfigAdmin PID and fields for
existing deployments. It now asks the shared PostgreSQL bundle to create the
pool; alternatively, a shared connection configured with `id=bms` takes
precedence. Each other app can configure its own shared connection with a
distinct ID and database credentials. `BmsSchema` and
`FeatureSchemaMigration` remain BMS-owned because a generic connection must
not create BMS tables or change another app's schema.

## Isolated BMS runtime

From the `Integrate-MaxiEMS` root, run `bash ./gradlew resolve.BMSApp` and
`bash ./gradlew export.BMSApp` as separate commands. The executable is
`platform/applications/io.openems.edge.application/generated/distributions/executable/BMSApp.jar`.
Start it with an **absolute** `-Dfelix.cm.dir` pointing to a dedicated BMS
ConfigAdmin directory and a separate `-Dopenems.data.dir`. Configure the BMS
PostgreSQL service for a disposable database when testing; never use the
operational `maxiems` database for schema fixtures. The frontend can target a
non-default WebSocket port with `VITE_BMS_WS_PORT` (default `8085`).

The original source/test migration was checked against
`maxiems/io.openems.edge.bms`: all 217 Java files have corresponding code in
`apps/bms`. The serial bridge repository is adapted to the shared service
interface, and the serial JSON endpoint binds that interface. Package names
changed for the split-bundle layout; business calculations and database
queries were not intentionally changed by this migration.
