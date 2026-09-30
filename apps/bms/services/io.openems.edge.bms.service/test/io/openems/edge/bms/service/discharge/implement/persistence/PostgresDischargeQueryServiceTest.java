package io.openems.edge.bms.service.discharge.implement.persistence;
import static org.junit.Assert.*;
import org.junit.Test;
import com.google.gson.JsonObject;
public class PostgresDischargeQueryServiceTest {
	@Test
	public void serializesNewCurrentWithEventProvenance() {
		var event = new JsonObject();
		PostgresDischargeQueryService.addCurrentFields(event, 7.5, -7.5, 7.5, "NEGATIVE", "EVENT_DIRECTION", null);

		assertEquals(7.5, event.get("current").getAsDouble(), 0.000001);
		assertEquals(-7.5, event.get("rawCurrentA").getAsDouble(), 0.000001);
		assertEquals(7.5, event.get("domainCurrentA").getAsDouble(), 0.000001);
		assertEquals("NEGATIVE", event.get("currentDirection").getAsString());
		assertEquals("EVENT_DIRECTION", event.get("currentProvenance").getAsString());
	}

	@Test
	public void serializesLegacyCurrentWithoutReinterpretingIt() {
		var sample = new JsonObject();
		sample.addProperty("sampleTime", "2026-08-07T00:00:00Z");
		PostgresDischargeQueryService.addCurrentFields(sample, -9.0, null, null, null, null, null);

		assertEquals(-9.0, sample.get("currentA").getAsDouble(), 0.000001);
		assertTrue(sample.get("rawCurrentA").isJsonNull());
		assertTrue(sample.get("domainCurrentA").isJsonNull());
		assertTrue(sample.get("currentDirection").isJsonNull());
		assertEquals("LEGACY_UNKNOWN", sample.get("currentProvenance").getAsString());
		assertTrue(sample.get("sampleValid").isJsonNull());
	}

}
