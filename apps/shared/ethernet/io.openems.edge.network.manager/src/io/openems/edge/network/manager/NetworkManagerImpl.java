package io.openems.edge.network.manager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Clock;
import java.time.Duration;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import io.openems.common.exceptions.OpenemsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.session.Role;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.EdgeKeys;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerConfig;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerStatus;
import io.openems.edge.network.manager.jsonrpc.SetNetworkManagerConfig;
import io.openems.edge.network.manager.jsonrpc.CancelNetworkManagerConfig;
import io.openems.edge.network.manager.jsonrpc.ConfirmNetworkManagerConfig;
import io.openems.edge.network.manager.jsonrpc.GetNetworkManagerOperation;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;
import io.openems.edge.network.manager.operation.NetworkOperationStore;
import io.openems.edge.network.manager.operation.NetworkConfigValidator;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkValidationException;
import io.openems.edge.network.manager.os.NmcliNetworkAdapter;
import io.openems.edge.network.manager.os.ProcessCommandRunner;
import io.openems.edge.network.manager.transaction.NetworkTransactionService;
import io.openems.edge.common.claim.Network;

import static io.openems.edge.common.claim.Network.SINGLETON_COMPONENT_ID;
import static io.openems.edge.common.claim.Network.SINGLETON_SERVICE_PID;

@Designate(ocd = Config.class, factory = false)
@Component(//
        name = SINGLETON_SERVICE_PID, //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.OPTIONAL, //
        property = { //
        "enabled=true" //
        })
