package io.openems.edge.bridge.dlms.api.element;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.type.TypeUtils;

/**
 * A concrete implementation of {@link DlmsElement} for OBIS codes.
 */
public class ObisElement extends AbstractDlmsElement<Object> {

	public ObisElement(OpenemsType type, String obis, int attributeIndex) {
		super(type, obis, attributeIndex);
	}

	@Override
	protected Object convertToType(Object value) {
		return TypeUtils.getAsType(this.type, value);
	}
}
