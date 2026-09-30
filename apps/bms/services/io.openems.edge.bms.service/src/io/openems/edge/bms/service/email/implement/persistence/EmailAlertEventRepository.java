package io.openems.edge.bms.service.email.implement.persistence;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import java.sql.SQLException;
import java.util.List;

public interface EmailAlertEventRepository {
	List<EmailAlertService.DeliveryStatus> list() throws SQLException;
	void save(EmailAlertService.DeliveryStatus status) throws SQLException;
}