public class NetworkManagerImpl extends AbstractOpenemsComponent
        implements NetworkManager, OpenemsComponent, ComponentJsonApi {

    private final Logger log = LoggerFactory.getLogger(NetworkManagerImpl.class);

    // Hardcoded interface names
    private static final String IFACE_ETH0 = "eth0";
    private static final String IFACE_ETH1 = "eth1";
    // 4G prefixes (wwan0, usb0, ppp0...)
    private static final String[] IFACE_MOBILE_PREFIXES = {"wwan", "ppp", "usb", "enx"};

    @Reference
    private ConfigurationAdmin cm;

    private Config config = null;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ProcessCommandRunner commandRunner = new ProcessCommandRunner(Duration.ofSeconds(10));
    private final NmcliNetworkAdapter networkAdapter = new NmcliNetworkAdapter(this.commandRunner);
    private NetworkTransactionService transactionService;
    private final NetworkConfigValidator validator = new NetworkConfigValidator();
    private volatile String activeOperationId;
    private long revision = 1;

    public NetworkManagerImpl() {
        super(
                OpenemsComponent.ChannelId.values(),
                NetworkManager.ChannelId.values(),
                Network.ChannelId.values());
    }

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsException {
            super.activate(context, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);
            this.config = config;
			this.revision = config.revision();
			this.transactionService = new NetworkTransactionService(new NetworkOperationStore(this::publishOperation),
					this.networkAdapter, this.executor, Clock.systemUTC(),
					() -> "network-" + UUID.randomUUID(), 60);
			this.restoreLastOperation(config.lastOperation());
			this.recoverPendingOperation(config.pendingOperation());
            // Schedule polling task
            this.executor.scheduleWithFixedDelay(() -> {
                try {
	                    this.updateIpChannels();
					this.expireOperationIfDue();
                } catch (Exception e) {
                    this.logWarn(this.log, "Error in network monitor task: " + e.getMessage());
                }
            }, 1, 5, TimeUnit.SECONDS);

	            this.updateIpChannels();

            if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
                return;
            }
    }

    @Modified
    private void modified(ComponentContext context, Config config) throws OpenemsNamedException {
	        super.modified(context, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);
	        this.config = config;
	        this.revision = config.revision();
	        this.updateIpChannels();

        if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
            return;
        }
    }

    @Deactivate
    protected void deactivate() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        super.deactivate();
    }

    @Override
    public void buildJsonApiRoutes(JsonApiBuilder builder) {
        // Get network status (IP addresses)
        builder.handleRequest(new GetNetworkManagerStatus(), endpoint -> {
            endpoint.setDescription("Gets the current IP status of all network interfaces.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.GUEST));
        }, call -> {
            return this.handleGetNetworkManagerStatus();
        });

        // Get network configuration
        builder.handleRequest(new GetNetworkManagerConfig(), endpoint -> {
            endpoint.setDescription("Gets the current network manager configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.OWNER));
        }, call -> {
            return this.handleGetNetworkManagerConfig();
        });

        // Set network configuration
        builder.handleRequest(new SetNetworkManagerConfig(), endpoint -> {
            endpoint.setDescription("Updates the network manager configuration.");
            endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
        }, call -> {
			return this.handleSetNetworkManagerConfig(call.getRequest());
        });

		builder.handleRequest(new GetNetworkManagerOperation(), endpoint -> {
			endpoint.setDescription("Gets a network configuration operation snapshot.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.OWNER));
		}, call -> this.transactionService.get(call.getRequest().operationId()));

		builder.handleRequest(new ConfirmNetworkManagerConfig(), endpoint -> {
			endpoint.setDescription("Confirms an applied network configuration.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
		}, call -> this.transactionService.confirm(call.getRequest().operationId(), call.getRequest().expectedSequence()));

		builder.handleRequest(new CancelNetworkManagerConfig(), endpoint -> {
			endpoint.setDescription("Cancels a network configuration operation.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
		}, call -> this.transactionService.cancel(call.getRequest().operationId()));
    }

    /**
     * Handles GetNetworkManagerStatus request.
     *
     * @return the response with current IP status
     */
    private GetNetworkManagerStatus.Response handleGetNetworkManagerStatus() {
        String lan1Ip = this.getChannelValueAsString(NetworkManager.ChannelId.LAN1_IP);
        String lan2Ip = this.getChannelValueAsString(NetworkManager.ChannelId.LAN2_IP);
        String mobileIp = this.getChannelValueAsString(NetworkManager.ChannelId.MOBILE_IP);
        String lastError = this.getChannelValueAsString(NetworkManager.ChannelId.LAST_ERROR_MESSAGE);

        return new GetNetworkManagerStatus.Response(lan1Ip, lan2Ip, mobileIp, lastError);
    }

    /**
     * Handles GetNetworkManagerConfig request.
     *
     * @return the response with current configuration
     */
	    private GetNetworkManagerConfig.Response handleGetNetworkManagerConfig() {
		var interfaces = List.of(
				new GetNetworkManagerConfig.InterfaceEntry(IFACE_ETH0, this.desiredConfig(IFACE_ETH0),
						this.observedConfig(IFACE_ETH0)),
				new GetNetworkManagerConfig.InterfaceEntry(IFACE_ETH1, this.desiredConfig(IFACE_ETH1),
						this.observedConfig(IFACE_ETH1)));
		var operationId = this.activeOperationId;
		var activeOperation = operationId == null ? null : this.transactionService.get(operationId);
		return new GetNetworkManagerConfig.Response(this.revision, interfaces, activeOperation);
	    }

	private NetworkInterfaceConfig desiredConfig(String interfaceName) {
		if (interfaceName.equals(IFACE_ETH0)) {
			if (this.config.eth0Mode() == Mode.DHCP) {
				return new NetworkInterfaceConfig(interfaceName, Mode.DHCP, null, null, null,
						this.dnsList(this.config.eth0DnsPrimary(), this.config.eth0DnsSecondary()));
			}
			return new NetworkInterfaceConfig(interfaceName, this.config.eth0Mode(), emptyToNull(this.config.eth0Ip()),
					this.subnetMaskToPrefixLength(this.config.eth0SubnetMask()), emptyToNull(this.config.eth0Gateway()),
					this.dnsList(this.config.eth0DnsPrimary(), this.config.eth0DnsSecondary()));
		}
		if (this.config.eth1Mode() == Mode.DHCP) {
			return new NetworkInterfaceConfig(interfaceName, Mode.DHCP, null, null, null,
					this.dnsList(this.config.eth1DnsPrimary(), this.config.eth1DnsSecondary()));
		}
		return new NetworkInterfaceConfig(interfaceName, this.config.eth1Mode(), emptyToNull(this.config.eth1Ip()),
				this.subnetMaskToPrefixLength(this.config.eth1SubnetMask()), emptyToNull(this.config.eth1Gateway()),
				this.dnsList(this.config.eth1DnsPrimary(), this.config.eth1DnsSecondary()));
	}

	private GetNetworkManagerConfig.ObservedInterface observedConfig(String interfaceName) {
		try {
			var observed = this.networkAdapter.observe(interfaceName);
			return new GetNetworkManagerConfig.ObservedInterface(observed.deviceState(), observed.carrier(),
					observed.addresses(), observed.gateway(), observed.dns(), observed.activeProfile());
		} catch (Exception e) {
			this.logWarn(this.log, "Failed to observe " + interfaceName + " through NetworkManager: " + e.getMessage());
		}
		var ip = this.getIpFromChannel(interfaceName);
		var connected = !ip.isBlank() && !ip.equals("Disconnected");
		var gateway = interfaceName.equals(IFACE_ETH0)
				? this.getChannelValueAsString(NetworkManager.ChannelId.LAN1_GATEWAY)
				: this.getChannelValueAsString(NetworkManager.ChannelId.LAN2_GATEWAY);
		return new GetNetworkManagerConfig.ObservedInterface(connected ? "ACTIVATED" : "DISCONNECTED", connected,
				connected ? List.of(ip) : List.of(), "None".equals(gateway) ? null : gateway, List.of(), null);
	}

	private List<String> dnsList(String primary, String secondary) {
		return java.util.stream.Stream.of(primary, secondary).map(NetworkManagerImpl::emptyToNull)
				.filter(java.util.Objects::nonNull).toList();
	}

	private Integer subnetMaskToPrefixLength(String subnetMask) {
		if (subnetMask == null || subnetMask.isBlank()) {
			return null;
		}
		var mask = 0;
		for (var octet : subnetMask.split("\\.", -1)) {
			mask += Integer.bitCount(Integer.parseInt(octet));
		}
		return mask;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

    /**
     * Handles SetNetworkManagerConfig request.
     *
     * @param request the request with new configuration
     * @throws OpenemsNamedException on error
     */
    private NetworkOperationSnapshot handleSetNetworkManagerConfig(SetNetworkManagerConfig.Request request)
			throws OpenemsNamedException {
		if (request.expectedRevision() != this.revision) {
			throw new OpenemsException("REVISION_CONFLICT");
		}
		this.validateRequest(request);
		return this.transactionService.submit(this.revision + 1, request.toConfig(),
				List.of(this.getIpFromChannel(request.interfaceName())).stream().filter(ip -> !ip.isBlank()
						&& !ip.equals("Disconnected")).toList());
    }

	private void validateRequest(SetNetworkManagerConfig.Request request) throws OpenemsException {
		try {
			this.validator.validate(request.toConfig());
		} catch (NetworkValidationException e) {
			throw new OpenemsException(e.code().name() + ": " + e.getMessage());
		}
	}

	private void publishOperation(NetworkOperationSnapshot snapshot) {
		this.persistOperation(snapshot);
		this.activeOperationId = snapshot.state().isTerminal() ? null : snapshot.operationId();
		this.channel(NetworkManager.ChannelId.CURRENT_OPERATION_ID).setNextValue(snapshot.operationId());
		this.channel(NetworkManager.ChannelId.OPERATION_SEQUENCE).setNextValue(snapshot.sequence());
		this.channel(NetworkManager.ChannelId.OPERATION_STATE).setNextValue(snapshot.state().name());
		this.channel(NetworkManager.ChannelId.OPERATION_PROGRESS).setNextValue(this.operationProgress(snapshot));
		this.channel(NetworkManager.ChannelId.OPERATION_INTERFACE).setNextValue(snapshot.interfaceName());
		this.channel(NetworkManager.ChannelId.OPERATION_ERROR_CODE)
				.setNextValue(snapshot.error() == null ? null : snapshot.error().code().name());
		this.channel(NetworkManager.ChannelId.CONFIRMATION_DEADLINE)
				.setNextValue(snapshot.confirmationDeadline() == null ? null : snapshot.confirmationDeadline().toString());
		if (snapshot.state() == io.openems.edge.network.manager.operation.NetworkOperationState.APPLIED
				|| snapshot.state() == io.openems.edge.network.manager.operation.NetworkOperationState.CONFIGURED_WAITING_FOR_LINK) {
			this.revision = snapshot.revision();
		}
	}

	private void restoreLastOperation(String serializedOperation) throws OpenemsException {
		if (serializedOperation == null || serializedOperation.isBlank()) {
			return;
		}
		try {
				var snapshot = NetworkOperationSnapshot.persistenceSerializer().deserialize(serializedOperation);
			if (snapshot.state().isTerminal()) {
				this.transactionService.recoverAfterRestart(snapshot);
			}
		} catch (OpenemsNamedException | RuntimeException e) {
			throw new OpenemsException("Failed to restore last network operation: " + e.getMessage());
		}
	}

	private void recoverPendingOperation(String serializedOperation) throws OpenemsException {
		if (serializedOperation == null || serializedOperation.isBlank()) {
			return;
		}
		try {
				var snapshot = NetworkOperationSnapshot.persistenceSerializer().deserialize(serializedOperation);
			this.transactionService.recoverAfterRestart(snapshot);
		} catch (OpenemsNamedException | RuntimeException e) {
			throw new OpenemsException("Failed to recover pending network operation: " + e.getMessage());
		}
	}

	private void persistOperation(NetworkOperationSnapshot snapshot) {
		try {
			var configuration = this.cm.getConfiguration(SINGLETON_SERVICE_PID, "?");
			var properties = configuration.getProperties();
				var serialized = NetworkOperationSnapshot.persistenceSerializer().serialize(snapshot).toString();
			properties.put("pendingOperation",
					snapshot.state().isTerminal() ? "" : serialized);
			properties.put("lastOperation", serialized);
			if (snapshot.state() == io.openems.edge.network.manager.operation.NetworkOperationState.APPLIED
					|| snapshot.state() == io.openems.edge.network.manager.operation.NetworkOperationState.CONFIGURED_WAITING_FOR_LINK) {
				this.putLastKnownGood(properties, snapshot);
			}
			configuration.update(properties);
		} catch (IOException | SecurityException e) {
			this.logError(this.log, "Failed to persist network operation: " + e.getMessage());
			this.channel(NetworkManager.ChannelId.LAST_ERROR_MESSAGE).setNextValue(e.getMessage());
			throw new IllegalStateException("Failed to persist network operation", e);
		}
	}

	private void expireOperationIfDue() {
		var operationId = this.activeOperationId;
		if (operationId != null) {
			this.transactionService.expire(operationId, Clock.systemUTC().instant());
		}
	}

	private void putLastKnownGood(Dictionary<String, Object> properties, NetworkOperationSnapshot snapshot) {
		var desired = snapshot.desired();
		var prefix = desired.interfaceName();
		properties.put("revision", snapshot.revision());
		properties.put(prefix + "Mode", desired.mode().name());
		properties.put(prefix + "Ip", desired.ip() == null ? "" : desired.ip());
		properties.put(prefix + "SubnetMask",
				desired.prefixLength() == null ? "" : this.prefixLengthToSubnetMask(desired.prefixLength()));
		properties.put(prefix + "Gateway", desired.gateway() == null ? "" : desired.gateway());
		properties.put(prefix + "DnsPrimary", desired.dns().isEmpty() ? "" : desired.dns().get(0));
		properties.put(prefix + "DnsSecondary", desired.dns().size() < 2 ? "" : desired.dns().get(1));
	}

	private String prefixLengthToSubnetMask(int prefixLength) {
		var mask = prefixLength == 0 ? 0 : -1 << 32 - prefixLength;
		return String.format("%d.%d.%d.%d", mask >>> 24 & 255, mask >>> 16 & 255, mask >>> 8 & 255, mask & 255);
	}

	private int operationProgress(NetworkOperationSnapshot snapshot) {
		return switch (snapshot.state()) {
		case ACCEPTED -> 10;
		case APPLYING -> 40;
		case VERIFYING -> 70;
		case AWAITING_CONFIRMATION -> 90;
		case APPLIED, CONFIGURED_WAITING_FOR_LINK -> 100;
		case ROLLING_BACK -> 80;
		case FAILED, ROLLED_BACK, CANCELLED -> 100;
		};
	}

    private String getChannelValueAsString(NetworkManager.ChannelId channelId) {
        Object val = this.channel(channelId).value().get();
        return (val != null) ? val.toString() : null;
    }

    /**
     * Scans all network interfaces and populates OpenEMS Channels.
     */
    private void updateIpChannels() {
        try {
            // Reset channels
            this.channel(NetworkManager.ChannelId.LAN1_IP).setNextValue("Disconnected");
            this.channel(NetworkManager.ChannelId.LAN1_GATEWAY).setNextValue("None");
            this.channel(NetworkManager.ChannelId.LAN2_IP).setNextValue("Disconnected");
            this.channel(NetworkManager.ChannelId.LAN2_GATEWAY).setNextValue("None");
            this.channel(NetworkManager.ChannelId.MOBILE_IP).setNextValue("Disconnected");
            this.channel(NetworkManager.ChannelId.MOBILE_GATEWAY).setNextValue("None");

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.getName().startsWith("lo")) {
                    continue;
                }
                String ipV4 = this.getIPv4FromInterface(iface);
                if (ipV4 == null) {
                    continue;
                }

                String name = iface.getName();

                // Auto-map based on interface name
                if (name.equals(IFACE_ETH0)) {
                    this.channel(NetworkManager.ChannelId.LAN1_IP).setNextValue(ipV4);
                    this.channel(NetworkManager.ChannelId.LAN1_GATEWAY).setNextValue(this.getGatewayFromInterface(name));
                } else if (name.equals(IFACE_ETH1)) {
                    this.channel(NetworkManager.ChannelId.LAN2_IP).setNextValue(ipV4);
                    this.channel(NetworkManager.ChannelId.LAN2_GATEWAY).setNextValue(this.getGatewayFromInterface(name));
                } else if (this.isMobileInterface(name)) {
                    this.channel(NetworkManager.ChannelId.MOBILE_IP).setNextValue(ipV4);
                    this.channel(NetworkManager.ChannelId.MOBILE_GATEWAY).setNextValue(this.getGatewayFromInterface(name));
                }
            }
        } catch (Exception e) {
            this.logWarn(this.log, "Monitoring Error: " + e.getMessage());
        }
    }

    private String getIpFromChannel(String ifName) {
        NetworkManager.ChannelId channelId = null;

        // 1. Xác định Channel cần lấy dựa trên tên interface
        if (ifName.equals(IFACE_ETH0)) {
            channelId = NetworkManager.ChannelId.LAN1_IP;
        } else if (ifName.equals(IFACE_ETH1)) {
            channelId = NetworkManager.ChannelId.LAN2_IP;
        }

        // 2. Lấy giá trị an toàn (Tránh lỗi Generics orElse)
        if (channelId != null) {
            Object val = this.channel(channelId).value().get();
            return (val != null) ? val.toString() : "";
        }

        return "";
    }

	    private String getGatewayFromInterface(String interfaceName) {
	        try {
	            String output = this.commandRunner.execute(List.of("ip", "route", "show", "dev", interfaceName));
			for (var line : output.lines().toList()) {
				var fields = line.trim().split("\\s+");
				if (fields.length >= 3 && fields[0].equals("default") && fields[1].equals("via")) {
					return fields[2];
				}
			}
	        } catch (Exception e) {
	            this.logWarn(this.log, "Failed to get gateway for " + interfaceName + ": " + e.getMessage());
	        }
	        return "None";
    }

private String getIPv4FromInterface(NetworkInterface iface) {
    String bestIp = null;
    int bestPrefixLength = 129; // Khởi tạo giá trị lớn hơn bất kỳ prefix nào (max 32 hoặc 128)

    for (java.net.InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
        InetAddress addr = ifaceAddr.getAddress();
        
        // Chỉ lấy IPv4 (4 bytes)
        if (addr.getAddress().length != 4) {
            continue;
        }

        int prefixLength = ifaceAddr.getNetworkPrefixLength();
        
        // LOGIC CỐT LÕI: Ưu tiên Subnet rộng hơn (Prefix nhỏ hơn)
        // LAN (/24) sẽ luôn thắng VPN (/32).
        // Nhưng nếu chỉ có 4G (/32), nó vẫn được chấp nhận.
        
        if (bestIp == null || prefixLength < bestPrefixLength) {
            bestIp = addr.getHostAddress();
            bestPrefixLength = prefixLength;
        }
    }
    return bestIp;
}

	private boolean isMobileInterface(String name) {
		for (String prefix : IFACE_MOBILE_PREFIXES) {
			if (name.startsWith(prefix)) {
                return true;
            }
		}
		return false;
	}

}
