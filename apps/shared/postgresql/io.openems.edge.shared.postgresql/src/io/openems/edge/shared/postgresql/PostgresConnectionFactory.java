package io.openems.edge.shared.postgresql;

/** Creates independent pools for applications that own legacy configuration. */
public interface PostgresConnectionFactory {

	PostgresConnection create(PostgresConnectionSettings settings);
}
