package io.openems.edge.bms.service.email.implement;

import io.openems.edge.bms.service.email.contracts.EmailTransport;

import io.openems.edge.bms.service.email.implement.persistence.EmailAlertEventRepository;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class EmailAlertServiceImplTest {

	private static final EmailAlertService.Settings VALID = new EmailAlertService.Settings(true, "mail.example.com", 587,
			"starttls", "operator", false, "bms@example.com", "MaxiEMS", List.of("team@example.com"),
			List.of("missing-stale-data"), true, 60);

	@Test
	public void passwordIsOnlyRepresentedByPresenceBit() {
		var response = EmailAlertServiceImpl.view(VALID, "secret-value");
		assertTrue(response.passwordConfigured());
		assertFalse(response.toString().contains("secret-value"));
	}

	@Test
	public void rejectsUnsafeAndInconsistentSmtpSettingsBeforeDelivery() {
		EmailAlertServiceImpl.validate(new EmailAlertService.Settings(true, "mail.example.com", 25, "plain", "", false,
				"bms@example.com", "MaxiEMS", List.of("team@example.com"), List.of("high-resistance", "high-temperature", "low-voltage", "high-voltage", "missing-stale-data"), true, 60), "");
		assertThrows(IllegalArgumentException.class, () -> EmailAlertServiceImpl.validate(VALID, ""));
		assertThrows(IllegalArgumentException.class, () -> EmailAlertServiceImpl.validate(new EmailAlertService.Settings(true, "mail\r\nBcc:x", 25, "plain", "", false, "bms@example.com", "MaxiEMS", List.of("team@example.com"), List.of("missing-stale-data"), true, 60), ""));
		assertThrows(IllegalArgumentException.class, () -> EmailAlertServiceImpl.validate(new EmailAlertService.Settings(true, "mail relay.example.com", 25, "plain", "", false, "bms@example.com", "MaxiEMS", List.of("team@example.com"), List.of("missing-stale-data"), true, 60), ""));
		assertThrows(IllegalArgumentException.class, () -> EmailAlertServiceImpl.validate(new EmailAlertService.Settings(true, "mail.example.com", 25, "plain", "", false, "bms@example.com", "MaxiEMS", List.of("team@example.com"), List.of("communication-loss"), true, 60), ""));
	}

	@Test
	public void reloadRestoresActiveDeliveryStateAndStatusHistory() throws Exception {
		var repository = new InMemoryRepository();
		var persisted = new EmailAlertService.DeliveryStatus("missing-stale-data:calculator-1", true, 123L, "SENT", 456L);
		repository.statuses.add(persisted);
		var service = new EmailAlertServiceImpl();
		setField(service, "events", repository);

		invoke(service, "reloadDeliveryState");

		assertEquals(List.of(persisted), service.getDeliveryStatus());
		@SuppressWarnings("unchecked")
		var activeEvents = (java.util.Map<String, Long>) getField(service, "activeEvents");
		assertEquals(Long.valueOf(123L), activeEvents.get(persisted.eventKey()));
	}

	@Test
	public void statusUpdatesArePersistedForAdministratorHistory() throws Exception {
		var repository = new InMemoryRepository();
		var service = new EmailAlertServiceImpl();
		setField(service, "events", repository);

		invoke(service, "updateStatus", String.class, boolean.class, long.class, String.class,
				"missing-stale-data:calculator-1", true, 123L, "FAILED");

		assertEquals(1, repository.statuses.size());
		assertEquals("FAILED", repository.statuses.get(0).lastDeliveryStatus());
	}

	@Test
	public void thresholdEventsUseCategoryStringAndCellIdentity() {
		assertEquals("high-temperature:str7:cell12", EmailAlertServiceImpl.thresholdIdentity("high-temperature", "str7", 12));
		assertEquals("low-voltage:str7:cell12", EmailAlertServiceImpl.thresholdIdentity("low-voltage", "str7", 12));
	}

	@Test
	public void deliveryFailureIsRecordedWithoutLeakingToCalculation() throws Exception {
		var repository = new InMemoryRepository();
		var service = new EmailAlertServiceImpl();
		setField(service, "events", repository);
		setField(service, "transport", (EmailTransport) (settings, password, subject, body) -> { throw new java.io.IOException("relay unavailable"); });

		service.deliver("high-voltage:str1:cell1", VALID, "subject", "body");

		assertEquals("FAILED", service.getDeliveryStatus().get(0).lastDeliveryStatus());
	}

	@Test
	public void staleAndPartialSnapshotsAreUnavailableButFreshCompleteSnapshotIsNot() {
		var fresh = snapshot(System.currentTimeMillis(), null);
		var partial = snapshot(System.currentTimeMillis(), "PARTIAL_DATA");
		assertFalse(EmailAlertServiceImpl.hasUnavailableData(fresh, System.currentTimeMillis()));
		assertTrue(EmailAlertServiceImpl.hasUnavailableData(partial, System.currentTimeMillis()));
		assertTrue(EmailAlertServiceImpl.hasUnavailableData(fresh, fresh.capturedAt() + 60_000L));
	}

	private static io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot snapshot(long capturedAt, String warning) {
		return new io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot(java.util.UUID.randomUUID(), 1, capturedAt, 1,
				null, null, null, io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality.MISSING,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, java.util.Map.of(1, new io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.Cell(1, 2.0, 25.0, 5, null, null, null, warning)), null);
	}

	private static Object getField(Object target, String name) throws ReflectiveOperationException {
		var field = EmailAlertServiceImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
		var field = EmailAlertServiceImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void invoke(Object target, String name) throws ReflectiveOperationException {
		var method = EmailAlertServiceImpl.class.getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(target);
	}

	private static void invoke(Object target, String name, Class<?> firstType, Class<?> secondType, Class<?> thirdType,
			Class<?> fourthType, Object first, Object second, Object third, Object fourth) throws ReflectiveOperationException {
		Method method = EmailAlertServiceImpl.class.getDeclaredMethod(name, firstType, secondType, thirdType, fourthType);
		method.setAccessible(true);
		method.invoke(target, first, second, third, fourth);
	}

	private static final class InMemoryRepository implements EmailAlertEventRepository {
		private final List<EmailAlertService.DeliveryStatus> statuses = new ArrayList<>();

		@Override
		public List<EmailAlertService.DeliveryStatus> list() {
			return List.copyOf(this.statuses);
		}

		@Override
		public void save(EmailAlertService.DeliveryStatus status) throws SQLException {
			this.statuses.removeIf(existing -> existing.eventKey().equals(status.eventKey()));
			this.statuses.add(status);
		}
	}
}
