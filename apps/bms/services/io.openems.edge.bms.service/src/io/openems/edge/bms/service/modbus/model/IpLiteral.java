package io.openems.edge.bms.service.modbus.model;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Strict IP-literal parser that never sends a hostname to DNS. */
public final class IpLiteral {
	private IpLiteral() {}

	public static InetAddress parse(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("IP literal is required");
		try {
			if (value.indexOf(':') >= 0) {
				if (!value.matches("[0-9A-Fa-f:.%]+")) throw new IllegalArgumentException("Invalid IPv6 literal");
				var address = InetAddress.getByName(value);
				if (!(address instanceof Inet6Address)) throw new IllegalArgumentException("Invalid IPv6 literal");
				return address;
			}
			var parts = value.split("\\.", -1);
			if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 literal");
			var bytes = new byte[4];
			for (var i = 0; i < parts.length; i++) {
				if (parts[i].isBlank() || !parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4 literal");
				var octet = Integer.parseInt(parts[i]);
				if (octet > 255) throw new IllegalArgumentException("Invalid IPv4 literal");
				bytes[i] = (byte) octet;
			}
			return InetAddress.getByAddress(bytes);
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid IP literal", e);
		}
	}
}
