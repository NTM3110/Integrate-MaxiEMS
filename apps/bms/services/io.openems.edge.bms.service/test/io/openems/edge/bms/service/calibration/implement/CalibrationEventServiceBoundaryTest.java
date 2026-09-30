package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;
import io.openems.edge.bms.service.strings.implement.persistence.StringManagementService;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CreateCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeletedEvent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition;

/** Service/store assertions separated from the JSON adapter tests. */
public class CalibrationEventServiceBoundaryTest {

	private static final UUID STRING_ID = UUID.fromString("74c6264e-3913-49ce-8286-54083718295f");

	@Test
	public void serviceAuthorizationCoversViewerOperatorAdminAndNoRole() throws Exception {
		for (var role : List.of(PlatformRole.BMS_VIEWER, PlatformRole.BMS_OPERATOR, PlatformRole.PLATFORM_ADMIN)) {
			guard("requireViewer", principal(role));
		}
		for (var role : List.of(PlatformRole.BMS_OPERATOR, PlatformRole.PLATFORM_ADMIN)) {
			guard("requireOperator", principal(role));
		}
		denied("requireViewer", principal());
		denied("requireOperator", principal());
		denied("requireOperator", principal(PlatformRole.BMS_VIEWER));
	}

	@Test
	public void createUsesServerTopologyActorAndC10C5Defaults() throws Exception {
		var service = service();
		var actor = principal(PlatformRole.BMS_OPERATOR);
		create(service, actor, DischargeMethod.C10, 37.5, null);
		var command = service.command;
		assertSame(actor, command.actor());
		assertEquals(STRING_ID, command.stringId());
		var config = command.configuration();
		assertEquals("str4", config.stringKey());
		assertEquals(110, config.cellCount());
		assertEquals(200.0, config.ratedCapacityAh(), 0.0);
		assertEquals(1.80, config.cutoffVoltagePerCell(), 0.0);
		assertEquals(1.70, config.safetyFloorVoltagePerCell(), 0.000001);
		assertEquals(37.5, config.chargeCurrentLimitA(), 0.0);
		assertEquals(60, config.snapshotFreshnessSeconds());
		assertEquals(1, config.mappingVersion());
		assertEquals(-3.3, config.floatTemperatureCoefficientMvPerCellC(), 0.0);
		assertEquals(-5.0, config.boostTemperatureCoefficientMvPerCellC(), 0.0);
		assertEquals(CalibrationEventConfiguration.DISCHARGE_VOLTAGE_FLOOR_POLICY, config.dischargeVoltageFloorPolicy());
		create(service, actor, DischargeMethod.C5, null, null);
		assertEquals(1.75, service.command.configuration().cutoffVoltagePerCell(), 0.0);
		assertEquals(1.65, service.command.configuration().safetyFloorVoltagePerCell(), 0.000001);
		assertEquals(40.0, service.command.configuration().chargeCurrentLimitA(), 0.0);
		create(service, actor, DischargeMethod.C10, null, 1.70);
		assertEquals(1.70, service.command.configuration().safetyFloorVoltagePerCell(), 0.0);
	}

	@Test
	public void createRejectsInvalidChargeLimitAndSafetyFloor() throws Exception {
		var service = service();
		var actor = principal(PlatformRole.BMS_OPERATOR);
		assertEquals("chargeCurrentLimitA must be finite and positive", assertThrows(IllegalArgumentException.class,
				() -> create(service, actor, DischargeMethod.C10, 0.0, null)).getMessage());
		assertEquals("safetyFloorVoltagePerCell must be strictly below cutoffVoltagePerCell",
				assertThrows(IllegalArgumentException.class,
						() -> create(service, actor, DischargeMethod.C10, null, 1.80)).getMessage());
	}

	@Test
	public void monthlyCreateKeepsServerTopologyWithoutADischargePlan() throws Exception {
		var service = service();
		service.create(4, CalibrationEventType.MONTHLY_INSPECTION, DischargeCurrentDirection.NEGATIVE,
				-3.3, -5.0, null, null, null, null, principal(PlatformRole.BMS_OPERATOR));
		assertEquals(STRING_ID, service.command.stringId());
		assertEquals(110, service.command.configuration().cellCount());
		assertEquals(CalibrationEventType.MONTHLY_INSPECTION, service.command.configuration().eventType());
		assertEquals(null, service.command.configuration().dischargeMethod());
		assertEquals(null, service.command.configuration().dischargeEndpointMode());
	}

	@Test
	public void deleteDraftPropagatesStaleVersionAndNonDraftRejections() throws Exception {
		var service = new CalibrationEventService();
		var store = new DeleteDraftStore();
		setField(service, "store", store);
		var command = new DeleteDraftCommand(42, 7);
		for (var message : List.of("Calibration event version is stale", "Only a draft calibration event can be deleted")) {
			store.failure = new IllegalArgumentException(message);
			assertEquals(message, assertThrows(IllegalArgumentException.class,
					() -> service.deleteDraft(command, principal(PlatformRole.BMS_OPERATOR))).getMessage());
			assertEquals(command, store.command);
		}
	}

	private static void create(CaptureService service, AuthenticatedPrincipal actor, DischargeMethod method,
			Double currentLimit, Double floor) throws Exception {
		service.create(4, CalibrationEventType.INITIAL_BASELINE, DischargeCurrentDirection.NEGATIVE, -3.3, -5.0,
				method, DischargeEndpointMode.FULL_CUTOFF, null, null, actor,
				CalibrationEventConfiguration.DEFAULT_FLOAT_STABLE_DURATION_MINUTES,
				CalibrationEventConfiguration.DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V,
				CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MIN_A,
				CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MAX_A, currentLimit, floor);
	}

	private static CaptureService service() throws Exception {
		var service = new CaptureService();
		setField(service, "strings", new FixedStrings());
		return service;
	}

	private static AuthenticatedPrincipal principal(PlatformRole... roles) {
		return new AuthenticatedPrincipal(UUID.randomUUID(), "operator", "Operator", Set.of(roles));
	}

	private static void guard(String name, AuthenticatedPrincipal principal) throws Exception {
		var method = CalibrationEventService.class.getDeclaredMethod(name, AuthenticatedPrincipal.class);
		method.setAccessible(true);
		method.invoke(null, principal);
	}

	private static void denied(String name, AuthenticatedPrincipal principal) {
		var exception = assertThrows(InvocationTargetException.class, () -> guard(name, principal));
		assertEquals(SecurityException.class, exception.getCause().getClass());
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		var field = CalibrationEventService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class CaptureService extends CalibrationEventService {
		private CreateCommand command;
		@Override
		protected EventDetail createFromSynchronizedString(CreateCommand command) {
			this.command = command;
			return null;
		}
	}

	private static final class DeleteDraftStore extends CalibrationEventStore {
		private DeleteDraftCommand command;
		private IllegalArgumentException failure;
		@Override
		public DeletedEvent deleteDraft(DeleteDraftCommand command) {
			this.command = command;
			if (this.failure != null) throw this.failure;
			return new DeletedEvent(command.calibrationEventId());
		}
	}

	private static final class FixedStrings extends StringManagementService {
		@Override
		public void reconcile() { }
		@Override
		public List<Definition> persistentDefinitions() {
			return List.of(new Definition(STRING_ID, 4, "str4", "String 4", 110, "Vision", "CL-200", 200,
					1.85, 2.30, 2.45, 1450, 0, 0, 0, 0, "modbus0", 1));
		}
	}
}
