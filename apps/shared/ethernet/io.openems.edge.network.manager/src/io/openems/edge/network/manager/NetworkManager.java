package io.openems.edge.network.manager;

import io.openems.common.channel.AccessMode;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface NetworkManager extends OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        // 3 Channel hiển thị IP riêng biệt
        LAN1_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of eth0")),
        LAN1_GATEWAY(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("Gateway of eth0")),
        LAN2_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of eth1")),
        LAN2_GATEWAY(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("Gateway of eth1")),
        MOBILE_IP(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("IP of 4G")),
        MOBILE_GATEWAY(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY).text("Gateway of 4G")),

	        LAST_ERROR_MESSAGE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY)),
	        CURRENT_OPERATION_ID(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY)),
	        OPERATION_SEQUENCE(Doc.of(OpenemsType.LONG).accessMode(AccessMode.READ_ONLY)),
	        OPERATION_STATE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY)),
	        OPERATION_PROGRESS(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),
	        OPERATION_INTERFACE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY)),
	        OPERATION_ERROR_CODE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY)),
	        CONFIRMATION_DEADLINE(Doc.of(OpenemsType.STRING).accessMode(AccessMode.READ_ONLY));

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
