I've created a complete TimescaleDB integration for the energy calculator. Here's what was done:

## 1. TimescaleDB Schema
Created `tools/timescaledb-schema/energy-breakdown-schema.sql` with all tables:
- **meter_profile** - Profile readings from EDMI meters (hypertable)
- **interval_state** - Interval availability states (hypertable)
- **calculation_period** - Calculation periods with scenarios
- **monthly_energy_summary** - Monthly aggregated totals
- **monthly_calculation_breakdown** - Period breakdowns with energy values
- **meter_status_summary** - Meter fault status tracking
- **scenario_window** - Scenario time windows
- **profile_reading_demo** - Demo/test profile readings (hypertable)
- **reading_value** - Wide table for 30s interval meter readings (hypertable)
- **profile_read_gap** - Track missed profile reads for retry

## 2. Java TimescaleDB Connector
Created `TimescaleDbConnector.java` with methods:
- `writeIntervalState()` - Store interval availability
- `writeCalculationPeriod()` - Store calculation periods
- `writeMonthlySummary()` - Store monthly totals with generated keys
- `writeMonthlyBreakdown()` - Store period breakdowns with foreign keys
- `writeMeterProfile()` - Store meter profile readings

## 3. Updated Configuration
Added TimescaleDB settings to `Config.java`:
- timescaleHost, timescalePort, timescaleDatabase
- timescaleUser, timescalePassword
- enableTimescaleDb, timescaleReadOnly

## 4. Updated Controller
Modified `ControllerEnergyCalculatorImpl.java` to:
- Initialize TimescaleDB connector when enabled
- Write to both InfluxDB and TimescaleDB simultaneously
- Properly deactivate both connectors on shutdown

## Next Steps
1. Build the project: `./gradlew buildEdge`
2. Create PostgreSQL database and run schema script
3. Configure TimescaleDB in OSGi config
4. Test the integration

Would you like me to build and test this now?