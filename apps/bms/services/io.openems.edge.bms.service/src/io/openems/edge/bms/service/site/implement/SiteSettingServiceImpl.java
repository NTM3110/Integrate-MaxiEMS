package io.openems.edge.bms.service.site.implement;

import io.openems.edge.bms.service.site.implement.persistence.SiteSettingRepository;

import io.openems.edge.bms.service.site.contracts.SiteSettingService;

import java.sql.SQLException;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(name = "io.openems.edge.bms.platform.site.SiteSettingServiceImpl", service = SiteSettingService.class)
public class SiteSettingServiceImpl implements SiteSettingService {

	static final String DEFAULT_SITE_NAME = "My Monitoring Site";
	static final int MAX_SITE_NAME_LENGTH = 120;
	private static final String SITE_NAME_KEY = "siteName";

	@Reference
	private SiteSettingRepository repository;

	public SiteSettingServiceImpl() {
	}

	SiteSettingServiceImpl(SiteSettingRepository repository) {
		this.repository = repository;
	}

	@Override
	public String getSiteName() throws SQLException {
		return this.repository.find(SITE_NAME_KEY).filter(value -> !value.isBlank()).orElse(DEFAULT_SITE_NAME);
	}

	@Override
	public String setSiteName(String siteName, UUID updatedBy) throws SQLException {
		if (siteName == null) {
			throw new IllegalArgumentException("Site name must be a string");
		}
		var normalized = siteName.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Site name must not be blank");
		}
		if (normalized.length() > MAX_SITE_NAME_LENGTH) {
			throw new IllegalArgumentException("Site name must not exceed " + MAX_SITE_NAME_LENGTH + " characters");
		}
		this.repository.save(SITE_NAME_KEY, normalized, updatedBy);
		return normalized;
	}
}
