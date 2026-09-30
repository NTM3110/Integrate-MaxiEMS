package io.openems.edge.bms.service.email.implement.persistence;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

@Component(name = "io.openems.edge.bms.platform.email.PostgresEmailAlertEventRepository", service = EmailAlertEventRepository.class)
public class PostgresEmailAlertEventRepository implements EmailAlertEventRepository {
	@Reference private BmsDatabase database;

	@Override public List<EmailAlertService.DeliveryStatus> list() throws SQLException {
		var results = new ArrayList<EmailAlertService.DeliveryStatus>();
		try (var connection = this.database.getConnection(); var query = connection.prepareStatement("SELECT event_key,active,last_sent_at,last_delivery_status,updated_at FROM bms.email_alert_event ORDER BY updated_at DESC" ); var rows = query.executeQuery()) {
			while (rows.next()) results.add(new EmailAlertService.DeliveryStatus(rows.getString(1), rows.getBoolean(2), rows.getTimestamp(3) == null ? 0 : rows.getTimestamp(3).getTime(), rows.getString(4), rows.getTimestamp(5).getTime()));
		}
		return results;
	}
	@Override public void save(EmailAlertService.DeliveryStatus status) throws SQLException {
		try (var connection = this.database.getConnection(); var update = connection.prepareStatement("INSERT INTO bms.email_alert_event(event_key,active,last_sent_at,last_delivery_status,updated_at) VALUES (?,?,?,?,now()) ON CONFLICT(event_key) DO UPDATE SET active=EXCLUDED.active,last_sent_at=EXCLUDED.last_sent_at,last_delivery_status=EXCLUDED.last_delivery_status,updated_at=now()")) {
			update.setString(1, status.eventKey()); update.setBoolean(2, status.active());
			if (status.lastSentAt() == 0) update.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE); else update.setTimestamp(3, new Timestamp(status.lastSentAt()));
			update.setString(4, status.lastDeliveryStatus()); update.executeUpdate();
		}
	}
}
