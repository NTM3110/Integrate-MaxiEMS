I've added all the related tables to the TimescaleDB schema:

**Tables added:**

1. **meter_profile** - Profile readings from EDMI meters (hypertable)
2. **interval_state** - Interval availability states (hypertable)
3. **calculation_period** - Calculation periods with scenarios
4. **monthly_energy_summary** - Monthly aggregated totals
5. **monthly_calculation_breakdown** - Period breakdowns with energy values
6. **meter_status_summary** - Meter fault status tracking
7. **scenario_window** - Scenario time windows
8. **profile_reading_demo** - Demo/test profile readings (hypertable)
9. **reading_value** - Wide table for 30s interval meter readings (hypertable)
10. **profile_read_gap** - Track missed profile reads for retry

**Key features:**
- Time-series tables use hypertables for automatic partitioning
- Relational tables have proper foreign keys and indexes
- Continuous aggregates for monthly summaries
- Triggers for updated_at timestamps
- Example queries for common use cases

The schema is now at: `E:\ATEnergy\Integrate-MAXiEMS\tools\timescaledb-schema\energy-breakdown-schema.sql`

**Next steps:**
1. Create the TimescaleDB database
2. Run the schema script
3. Modify the Java controller to write to PostgreSQL/TimescaleDB

Would you like me to proceed with creating the Java connector for TimescaleDB?