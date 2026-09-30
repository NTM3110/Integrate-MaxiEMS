package io.openems.edge.bms.service.site.contracts;

import java.sql.SQLException;
import java.util.UUID;

public interface SiteSettingService {

	String getSiteName() throws SQLException;

	String setSiteName(String siteName, UUID updatedBy) throws SQLException;
}
