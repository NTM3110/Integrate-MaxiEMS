# MaxiEMS BMS API and Data Contract

This document is the implementation contract for the PostgreSQL-backed BMS services.

## Ownership

* PostgreSQL owns user accounts, site metadata, string business data, thresholds, assets, discharge data, audit data and history.
* OpenEMS ConfigurationAdmin owns bridges, TA, TC and controller runtime properties.
* TA/TC channels own live values. A persistence worker copies selected values to PostgreSQL without blocking polling.

## Feature API namespaces

* `platform.*` - authentication, sessions and account operations.
* `bms.strings.*` - list, get, create, update, delete and operation status.
* `bms.dashboard.*` - dashboard snapshot, details, alarms and history.
* `bms.discharge.*` - calibration, events, samples, curves and schedules.
* `bms.assets.*` - asset records and replacement limits.
* `bms.reporting.*` - CSV/print data.
* `bms.communication.*` - serial and network configuration.
* `bms.maintenance.*` - backup, restore, reboot, Wi-Fi, IR and GPIO actions.

Every mutating request includes the opaque `token` issued by `platformAuthenticate`. Feature handlers validate the token and role before invoking their service.

## String operation lifecycle

1. Store an operation with status `PENDING` and validate the request.
2. Apply TA/TC configuration through ConfigurationAdmin.
3. Verify the expected components activate and expose channels.
4. Commit string business data and mark the operation `ACTIVE`.
5. On failure, restore the prior runtime configuration and mark the operation `FAILED`.

No complete ConfigurationAdmin property snapshot is stored in PostgreSQL.
