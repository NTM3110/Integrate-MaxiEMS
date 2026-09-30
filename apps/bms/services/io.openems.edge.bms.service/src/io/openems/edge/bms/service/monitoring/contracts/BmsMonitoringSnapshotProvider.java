package io.openems.edge.bms.service.monitoring.contracts;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

import io.openems.edge.common.component.OpenemsComponent;

/** Atomic runtime boundary consumed by API, Modbus, persistence and alarms. */
public interface BmsMonitoringSnapshotProvider extends OpenemsComponent {

	String stringKey();

	BmsMonitoringSnapshot latestFrame();
}
