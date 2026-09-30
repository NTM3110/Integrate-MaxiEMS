package io.openems.controller.ipsec;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

@ObjectClassDefinition(name = "Controller VPN EVN (Minimal)")
@interface Config {
    String id() default "EVNNPC0";
    boolean enabled() default false;
	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";
    // --- Tunnel Policy (Thông số bắt buộc) ---
    @AttributeDefinition(name = "Local ID", description = "FQDN/String cho leftid khi có NAT")
    String localId() default "AT_Energy";

    @AttributeDefinition(name = "Local Subnet", description = "Subnet LAN cần bảo vệ")
    String localSubnet() default "10.0.0.0/24";

    @AttributeDefinition(name = "Is behind NAT?", description = "Bật nếu thiết bị nằm sau Router")
    boolean natLeft() default true;

    @AttributeDefinition(name = "Remote Public IP", description = "IP tĩnh của trạm đối diện")
    String remotePublicIp() default "116.212.47.2";

    @AttributeDefinition(name = "Remote ID", description = "ID của peer (rightid)")
    String remoteId() default "Sophos_EVN";

    @AttributeDefinition(name = "Remote Subnet", description = "Subnet mạng đích")
    String remoteSubnet() default "10.21.10.64/27";

    @AttributeDefinition(name = "Pre-Shared Key", description = "Mật khẩu PSK")
    String psk() default "password";
}