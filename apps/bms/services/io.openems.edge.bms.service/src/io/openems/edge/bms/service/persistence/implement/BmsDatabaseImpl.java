package io.openems.edge.bms.service.persistence.implement;

import java.sql.Connection;
import java.sql.SQLException;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.shared.postgresql.PostgresConnection;
import io.openems.edge.shared.postgresql.PostgresConnectionFactory;
import io.openems.edge.shared.postgresql.PostgresConnectionSettings;

@Component(name = "io.openems.edge.bms.platform.persistence.BmsDatabaseImpl", service = BmsDatabase.class, immediate = true)
@Designate(ocd = Config.class)
public class BmsDatabaseImpl implements BmsDatabase {

	private final Logger log = LoggerFactory.getLogger(BmsDatabaseImpl.class);
	@Reference
	private PostgresConnectionFactory connectionFactory;

	// Optional shared factory configuration can replace the historical BMS PID.
	// The legacy PID stays supported so existing installations keep working.
	@Reference(target = "(id=bms)", cardinality = ReferenceCardinality.OPTIONAL,
			policyOption = ReferencePolicyOption.GREEDY)
	private PostgresConnection configuredConnection;

	private volatile PostgresConnection legacyConnection;
	private volatile PostgresConnection activeConnection;
	private volatile boolean available;

	@Activate
	@Modified
	protected synchronized void activate(Config config) {
		this.closeLegacyConnection();
		this.activeConnection = null;
		try {
			if (this.configuredConnection != null) {
				this.activeConnection = this.configuredConnection;
			} else {
				this.legacyConnection = this.connectionFactory.create(legacySettings(config));
				this.activeConnection = this.legacyConnection;
			}
			BmsSchema.migrate(this);
			// Keep feature tables in the same startup path as the base schema. This
			// avoids relying on a second OSGi component activation after PostgreSQL
			// has already become available.
			FeatureSchemaMigration.migrate(this);
			this.available = true;
			this.log.info("MaxiEMS BMS PostgreSQL schemas are ready.");
		} catch (Exception e) {
			this.available = false;
			this.activeConnection = null;
			this.closeLegacyConnection();
			this.log.error("MaxiEMS BMS PostgreSQL is unavailable; runtime BMS channels continue without persistence.", e);
		}
	}

	@Deactivate
	protected synchronized void deactivate() {
		this.available = false;
		this.activeConnection = null;
		this.closeLegacyConnection();
	}

	@Override
	public Connection getConnection() throws SQLException {
		var source = this.activeConnection;
		if (source == null) {
			throw new SQLException("MaxiEMS BMS PostgreSQL is not configured");
		}
		return source.getConnection();
	}

	@Override
	public boolean isAvailable() {
		return this.available;
	}

	static PostgresConnectionSettings legacySettings(Config config) {
		var user = config.pgUser();
		var password = config.pgPassword();
		// Preserve the historical local fallback, but never put it in the shared bundle.
		if ("maxiems".equals(user) && password.isBlank()) {
			user = "postgres";
			password = "admin";
		}
		return new PostgresConnectionSettings("bms", config.pgHost(), config.pgPort(), config.database(), user,
				password, config.connectionPoolSize());
	}

	private void closeLegacyConnection() {
		var source = this.legacyConnection;
		this.legacyConnection = null;
		if (source != null) {
			source.close();
		}
	}
}
