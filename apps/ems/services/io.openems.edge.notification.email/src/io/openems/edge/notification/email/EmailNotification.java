package io.openems.edge.notification.email;

import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface EmailNotification extends EmailNotificationService, OpenemsComponent {
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		QUEUE_SIZE(Doc.of(INTEGER)),
		QUEUE_FULL(Doc.of(Level.WARNING).persistencePriority(HIGH)),
		SEND_FAILED(Doc.of(Level.WARNING).persistencePriority(HIGH)),
		SENT_EMAIL_COUNT(Doc.of(LONG).persistencePriority(HIGH)),
		FAILED_EMAIL_COUNT(Doc.of(LONG).persistencePriority(HIGH)),
		LAST_SEND_TIMESTAMP(Doc.of(STRING)),
		LAST_ERROR(Doc.of(STRING));

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
