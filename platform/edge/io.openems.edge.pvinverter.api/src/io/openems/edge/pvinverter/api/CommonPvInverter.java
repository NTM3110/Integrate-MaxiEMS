package io.openems.edge.pvinverter.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.*;
import io.openems.edge.common.component.OpenemsComponent;

public interface CommonPvInverter extends OpenemsComponent {
    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        RATED_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).persistencePriority(PersistencePriority.MEDIUM)),
        TEMPERATURE(Doc.of(OpenemsType.INTEGER).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.MEDIUM)),

        ;

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
