package io.openems.edge.bms.service.persistence.implement;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * BMS access point for PostgreSQL connections and BMS schema readiness.
 *
 * <p>Runtime OpenEMS component configuration deliberately does not use this
 * database. It remains in ConfigurationAdmin.</p>
 */
public interface BmsDatabase {

	Connection getConnection() throws SQLException;

	boolean isAvailable();
}
