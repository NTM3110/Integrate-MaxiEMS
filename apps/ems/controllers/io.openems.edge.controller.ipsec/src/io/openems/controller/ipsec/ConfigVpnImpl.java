package io.openems.controller.ipsec;

import io.openems.common.exceptions.OpenemsException;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import org.osgi.service.component.annotations.Reference;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.VPN.EVN", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ConfigVpnImpl extends AbstractOpenemsComponent implements ConfigVpn, Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ConfigVpnImpl.class);
	private Config config = null;
	private String activeWanInterface = "eth0";
	private java.util.concurrent.ScheduledExecutorService executor;

	@Reference(policy = org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC, policyOption = org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY, cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL, target = "(id=network0)")
	protected volatile OpenemsComponent networkManager;

	public ConfigVpnImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ConfigVpn.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		this.logInfo(this.log, "--- Activating VPN Controller [" + config.id() + "] ---");
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		if (config.enabled()) {
			this.applyIpsecConfiguration();
			this.executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
			this.executor.scheduleWithFixedDelay(() -> this.checkIpsecStatus(), 60, 60, java.util.concurrent.TimeUnit.SECONDS);
		} else {
			this.logInfo(this.log, "VPN Controller is DISABLED in configuration.");
		}
	}

	private void applyIpsecConfiguration() {
		this.logInfo(this.log, ">>> Starting IPsec expert configuration application...");
		try {
			this.activeWanInterface = this.detectInternetInterface();
			this.logInfo(this.log, "Using WAN Interface: " + this.activeWanInterface);
			
			if (this.validateConfig()) {
				if (this.isConfigurationUnchangedAndRunning()) {
					this.logInfo(this.log, "IPsec configuration is unchanged, network rules exist, and StrongSwan is running. Skipping re-application.");
					return;
				}

				this.logInfo(this.log, "Configuration validated. Writing files...");
				this.writeIpsecConf();
				this.writeIpsecSecrets();
				
				this.logInfo(this.log, "Setting up network with nmcli/iptables...");
				this.setupNetwork();
				
				this.logInfo(this.log, "Restarting IPsec services...");
				this.restartIpsec();
				
				this.logInfo(this.log, "SUCCESS: IPsec expert configuration applied successfully.");
			} else {
				this.logError(this.log, "Configuration validation FAILED. Check your parameters.");
			}
		} catch (Exception e) {
			this.logError(this.log, "CRITICAL ERROR applying IPsec configuration: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private boolean validateConfig() {
		boolean isValid = true;
		
		if (!this.isValidIpv4Cidr(this.config.localSubnet())) {
			this.logWarn(this.log, "Invalid Local Subnet format: " + this.config.localSubnet());
			isValid = false;
		}
		if (!this.isValidIpv4Cidr(this.config.remoteSubnet())) {
			this.logWarn(this.log, "Invalid Remote Subnet format: " + this.config.remoteSubnet());
			isValid = false;
		}

		// Check for subnet overlap
		if (this.config.localSubnet().equals(this.config.remoteSubnet())) {
			this.logError(this.log, "Local Subnet and Remote Subnet MUST NOT be identical!");
			isValid = false;
		}
		
		return isValid;
	}

	private String generateIpsecConfContent() {
		StringBuilder sb = new StringBuilder();
		sb.append("config setup\n");
		sb.append("    charondebug=\"ike 2, knl 2, cfg 2, esp 2\"\n");
		sb.append("    uniqueids=no\n\n");

		sb.append("conn EVN_VPN\n");
		sb.append("    auto=start\n");
		sb.append("    keyexchange=ikev2\n");
		sb.append("    type=tunnel\n");
		sb.append("    authby=psk\n");
		sb.append("    installpolicy=yes\n");
		sb.append("    leftfirewall=no\n");

		// Phase 1 (IKE) - Hardcoded defaults for EVN
		sb.append("    ike=aes256-sha256-modp2048!\n");
		sb.append("    ikelifetime=28800s\n");
		sb.append("    rekeymargin=360s\n");

		// Phase 2 (ESP) - Hardcoded defaults for EVN
		sb.append("    esp=aes256-sha256-modp2048!\n");
		sb.append("    lifetime=21600s\n");

		// Identifiers
		sb.append("    left=%defaultroute\n");
		if (this.config.natLeft()) {
			sb.append("    leftid=").append(this.config.localId()).append("\n");
		} else {
			sb.append("    leftid=%any\n");
		}
		sb.append("    leftsubnet=").append(this.config.localSubnet()).append("\n");

		sb.append("    right=").append(this.config.remotePublicIp()).append("\n");
		sb.append("    rightid=").append(this.config.remoteId().isEmpty() ? this.config.remotePublicIp() : this.config.remoteId())
				.append("\n");
		sb.append("    rightsubnet=").append(this.config.remoteSubnet()).append("\n");

		// DPD (Dead Peer Detection) - Hardcoded defaults
		sb.append("    dpddelay=30s\n");
		sb.append("    dpdtimeout=60s\n");
		sb.append("    dpdaction=restart\n");
		sb.append("    closeaction=restart\n");

		sb.append("    keyingtries=%forever\n");
		return sb.toString();
	}

	private void writeIpsecConf() throws IOException {
		String tmpPath = "/tmp/ipsec.conf.tmp";
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(tmpPath))) {
			writer.write(this.generateIpsecConfContent());
		}
		this.executeCommand("sudo mv " + tmpPath + " /etc/ipsec.conf");
	}

	private String generateIpsecSecretsContent() {
		StringBuilder sb = new StringBuilder();
		
		// StrongSwan secrets format: [LocalID] [RemoteID] : PSK "password"
		String localId = this.config.localId().isEmpty() ? "%any" : this.config.localId();
		String remoteId = this.config.remoteId().isEmpty() ? this.config.remotePublicIp() : this.config.remoteId();
		
		sb.append(localId).append(" ").append(remoteId).append(" : PSK \"").append(this.config.psk()).append("\"\n");
		return sb.toString();
	}

	private void writeIpsecSecrets() throws IOException {
		java.io.File tmpFile = java.io.File.createTempFile("ipsec_secrets_", ".tmp");
		// Ensure file can't be read by malicious software on local OS
		tmpFile.setReadable(false, false);
		tmpFile.setWritable(false, false);
		tmpFile.setExecutable(false, false);
		tmpFile.setReadable(true, true);
		tmpFile.setWritable(true, true);

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(tmpFile))) {
			writer.write(this.generateIpsecSecretsContent());
		}
		
		this.executeCommand("sudo mv " + tmpFile.getAbsolutePath() + " /etc/ipsec.secrets");
		this.executeCommand("sudo chmod 600 /etc/ipsec.secrets");
	}

	private boolean isConfigurationUnchangedAndRunning() {
		try {
			// Read current /etc/ipsec.conf
			String currentConf = this.executeSystemCommand("sudo cat /etc/ipsec.conf");
			String newConf = this.generateIpsecConfContent();
			if (!newConf.trim().equals(currentConf.trim())) {
				this.logInfo(this.log, "IPsec configuration has changed.");
				return false;
			}
			
			String currentSecrets = this.executeSystemCommand("sudo cat /etc/ipsec.secrets");
			String newSecrets = this.generateIpsecSecretsContent();
			if (!newSecrets.trim().equals(currentSecrets.trim())) {
				this.logInfo(this.log, "IPsec secrets have changed.");
				return false;
			}
			
			// Check if service is active
			String sysctlStatus = this.executeSystemCommand("systemctl is-active strongswan-starter");
			if (!sysctlStatus.trim().equals("active")) {
				this.logInfo(this.log, "StrongSwan service is not active.");
				return false;
			}
			
			// Check iptables chain exists
			String iptablesCheck = this.executeSystemCommand("sudo iptables -L IPSEC_FORWARD -n 2>&1");
			if (iptablesCheck.contains("iptables: No chain") || iptablesCheck.contains("No chain/target")) {
				this.logInfo(this.log, "IPTables chain IPSEC_FORWARD is missing.");
				return false;
			}

			// Check route
			String routeCheck = this.executeSystemCommand("ip route show " + this.config.remoteSubnet());
			if (routeCheck.trim().isEmpty()) {
				this.logInfo(this.log, "Route for remote subnet is missing.");
				return false;
			}

			return true;
		} catch (Exception e) {
			this.logWarn(this.log, "Exception while checking current configuration: " + e.getMessage());
			return false;
		}
	}

	private void setupNetwork() {
		// 0. Tắt RP Filter (Reverse Path Filtering) để kernel không drop gói tin trả về từ VPN
		this.logInfo(this.log, "Disabling RP Filter to allow asymmetric routing...");
		this.executeCommand("sysctl -w net.ipv4.conf.all.rp_filter=0");
		this.executeCommand("sysctl -w net.ipv4.conf.default.rp_filter=0");
		this.executeCommand("sysctl -w net.ipv4.conf." + this.activeWanInterface + ".rp_filter=0");
		
		// 1. Bật Forwarding (Rất quan trọng để route traffic qua Gateway)
		this.logInfo(this.log, "Enabling IPv4 Forwarding...");
		this.executeCommand("sysctl -w net.ipv4.ip_forward=1");
		// Đảm bảo ghi vào file để tồn tại sau reboot (tùy chọn nhưng an toàn hơn)
		this.executeCommand("grep -qxF 'net.ipv4.ip_forward=1' /etc/sysctl.conf || echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.conf");

		// 2. Cấu hình IPTables cho IPsec (Policy-Based / XFRM)
		this.logInfo(this.log, "Configuring IPTables rules for Site-to-Site VPN...");
		
		String localSubnet = this.config.localSubnet();
		String remoteSubnet = this.config.remoteSubnet();

		// Tạo chain FORWARD riêng cho IPsec để dễ quản lý và dọn dẹp
		this.executeCommand("sudo iptables -N IPSEC_FORWARD 2>/dev/null || true");
		this.executeCommand("sudo iptables -F IPSEC_FORWARD");
		// Chèn vào đầu chuỗi FORWARD nếu chưa có
		this.executeCommand("sudo iptables -D FORWARD -j IPSEC_FORWARD 2>/dev/null || true");
		this.executeCommand("sudo iptables -I FORWARD -j IPSEC_FORWARD");

		// Cho phép traffic đi qua (Forward) giữa hai subnet
		this.executeCommand("sudo iptables -A IPSEC_FORWARD -s " + localSubnet + " -d " + remoteSubnet + " -j ACCEPT");
		this.executeCommand("sudo iptables -A IPSEC_FORWARD -s " + remoteSubnet + " -d " + localSubnet + " -j ACCEPT");
		this.executeCommand("sudo iptables -A IPSEC_FORWARD -m state --state RELATED,ESTABLISHED -j ACCEPT");

		// 3. Cấu hình NAT Masquerade (Tránh NAT traffic của VPN) - Hardcoded ENABLED
		if (true) {
			this.logInfo(this.log, "Configuring NAT Masquerade on WAN interface: " + this.activeWanInterface);
			
			// Tạo chain NAT riêng
			this.executeCommand("sudo iptables -t nat -N IPSEC_NAT 2>/dev/null || true");
			this.executeCommand("sudo iptables -t nat -F IPSEC_NAT");
			
			// Gắn vào POSTROUTING nếu chưa có
			this.executeCommand("sudo iptables -t nat -D POSTROUTING -j IPSEC_NAT 2>/dev/null || true");
			this.executeCommand("sudo iptables -t nat -I POSTROUTING -j IPSEC_NAT");
			
			// QUAN TRỌNG: EXCLUDE (Không NAT) traffic đã match IPsec policy
			this.executeCommand("sudo iptables -t nat -A IPSEC_NAT -m policy --pol ipsec --dir out -j ACCEPT");
			
			// EXCLUDE (Không NAT) traffic tới Remote Subnet của VPN (Dự phòng)
			this.executeCommand("sudo iptables -t nat -A IPSEC_NAT -d " + remoteSubnet + " -j RETURN");
			
			// MASQUERADE các traffic còn lại khi đi ra WAN
			this.executeCommand("sudo iptables -t nat -A IPSEC_NAT -o " + this.activeWanInterface + " -j MASQUERADE");
		}

		// 4. Cấu hình Routes
		this.setupRoutes();
	}

	private String detectInternetInterface() {
		// Detect interface to use for MASQUERADE rules
		// Ưu tiên các interface thường dùng
		String[] candidates = { "eth0", "eth1","p2p0","wlan0", "usb0", "ppp0" }; 
		for (String iface : candidates) {
			try {
				// Kiểm tra file định danh interface tồn tại trong /sys/class/net
				Process checkExist = Runtime.getRuntime().exec(new String[] { "/bin/sh", "-c", "ls /sys/class/net/" + iface });
				if (checkExist.waitFor() != 0) {
					continue;
				}

				this.logInfo(this.log, "Checking internet access on " + iface + "...");
				// Ping test với timeout ngắn và bind vào interface (Dùng sudo cho chắc chắn)
				Process p = Runtime.getRuntime().exec(new String[] { "/bin/sh", "-c", "sudo ping -c 1 -W 2 -I " + iface + " 8.8.8.8" });
				if (p.waitFor() == 0) {
					this.logInfo(this.log, ">>> SUCCESS: Internet detected on " + iface);
					return iface;
				}
			} catch (Exception e) {
				// Bỏ qua và thử candidate tiếp theo
			}
		}

		this.logWarn(this.log, "No internet detected on any candidate interface. Defaulting to eth0.");
		return "eth0";
	}

	private void setupRoutes() {
		String remoteSubnet = this.config.remoteSubnet();
		String localSubnet = this.config.localSubnet();
		
		// Logic: Use IP calculated from Local Subnet 
		String localVpnIp = this.calculateFirstUsableIp(localSubnet);
		int localPrefix = this.getPrefixLength(localSubnet);

		this.logInfo(this.log, "Configuring Source Routing...");
		
		// Ensure IP exists on interface via 'ip addr add' to prevent "Invalid prefsrc"
		this.logInfo(this.log, "Ensuring Source IP " + localVpnIp + " exists on " + this.activeWanInterface);
		this.executeCommand("sudo ip addr add " + localVpnIp + "/" + localPrefix + " dev " + this.activeWanInterface + " 2>/dev/null || true");

		this.logInfo(this.log, "Routing " + remoteSubnet + " via " + this.activeWanInterface + " with Source IP: " + localVpnIp);
		
		// Lệnh này đảm bảo gói tin mang đúng "danh tính" IP nguồn khi đi vào hầm
		String cmd = "sudo ip route replace " + remoteSubnet + " dev " + this.activeWanInterface + " src " + localVpnIp;
		this.executeCommand(cmd);
	}

	private void restartIpsec() {
		this.logInfo(this.log, "Restarting StrongSwan service...");
		this.executeCommand("sudo systemctl restart strongswan-starter");
		
		try {
			// Chờ dịch vụ khởi động hoàn toàn
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			/* ignore */
		}

		String name = "EVN_VPN";
		this.logInfo(this.log, "Executing tunnel Down/Up for: " + name);
		this.executeCommand("sudo ipsec down " + name);
		this.executeCommand("sudo ipsec up " + name);
	}

	private void executeCommand(String command) {
		this.executeCommandWithResult(command);
	}

	private boolean executeCommandWithResult(String command) {
		try {
			this.logInfo(this.log, "Executing command: " + command);
			ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			
			String output = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))
					.lines().collect(Collectors.joining("\n"));
					
			int exitCode = p.waitFor();
			if (exitCode != 0) {
				this.logError(this.log, "Command FAILED (Exit " + exitCode + "): " + command + "\nOutput: " + output);
				return false;
			}
			return true;
		} catch (Exception e) {
			this.logError(this.log, "Command execution EXCEPTION: " + command + " Error: " + e.getMessage());
			return false;
		}
	}

	@Deactivate
	protected void deactivate() {
		this.logInfo(this.log, "--- Deactivating VPN Controller & Cleaning System ---");

		if (this.executor != null) {
			this.executor.shutdownNow();
		}
		
		if (this.config != null) {
			String remoteSubnet = this.config.remoteSubnet();
			
			// 1. Xóa Route tĩnh
			this.executeCommand("sudo ip route del " + remoteSubnet + " 2>/dev/null || true");

			// Xóa IP Source đã gán (nếu có)
			String localVpnIp = this.calculateFirstUsableIp(this.config.localSubnet());
			int localPrefix = this.getPrefixLength(this.config.localSubnet());
			if (localVpnIp != null && !localVpnIp.isEmpty()) {
				this.executeCommand("sudo ip addr del " + localVpnIp + "/" + localPrefix + " dev " + this.activeWanInterface + " 2>/dev/null || true");
			}

			// 2. Gỡ bỏ IPTables (Dỡ lệnh nhảy trước khi xóa Chain)
			this.executeCommand("sudo iptables -D FORWARD -j IPSEC_FORWARD 2>/dev/null || true");
			this.executeCommand("sudo iptables -t nat -D POSTROUTING -j IPSEC_NAT 2>/dev/null || true");
			
			this.executeCommand("sudo iptables -F IPSEC_FORWARD && sudo iptables -X IPSEC_FORWARD 2>/dev/null || true");
			this.executeCommand("sudo iptables -t nat -F IPSEC_NAT && sudo iptables -t nat -X IPSEC_NAT 2>/dev/null || true");
		}

		// 3. Dừng hầm VPN
		this.executeCommand("sudo ipsec down EVN_VPN");

		// 4. Khôi phục RP Filter về mặc định (An toàn cho hệ thống)
		this.executeCommand("sudo sysctl -w net.ipv4.conf.all.rp_filter=2");
		
		super.deactivate();
	}

	private long lastStatusCheck = 0;

	@Override
	public void run() {
		if (!this.config.enabled()) {
			return;
		}

		try {
			// 1. Kiểm tra trạng thái dịch vụ IPsec (Phase 1 & 2)
			boolean ipsecHealthy = this.checkIpsecStatus();
			String ping = "10.21.10.65";

			// 2. Kiểm tra kết nối thực tế bằng Ping (chỉ khi IPsec báo ổn)
			boolean pingHealthy = false;
			if (ipsecHealthy) {
				pingHealthy = this.checkPingStatus(ping);
			}

			// 3. Logic xử lý: Nếu hỏng 1 trong 2 thì thiết lập lại
			if (!ipsecHealthy || !pingHealthy) {
				this.logWarn(this.log, "VPN link is down (IPsec: " + ipsecHealthy + ", Ping: " + pingHealthy + "). Re-applying configuration...");
				this.applyIpsecConfiguration();
				// Sau khi apply có thể cần restart service
				this.executeSystemCommand("sudo ipsec restart");
			}

		} catch (Exception e) {
			this.logError(this.log, "Error in VPN control loop: " + e.getMessage());
		}
	}

	private void reconnectVpn() {
		this.logInfo(this.log, "Restarting IPsec service...");
		// Tùy vào OS, bạn có thể dùng 'ipsec restart' hoặc 'systemctl restart strongswan'
		this.executeSystemCommand("sudo ipsec restart");

		// Đợi một chút để service khởi động lại trước khi apply cấu hình mới nếu cần
		try {
			Thread.sleep(5000);
		} catch (InterruptedException ignored) {
			this.logInfo(this.log,"Loi roi may oi");
		}

		this.applyIpsecConfiguration();
	}

	private boolean checkPingStatus(String ipAddress) {
		if (ipAddress == null || ipAddress.isEmpty()) {
			return false;
		}
		try {
			// Ping 3 gói tin, chờ tối đa 2 giây mỗi gói
			String cmd = String.format("ping -c 3 -W 2 %s", ipAddress);
			String output = this.executeSystemCommand(cmd);

			// Nếu lệnh ping trả về exit code 0, thường executeSystemCommand sẽ không ném lỗi
			// Hoặc bạn có thể check chữ "64 bytes from" trong output
			boolean success = output.contains("64 bytes from");

			this.channel(ConfigVpn.ChannelId.PING_STATUS).setNextValue(success);
			return success;
		} catch (Exception e) {
			this.logWarn(this.log, "Ping failed to " + ipAddress);
			this.channel(ConfigVpn.ChannelId.PING_STATUS).setNextValue(false);
			return false;
		}
	}

	private String calculateFirstUsableIp(String cidr) {
		try {
			String[] parts = cidr.split("/");
			String[] octets = parts[0].split("\\.");
			long ipNum = 0;
			for (int i = 0; i < 4; i++) {
				int octet = Integer.parseInt(octets[i]);
				if (octet < 0 || octet > 255) {
					throw new IllegalArgumentException("IPv4 octet out of range: " + octet);
				}
				ipNum = (ipNum << 8) | octet;
			}
			int prefix = Integer.parseInt(parts[1]);
			if (prefix < 0 || prefix > 32) {
				throw new IllegalArgumentException("CIDR prefix out of range: " + prefix);
			}
			long mask = prefix == 0 ? 0 : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
			ipNum = ipNum & mask;
			return String.format("%d.%d.%d.%d", (ipNum >> 24) & 0xFF, (ipNum >> 16) & 0xFF, (ipNum >> 8) & 0xFF, ipNum & 0xFF);
		} catch (Exception e) {
			this.logWarn(this.log, "Failed to calculate IP for " + cidr + ": " + e.getMessage());
			return cidr.split("/")[0];
		}
	}


	private int getPrefixLength(String cidr) {
		try {
			String[] parts = cidr.split("/");
			int prefix = Integer.parseInt(parts[1]);
			if (prefix < 0 || prefix > 32) {
				return 32;
			}
			return prefix;
		} catch (Exception e) {
			return 32;
		}
	}

	private boolean isValidIpv4Cidr(String cidr) {
		if (cidr == null || cidr.isBlank()) {
			return false;
		}
		String[] parts = cidr.split("/");
		if (parts.length != 2) {
			return false;
		}
		String[] octets = parts[0].split("\\.");
		if (octets.length != 4) {
			return false;
		}
		try {
			for (String octetStr : octets) {
				int octet = Integer.parseInt(octetStr);
				if (octet < 0 || octet > 255) {
					return false;
				}
			}
			int prefix = Integer.parseInt(parts[1]);
			return prefix >= 0 && prefix <= 32;
		} catch (NumberFormatException e) {
			return false;
		}
	}



	private boolean checkIpsecStatus() {
		try {
			String statusOutput = this.executeSystemCommand("sudo ipsec statusall");

			// Parse Phase 1
			String p1Status = "DOWN";
			if (statusOutput.contains("ESTABLISHED")) {
				p1Status = "ESTABLISHED";
			} else if (statusOutput.contains("Connecting")) {
				p1Status = "CONNECTING";
			}
			this.channel(ConfigVpn.ChannelId.PHASE1_STATUS).setNextValue(p1Status);

			// Parse Phase 2
			String p2Status = "DOWN";
			if (statusOutput.contains("INSTALLED")) {
				p2Status = "INSTALLED";
			}
			this.channel(ConfigVpn.ChannelId.PHASE2_STATUS).setNextValue(p2Status);

			// Phân tích lỗi nếu DOWN
			if ("DOWN".equals(p1Status) || "DOWN".equals(p2Status)) {
				String logOutput = this.executeSystemCommand("sudo journalctl -u strongswan-starter -n 50 --no-pager");
				String error = "UNKNOWN";
				if (logOutput.contains("AUTHENTICATION_FAILED")) {
					error = "AUTH_FAILED";
				} else if (logOutput.contains("NO_PROPOSAL_CHOSEN")) {
					error = "NO_PROPOSAL";
				} else if (logOutput.contains("retransmit") || logOutput.contains("timeout")) {
					error = "NETWORK_UNREACHABLE";
				}

				this.channel(ConfigVpn.ChannelId.VPN_ERROR_DETAILS).setNextValue(error);
				return false; // Trạng thái không ổn định
			}

			this.channel(ConfigVpn.ChannelId.VPN_ERROR_DETAILS).setNextValue("NONE");
			return true; // Mọi thứ đều ổn

		} catch (Exception e) {
			this.logWarn(this.log, "Failed to check IPsec status: " + e.getMessage());
			return false;
		}
	}


	private String executeSystemCommand(String command) {
		StringBuilder output = new StringBuilder();
		try {
			ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			
			// Always consume streams continuously before waiting to prevent blocking I/O
			try (java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.append(line).append("\n");
				}
			}
			
			// Wait with timeout to prevent hanging forever
			if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
				p.destroy();
				return output.toString() + "\nTimeout executing command: " + command;
			}
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
		return output.toString();
	}
}
