package io.openems.edge.logger.foxess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.MeterType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.logger.api.EnergyProductionTodayCalculator;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Foxess", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class FoxessLoggerImpl extends AbstractOpenemsModbusComponent //
		implements FoxessLogger, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public Config config;
	private final EnergyProductionTodayCalculator energyProductionTodayCalculator = new EnergyProductionTodayCalculator();
	private LocalDate energyProductionTodayDay;

	public FoxessLoggerImpl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SmartLogger.ChannelId.values(), //
				FoxessLogger.ChannelId.values() //
		);

		this.<io.openems.edge.common.channel.Channel<Long>>channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL)
				.onSetNextValue(value -> this.calculateEnergyProductionToday(value.get()));
	}

	@Activate
	@Modified
	protected void activate(final ComponentContext context, final Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id());
		this.restoreEnergyProductionTotalAtStartOfDay();
	}

	@Deactivate
	@Override
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(40323, Priority.HIGH, //
					m(FoxessLogger.ChannelId.DC1_POWER,
							new SignedDoublewordElement(40323)),
					m(FoxessLogger.ChannelId.DC2_POWER,
							new SignedDoublewordElement(40325)),
					new DummyRegisterElement(40327,40328),
					m(FoxessLogger.ChannelId.DC_TOTAL,
							new SignedDoublewordElement(40329)),
					new DummyRegisterElement(40331,40356),
					m(FoxessLogger.ChannelId.S_N,
							new StringWordElement(40357,16)),
					new DummyRegisterElement(40373,40377),
					m(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL,
							new UnsignedDoublewordElement(40378), SCALE_FACTOR_2),
					new DummyRegisterElement(40380,40381),
					m(ElectricityMeter.ChannelId.ACTIVE_POWER,
							new SignedDoublewordElement(40382))
				));
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	private void calculateEnergyProductionToday(Long energyProductionTotal) {
		var today = ZonedDateTime.now().toLocalDate();
		if (energyProductionTotal == null) {
			this.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY)
					.setNextValue(this.energyProductionTodayCalculator.update(today, null));
			return;
		}
		var isNewDay = !today.equals(this.energyProductionTodayDay);
		this.energyProductionTodayDay = today;
		this.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY)
				.setNextValue(this.energyProductionTodayCalculator.update(today, energyProductionTotal));
		if (isNewDay) {
			this.restoreEnergyProductionTotalAtStartOfDay();
		}
	}

	private void restoreEnergyProductionTotalAtStartOfDay() {
		final var currentTimedata = this.timedata;
		if (currentTimedata == null) {
			return;
		}

		CompletableFuture.runAsync(() -> {
			final var now = ZonedDateTime.now();
			final var startOfDay = now.toLocalDate().atStartOfDay(now.getZone());
			final var address = new ChannelAddress(this.id(), SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL.id());

			try {
				var historicData = currentTimedata.queryHistoricData(null, startOfDay, now, Set.of(address),
						new Resolution(1, ChronoUnit.MINUTES));
				if (historicData == null || historicData.isEmpty()) {
					return;
				}

				Long firstTotalOfDay = null;
				for (var row : historicData.values()) {
					var value = JsonUtils.getAsOptionalLong(row.get(address)).orElse(null);
					if (value != null) {
						firstTotalOfDay = value;
						break;
					}
				}
				if (firstTotalOfDay == null) {
					return;
				}

				this.channel(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY).setNextValue(
						this.energyProductionTodayCalculator.restore(now.toLocalDate(), firstTotalOfDay));
			} catch (Exception e) {
				// Keep runtime calculation as fallback if historic data is unavailable.
			}
		});
	}
}
