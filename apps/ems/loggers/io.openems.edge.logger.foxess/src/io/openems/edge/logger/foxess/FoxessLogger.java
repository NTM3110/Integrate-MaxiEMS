package io.openems.edge.logger.foxess;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.logger.api.SmartLogger;

public interface FoxessLogger extends SmartLogger, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DC1_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),
		DC2_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),
		DC_TOTAL(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT).persistencePriority(PersistencePriority.HIGH)),
		S_N(Doc.of(OpenemsType.STRING).text("Serial Number.").persistencePriority(PersistencePriority.HIGH))//
		;

		private final Doc doc;

		ChannelId(final Doc doc) {
			this.doc = doc;
		}

		/**
		 * Accessor method for the {@link Doc} instance of this channel.
		 *
		 * @return the Doc instance
		 * @see ChannelId#doc()
		 */
		@Override
		public Doc doc() {
			return this.doc;
		}
	}









}
