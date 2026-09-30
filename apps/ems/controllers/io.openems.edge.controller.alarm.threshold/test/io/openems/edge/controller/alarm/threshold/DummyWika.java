package io.openems.edge.controller.alarm.threshold;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.wika.api.WikaAms01Alarm;
import io.openems.edge.wika.api.WikaManagement;

public class DummyWika extends AbstractOpenemsComponent implements WikaAms01Alarm {
	public DummyWika(String id, String alias) {
		super(OpenemsComponent.ChannelId.values(), WikaManagement.ChannelId.values(),
				WikaAms01Alarm.ChannelId.values());
		super.activate(null, id, alias, true);
	}
}
