package io.openems.edge.shared.postgresql;

import java.sql.Connection;
import java.sql.SQLException;

/** A separately owned PostgreSQL pool; callers never manage the JDBC driver. */
public interface PostgresConnection extends AutoCloseable {

	Connection getConnection() throws SQLException;

	/** Pool lifecycle status, not an active network/database health check. */
	boolean isAvailable();

	@Override
	void close();
}
