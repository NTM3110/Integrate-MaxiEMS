package io.openems.edge.logger.sma;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Sma", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class SmaLoggerImpl extends AbstractOpenemsModbusComponent //
		implements SmaLogger, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent {

	private static final ElementToChannelConverter WH_TO_KWH = new ElementToChannelConverter(value -> {
		var wattHours = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
		if (wattHours == null) {
			return null;
		}
		return wattHours / 1000F;
	});

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	protected ElectricityMeter meter;

	private final Consumer<Value<Integer>> meterActivePowerForwarder = value -> this.meter._setActivePower(value.get());

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config;

	public SmaLoggerImpl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SmartLogger.ChannelId.values(), //
				SmaLogger.ChannelId.values() //
		);
	}

	@Activate
	@Modified
	protected void activate(final ComponentContext context, final Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id());
		this.getGridConnectionPointActivePowerChannel()
				.onSetNextValue(this.meterActivePowerForwarder);
		OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "meter", config.meter_id());
	}

	@Deactivate
	@Override
	protected void deactivate() {
		this.getGridConnectionPointActivePowerChannel()
				.removeOnSetNextValueCallback(this.meterActivePowerForwarder);
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(30513, Priority.HIGH, //
						m(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new UnsignedQuadruplewordElement(30513)), //
						m(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY,
								new UnsignedQuadruplewordElement(30517), WH_TO_KWH)), //
				new FC3ReadRegistersTask(30775, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(30775).wordOrder(WordOrder.MSWLSW))),
				new FC3ReadRegistersTask(31249, Priority.HIGH, //
						m(SmaLogger.ChannelId.GRID_CONNECTION_POINT_ACTIVE_POWER,
								new SignedDoublewordElement(31249))));
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}
}
