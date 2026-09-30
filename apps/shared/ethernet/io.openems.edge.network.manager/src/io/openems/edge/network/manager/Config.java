package io.openems.edge.network.manager;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

@ObjectClassDefinition(name = "Network Manager", description = "Monitors all interfaces. Configures eth0/eth1 if requested.")
public @interface Config {

        @AttributeDefinition(name = "Component-ID", description = "Unique ID")
        String id() default "network0";

        @AttributeDefinition(name = "Alias", description = "Human-readable name")
	String alias() default "";

	@AttributeDefinition(name = "Configuration revision", description = "Committed network configuration revision")
	long revision() default 1;

	@AttributeDefinition(name = "Pending operation", description = "Serialized network operation for restart recovery")
	String pendingOperation() default "";

	@AttributeDefinition(name = "Last operation", description = "Last operation snapshot retained for UI recovery")
	String lastOperation() default "";

        // --- ETH0 SETTINGS (WAN) ---
        @AttributeDefinition(name = "[eth0] Set IP Mode", description = "Select 'NONE' to keep current OS settings.", options = {
                        @Option(label = "Do Nothing (Monitor Only)", value = "NONE"),
                        @Option(label = "Change to DHCP", value = "DHCP"),
                        @Option(label = "Change to Static IP", value = "STATIC")
        })
        Mode eth0Mode() default Mode.NONE;

        @AttributeDefinition(name = "[eth0] IP Address", description = "Static IP address (e.g., 192.168.1.50)")
        String eth0Ip() default "192.168.1.50";

        @AttributeDefinition(name = "[eth0] Subnet Mask", description = "Subnet mask (e.g., 255.255.255.0)")
        String eth0SubnetMask() default "255.255.255.0";

        @AttributeDefinition(name = "[eth0] Gateway", description = "Gateway address (e.g., 192.168.1.1)")
        String eth0Gateway() default "192.168.1.1";

        @AttributeDefinition(name = "[eth0] Primary DNS", description = "Primary DNS server (e.g., 8.8.8.8)")
        String eth0DnsPrimary() default "8.8.8.8";

        @AttributeDefinition(name = "[eth0] Secondary DNS", description = "Secondary DNS server (e.g., 8.8.4.4)")
        String eth0DnsSecondary() default "8.8.4.4";

        // --- ETH1 SETTINGS (Local) ---
        @AttributeDefinition(name = "[eth1] Set IP Mode", description = "Select 'NONE' to keep current OS settings.", options = {
                        @Option(label = "Do Nothing (Monitor Only)", value = "NONE"),
                        @Option(label = "Change to DHCP", value = "DHCP"),
                        @Option(label = "Change to Static IP", value = "STATIC")
        })
        Mode eth1Mode() default Mode.NONE;

        @AttributeDefinition(name = "[eth1] IP Address", description = "Static IP address (e.g., 10.0.0.5)")
        String eth1Ip() default "10.0.0.5";

        @AttributeDefinition(name = "[eth1] Subnet Mask", description = "Subnet mask (e.g., 255.255.255.0)")
        String eth1SubnetMask() default "255.255.255.0";

        @AttributeDefinition(name = "[eth1] Gateway", description = "Gateway address (optional for LAN)")
        String eth1Gateway() default "";

        @AttributeDefinition(name = "[eth1] Primary DNS", description = "Primary DNS server (optional for LAN)")
        String eth1DnsPrimary() default "";

        @AttributeDefinition(name = "[eth1] Secondary DNS", description = "Secondary DNS server (optional for LAN)")
        String eth1DnsSecondary() default "";

        String webconsole_configurationFactory_nameHint() default "Network Manager [{id}]";
}
