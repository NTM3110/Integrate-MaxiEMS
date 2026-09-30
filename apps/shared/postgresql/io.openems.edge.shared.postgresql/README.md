# Shared PostgreSQL connection bundle

`io.openems.edge.shared.postgresql` owns JDBC driver registration and HikariCP
connection pools only. It does not create application tables or run migrations.

Create one ConfigAdmin factory configuration with PID
`io.openems.edge.shared.postgresql.Connection` per independent connection. Each
instance needs a unique `id`, `host`, `port`, `database`, `user`, `password`, and
`poolSize`. Applications bind `PostgresConnection` by the `id` service property;
for example BMS uses `(id=bms)`. Two apps can use different databases or even
the same database through separate pools. Duplicate active IDs are rejected.

For existing BMS installations, the historical
`io.openems.edge.bms.platform.persistence.BmsDatabaseImpl` configuration is
still accepted. Its BMS adapter asks `PostgresConnectionFactory` to create a
pool unless an explicit shared connection with `id=bms` is configured. BMS
schema migration stays in the BMS bundle.
