package io.openems.edge.ess.deye;

import org.osgi.service.event.EventHandler;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.ess.api.ManagedSymmetricEss;

public interface DeyeHybridEss extends ManagedSymmetricEss, OpenemsComponent, EventHandler, ModbusSlave {
}
