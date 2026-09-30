package io.openems.edge.controller.alarm.threshold;

import static io.openems.common.types.OpenemsType.INTEGER;

import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerAlarmThreshold extends Controller, OpenemsComponent {
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ACTIVE_ALARM_COUNT(Doc.of(INTEGER)),
		PENDING_TRANSITION_COUNT(Doc.of(INTEGER)),
		NO_WIKA_FOUND(Doc.of(Level.WARNING)),
		INVALID_ALARM_VALUE(Doc.of(Level.WARNING)),
		EMAIL_ENQUEUE_FAILED(Doc.of(Level.WARNING));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
