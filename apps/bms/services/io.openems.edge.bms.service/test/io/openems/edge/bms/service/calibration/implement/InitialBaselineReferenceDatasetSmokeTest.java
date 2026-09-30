package io.openems.edge.bms.service.calibration.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.Assume;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Opt-in, read-only smoke test for an already completed operational baseline. */
public class InitialBaselineReferenceDatasetSmokeTest {

	@Test
	public void loadsApprovedBaselineWithoutMutatingTheDatabase() throws Exception {
		var url = System.getenv("BMS_REFERENCE_SMOKE_URL");
		Assume.assumeTrue("BMS_REFERENCE_SMOKE_URL is not configured", url != null && !url.isBlank());
		var source = new PGSimpleDataSource();
		source.setUrl(url);
		source.setUser(environment("BMS_REFERENCE_SMOKE_USER", "postgres"));
		source.setPassword(environment("BMS_REFERENCE_SMOKE_PASSWORD", ""));
		var database = new BmsDatabase() {
			@Override
			public Connection getConnection() throws SQLException {
				return source.getConnection();
			}

			@Override
			public boolean isAvailable() {
				return true;
			}
		};
		var service = new InitialBaselineReferenceDatasetService();
		var field = InitialBaselineReferenceDatasetService.class.getDeclaredField("database");
		field.setAccessible(true);
		field.set(service, database);

		long eventId = Long.parseLong(environment("BMS_REFERENCE_SMOKE_EVENT_ID", "42"));
		var dataset = service.loadApprovedByEventId(eventId);
		assertEquals(eventId, dataset.calibrationEventId());
		assertNotNull(dataset.curve().contentHash());
		assertEquals(dataset.cellCount(), dataset.cellCutoffs().size());
		assertEquals(dataset.curve().pointCount(), dataset.curvePoints().size());
		assertEquals(dataset.cellCount(), dataset.recoveryFloatSnapshot().cells().size());
		assertEquals(dataset.cellCount(), dataset.vocSnapshot().cells().size());
		assertTrue(dataset.evidence().keySet().containsAll(java.util.List.of("CHARGER_OUTPUT_CURRENT",
				"MIDPOINT_VOLTAGE", "RIPPLE_BEFORE_LOAD", "RIPPLE_AFTER_LOAD")));
		assertEquals(eventId, service.loadActiveByStringKey(dataset.stringKey()).orElseThrow().calibrationEventId());
	}

	private static String environment(String name, String fallback) {
		var value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}
}
