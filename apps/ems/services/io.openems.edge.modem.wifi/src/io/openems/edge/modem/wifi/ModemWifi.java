package io.openems.edge.modem.wifi;

import io.openems.common.types.OpenemsType;
import org.osgi.service.event.EventHandler;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface ModemWifi extends OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		WIFI_LIST(Doc.of(OpenemsType.STRING).text("List Wifi")),
		STATUS(Doc.of(OpenemsType.STRING).text("Wifi Status")),
		STATUS_CONNECT(Doc.of(OpenemsType.STRING).text("Status Connect Wifi"))
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

	public class WifiInfo {
		private String ssid;
		private int signal;
		private String security;

		public WifiInfo(String ssid, int signal, String security) {
			this.ssid = ssid;
			this.signal = signal;
			this.security = security;
		}

		public String getSsid() {
			return this.ssid;
		}

		public void setSsid(String ssid) {
			this.ssid = ssid;
		}

		public int getSignal() {
			return this.signal;
		}

		public void setSignal(int signal) {
			this.signal = signal;
		}

		public String getSecurity() {
			return this.security;
		}

		public void setSecurity(String security) {
			this.security = security;
		}

		/**
		 * Returns a string representation of this WifiInfo.
		 *
		 * @return the formatted WifiInfo string
		 */
		@Override
		public String toString() {
			return "WifiInfo{"
					+ "ssid='" + this.ssid + '\''
					+ ", signal=" + this.signal
					+ ", security='" + this.security + '\''
					+ '}';
		}
	}

}
