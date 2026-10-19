package io.openems.edge.bms.bridge.modbus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.CompletedBatch;
import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.Outcome;

public class BridgeBmsModbusSerialImplTest {
	@Test
	public void markerIsVisibleOnlyAfterTheFollowingProcessImageSwitch() {
		var bridge = new BridgeBmsModbusSerialImpl();
		var batch = new CompletedBatch("generation", 1, 1000, 2000, Map.of("ta1", Outcome.SUCCESS));
		bridge.beforeProcessImage();
		bridge.acceptCompletedBatch(batch);
		bridge.afterProcessImage();
		assertNull(bridge.getCompletedBatch());
		assertFalse(bridge.acknowledgeProcessed("generation", 1));
		bridge.beforeProcessImage();
		bridge.afterProcessImage();
		assertEquals(batch, bridge.getCompletedBatch());
		assertFalse(bridge.acknowledgeProcessed("other", 1));
		assertTrue(bridge.acknowledgeProcessed("generation", 1));
	}
}
