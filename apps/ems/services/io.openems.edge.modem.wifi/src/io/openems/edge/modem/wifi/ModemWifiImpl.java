package io.openems.edge.modem.wifi;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.modem.wifi.ModemWifi.WifiInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "io.openems.edge.modem.wifi", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE,
		property = { //
				"enabled=true" //
		}//
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class ModemWifiImpl extends AbstractOpenemsComponent implements ModemWifi, OpenemsComponent, EventHandler {

	private Config config = null;
	private final Logger log = LoggerFactory.getLogger(ModemWifiImpl.class);
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	public ModemWifiImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModemWifi.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		if (!"".equals(config.nameWiFi())) {
			this.executor.submit(() -> this.connectWifi(config.nameWiFi(), config.passWiFi()));
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.executor.shutdownNow();
	}

	/**
	 * Gets the configuration.
	 *
	 * @return the configuration instance
	 */
	public List<WifiInfo> scanWifi() {
		Map<String, WifiInfo> bestSignal = new HashMap<>();
		List<WifiInfo> list = new ArrayList<>();

		try {
			Process process = Runtime.getRuntime().exec("nmcli -t -f SSID,SIGNAL,SECURITY dev wifi");
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(":");
				if (parts.length >= 3) {
					String ssid = parts[0];
					int signal = Integer.parseInt(parts[1]);
					String security = parts[2];

					WifiInfo wifi = new WifiInfo(ssid, signal, security);
					bestSignal.merge(ssid, wifi,
							(oldwifi, newwifi) -> oldwifi.getSignal() >= newwifi.getSignal() ? oldwifi : newwifi);

				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		list.addAll(bestSignal.values());

		return list;
	}

	/**
	 * Connect Wi-Fi.
	 * @param nameWiFi the SSID of the Wi-Fi Connect.
	 * @param passWiFi the password of the Wi-Fi Connect.
	 */
	public void connectWifi(String nameWiFi, String passWiFi) {
		String oldWiFi = this.channel(ModemWifi.ChannelId.STATUS).toString();
		String[] parts = oldWiFi.split(":");
		if (parts.length >= 2 && "Connect".equals(parts[0])) {
			if (nameWiFi.equals(parts[1])) {
				return;
			}
		}
		try {
			ProcessBuilder pb = new ProcessBuilder("sudo", "nmcli", "dev", "wifi", "connect", nameWiFi, "password", passWiFi);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String connect;
			while ((connect = reader.readLine()) != null) {
				if (connect.contains("Secrets were required")) {
					this.channel(ModemWifi.ChannelId.STATUS_CONNECT).setNextValue("Wrong password");
				} else if (connect.contains("successfully activated")) {
					this.channel(ModemWifi.ChannelId.STATUS_CONNECT).setNextValue("Successfully Connect");
				} else if (connect.contains("could not be reserved")) {
					this.channel(ModemWifi.ChannelId.STATUS_CONNECT).setNextValue("Timeout");
				}
			}
			boolean exitcode = process.waitFor(30, TimeUnit.SECONDS);
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("WiFi Connection Failed",e);
		}
	}

	/**
	 * Gets the status Wi-Fi.
	 */
	public void statusWifi() {
		try {
			Process process = Runtime.getRuntime()
					.exec("nmcli -t -f ACTIVE,SSID,SIGNAL,SECURITY dev wifi");
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String status;
			boolean connect = false;

			while ((status = reader.readLine()) != null) {
				String[] parts = status.split(":");
				if (parts.length >= 3) {
					String active = parts[0];
					String name = parts[1];
					int signal = Integer.parseInt(parts[2]);
					String security = parts[3];
					this.logInfo(this.log,"active: " + active + ",name: " + name);
					if ("yes".equals(active)) {
						this.channel(ModemWifi.ChannelId.STATUS).setNextValue("Connect: " + name);
						connect = true;
						break;
					}
				}
			}
			if (!connect) {
				this.logInfo(this.log,"Skip while!");
				this.channel(ModemWifi.ChannelId.STATUS).setNextValue("No Connect");
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String debugLog() {
		return ("Status:" + ModemWifi.ChannelId.STATUS.toString());
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		if (event.getTopic().equals(EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE)) {
			var wifilist = this.scanWifi();
			String listWifi = wifilist.toString();
			this.channel(ModemWifi.ChannelId.WIFI_LIST).setNextValue(listWifi);
			this.statusWifi();
		}

	}
}
