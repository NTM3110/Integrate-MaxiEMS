package io.openems.edge.shared.postgresql.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.edge.shared.postgresql.PostgresConnection;
import io.openems.edge.shared.postgresql.PostgresConnectionFactory;
import io.openems.edge.shared.postgresql.PostgresConnectionSettings;

/** One ConfigAdmin factory instance per independently configured database. */
@Component(name = "io.openems.edge.shared.postgresql.Connection", service = PostgresConnection.class,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = Config.class, factory = true)
public class ConfiguredConnection implements PostgresConnection {
	private static final ConcurrentHashMap<String, ConfiguredConnection> ACTIVE_IDS = new ConcurrentHashMap<>();

	@Reference
	private PostgresConnectionFactory factory;

	private volatile PostgresConnection connection;
	private String id;

	@Activate
	protected void activate(Config config) {
		var settings = new PostgresConnectionSettings(config.id(), config.host(), config.port(), config.database(),
				config.user(), config.password(), config.poolSize());
		if (ACTIVE_IDS.putIfAbsent(settings.id(), this) != null) {
			throw new IllegalStateException("Duplicate PostgreSQL connection ID: " + settings.id());
		}
		this.id = settings.id();
		try {
			this.connection = this.factory.create(settings);
		} catch (RuntimeException e) {
			ACTIVE_IDS.remove(this.id, this);
			this.id = null;
			throw e;
		}
	}

	@Deactivate
	protected void deactivate() {
		var current = this.connection;
		this.connection = null;
		if (this.id != null) {
			ACTIVE_IDS.remove(this.id, this);
			this.id = null;
		}
		if (current != null) {
			current.close();
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		var current = this.connection;
		if (current == null) {
			throw new SQLException("PostgreSQL connection is not active");
		}
		return current.getConnection();
	}

	@Override
	public boolean isAvailable() {
		var current = this.connection;
		return current != null && current.isAvailable();
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("ConfigAdmin owns this connection");
	}
}
