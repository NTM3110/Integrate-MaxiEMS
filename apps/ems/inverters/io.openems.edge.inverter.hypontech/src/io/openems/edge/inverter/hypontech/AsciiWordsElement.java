package io.openems.edge.inverter.hypontech;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.element.AbstractMultipleWordsElement;

class AsciiWordsElement extends AbstractMultipleWordsElement<AsciiWordsElement, String> {

	AsciiWordsElement(int startAddress, int length) {
		super(OpenemsType.STRING, startAddress, length);
	}

	@Override
	protected AsciiWordsElement self() {
		return this;
	}

	@Override
	protected String byteBufferToValue(ByteBuffer buff) {
		var bytes = new byte[buff.remaining()];
		buff.get(bytes);
		return new String(bytes, StandardCharsets.US_ASCII).trim();
	}

	@Override
	protected void valueToByteBuffer(ByteBuffer buff, String value) {
		buff.put(value.getBytes(StandardCharsets.US_ASCII));
	}
}
