package io.openems.edge.bms.endpoints.serial;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.JsonObject;

public class BmsSerialBridgeJsonApiTest {
	@Test
	public void responseTimeoutRequiresAnExactJsonInteger() {
		var params = new JsonObject();
		assertNull(BmsSerialBridgeJsonApi.optionalInt(params, "responseTimeoutMs"));
		params.addProperty("responseTimeoutMs", 100);
		assertEquals(Integer.valueOf(100), BmsSerialBridgeJsonApi.optionalInt(params, "responseTimeoutMs"));
		params.addProperty("responseTimeoutMs", 100.5);
		assertThrows(IllegalArgumentException.class,
				() -> BmsSerialBridgeJsonApi.optionalInt(params, "responseTimeoutMs"));
		params.addProperty("responseTimeoutMs", "100");
		assertThrows(IllegalArgumentException.class,
				() -> BmsSerialBridgeJsonApi.optionalInt(params, "responseTimeoutMs"));
	}
}
