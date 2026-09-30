package io.openems.edge.bms.service.auth.model;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedPrincipal(UUID id, String username, String name, Set<PlatformRole> roles) {
	public AuthenticatedPrincipal {
		if (id == null || username == null || username.isBlank() || name == null || name.isBlank() || roles == null) {
			throw new IllegalArgumentException("Authenticated principal identity and name are required");
		}
		roles = Set.copyOf(roles);
	}

	public boolean hasRole(PlatformRole role) {
		return this.roles.contains(PlatformRole.PLATFORM_ADMIN) || this.roles.contains(role);
	}
}
