package io.openems.edge.shared.postgresql.internal;

import org.osgi.service.component.annotations.Component;
import org.postgresql.Driver;
import org.postgresql.ds.PGSimpleDataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.openems.edge.shared.postgresql.PostgresConnection;
import io.openems.edge.shared.postgresql.PostgresConnectionFactory;
import io.openems.edge.shared.postgresql.PostgresConnectionSettings;

@Component(service = PostgresConnectionFactory.class)
public class PoolFactory implements PostgresConnectionFactory {

	@Override
	public PostgresConnection create(PostgresConnectionSettings settings) {
		try {
			if (!Driver.isRegistered()) {
				Driver.register();
			}
		} catch (java.sql.SQLException e) {
			throw new IllegalStateException("Unable to register PostgreSQL JDBC driver", e);
		}
		var source = new PGSimpleDataSource();
		source.setServerNames(new String[] { settings.host() });
		source.setPortNumbers(new int[] { settings.port() });
		source.setDatabaseName(settings.database());
		source.setUser(settings.user());
		if (!settings.password().isBlank()) {
			source.setPassword(settings.password());
		}
		var pool = new HikariDataSource();
		pool.setDataSource(source);
		pool.setMaximumPoolSize(settings.poolSize());
		pool.setPoolName("postgres-" + settings.id());
		return new PooledConnection(pool);
	}

	private record PooledConnection(HikariDataSource pool) implements PostgresConnection {
		@Override
		public java.sql.Connection getConnection() throws java.sql.SQLException {
			return this.pool.getConnection();
		}

		@Override
		public boolean isAvailable() {
			return !this.pool.isClosed();
		}

		@Override
		public void close() {
			this.pool.close();
		}
	}
}
