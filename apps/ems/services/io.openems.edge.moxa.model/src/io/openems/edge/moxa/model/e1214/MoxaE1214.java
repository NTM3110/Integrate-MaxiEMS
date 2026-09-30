package io.openems.edge.moxa.model.e1214;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface MoxaE1214 extends OpenemsComponent {

    enum ChannelId implements io.openems.edge.common.channel.ChannelId {
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

