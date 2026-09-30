package io.openems.edge.ess.deye;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.ess.power.api.Pwr.REACTIVE;
import static io.openems.edge.ess.power.api.Relationship.EQUALS;
import static io.openems.edge.ess.power.api.Relationship.GREATER_OR_EQUALS;
import static io.openems.edge.ess.power.api.Relationship.LESS_OR_EQUALS;

import java.io.IOException;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.deye.hybrid.PvInverterDeyeHybrid;

@Designate(ocd = DeyeHybridEssConfig.class, factory = true)
@Component(//
		name = "Ess.Deye.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class DeyeHybridEssImpl extends AbstractOpenemsComponent
		implements DeyeHybridEss, ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler, ModbusSlave {

	private static final int MAX_UNSIGNED_WORD = 0xFFFF;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.MANDATORY)
	private PvInverterDeyeHybrid inverter;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private Power power;

	private DeyeHybridEssConfig config;

	public DeyeHybridEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values());
		this._setGridMode(GridMode.UNDEFINED);
		this._setAllowedChargePower(0);
		this._setAllowedDischargePower(0);
	}

	@Activate
	private void activate(ComponentContext context, DeyeHybridEssConfig config)
			throws OpenemsNamedException, ConfigurationException, IOException {
		validateConfig(config);
		this.config = config;
		super.activate(context, config.id(), config.alias(), config.enabled());
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "inverter", config.inverter_id())) {
			return;
		}
		this._setCapacity(config.capacity());
		this._setGridMode(GridMode.UNDEFINED);
		this.updateEssChannels();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private static void validateConfig(DeyeHybridEssConfig config) throws OpenemsException {
		if (config.capacity() < 0) {
			throw new OpenemsException("Battery capacity override must not be negative");
		}
		if (config.maxChargePower() < 0) {
			throw new OpenemsException("Maximum charge power override must not be negative");
		}
		if (config.maxDischargePower() < 0) {
			throw new OpenemsException("Maximum discharge power override must not be negative");
		}
		if (config.minSoc() < 0 || config.maxSoc() > 100 || config.minSoc() >= config.maxSoc()) {
			throw new OpenemsException("SOC limits must satisfy 0 <= minimum < maximum <= 100");
		}
		validateOptionalUnsignedWord(config.remoteEnableValue(), "Remote-enable value");
		validateOptionalUnsignedWord(config.emsModeValue(), "EMS-mode value");
		validateOptionalUnsignedWord(config.heartbeatValue(), "Heartbeat value");
	}

	private static void validateOptionalUnsignedWord(int value, String label) throws OpenemsException {
		if (value < -1 || value > MAX_UNSIGNED_WORD) {
			throw new OpenemsException(label + " must be -1 or fit an unsigned 16-bit register");
		}
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || !EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE.equals(event.getTopic())) {
			return;
		}
		this.updateEssChannels();
	}

	private void updateEssChannels() {
		var soc = this.inverter.getBatterySoc().get();
		var batteryPower = invertIf(this.inverter.getBatteryPower().get(), this.config.invertTelemetrySign());
		this._setSoc(soc);
		this._setActivePower(batteryPower);
		this._setReactivePower(0);
		this._setActiveChargeEnergy(this.inverter.getBatteryChargeEnergy().get());
		this._setActiveDischargeEnergy(this.inverter.getBatteryDischargeEnergy().get());
		this._setGridMode(GridMode.UNDEFINED);
		this.updateAllowedPower(soc);
	}

	private void updateAllowedPower(Integer soc) {
		var inverterPower = this.getNominalBatteryPower();
		this._setMaxApparentPower(inverterPower);
		if (!this.isControlReady() || soc == null) {
			this._setAllowedChargePower(0);
			this._setAllowedDischargePower(0);
			return;
		}

		var voltage = this.inverter.getBatteryVoltage().get();
		var chargeCurrent = this.inverter.getBmsMaxChargeCurrent().get();
		var dischargeCurrent = this.inverter.getBmsMaxDischargeCurrent().get();
		var bmsChargePower = calculatePowerFromMilli(voltage, chargeCurrent);
		var bmsDischargePower = applyDischargeEfficiency(calculatePowerFromMilli(voltage, dischargeCurrent));

		var allowedCharge = soc >= this.config.maxSoc() //
				? 0 //
				: resolvePowerLimit(this.config.maxChargePower(), bmsChargePower, inverterPower);
		var allowedDischarge = soc <= this.config.minSoc() //
				? 0 //
				: resolvePowerLimit(this.config.maxDischargePower(), bmsDischargePower, inverterPower);
		this._setMaxApparentPower(maxPositive(allowedCharge, allowedDischarge, inverterPower));
		this._setAllowedChargePower(-allowedCharge);
		this._setAllowedDischargePower(allowedDischarge);
	}

	private boolean isControlReady() {
		return this.config != null //
				&& this.config.remoteEnableValue() >= 0 //
				&& this.config.emsModeValue() >= 0 //
				&& Boolean.TRUE.equals(this.inverter.getWriteEnabled().get());
	}

	static int resolvePowerLimit(int configuredPower, Integer bmsPower, Integer inverterPower) {
		if (bmsPower == null || bmsPower <= 0 || inverterPower == null || inverterPower <= 0) {
			return 0;
		}
		var result = Math.min(bmsPower, inverterPower);
		return configuredPower <= 0 ? result : Math.min(configuredPower, result);
	}

	private static int maxPositive(Integer... values) {
		var result = 0;
		for (var value : values) {
			if (value != null && value > result) {
				result = value;
			}
		}
		return result;
	}

	private Integer getNominalBatteryPower() {
		return this.inverter.getMaxApparentPower().get();
	}

	static Integer calculatePowerFromMilli(Integer millivolt, Integer milliampere) {
		if (millivolt == null || milliampere == null || millivolt <= 0 || milliampere < 0) {
			return null;
		}
		return Math.toIntExact((long) millivolt * milliampere / 1_000_000L);
	}

	private static Integer applyDischargeEfficiency(Integer dcPower) {
		return dcPower == null ? null : Math.toIntExact((long) dcPower * 95L / 100L);
	}

	private static Integer invertIf(Integer value, boolean invert) {
		return value == null || !invert ? value : Math.negateExact(value);
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (!this.isControlReady()) {
			throw new OpenemsException(
					"Deye EMS control is blocked: enable driver writes and configure vendor-confirmed remote/EMS values");
		}
		if (reactivePower != 0) {
			throw new OpenemsException("Deye ESS adapter does not support reactive-power setpoints");
		}

		this.updateAllowedPower(this.inverter.getBatterySoc().get());
		var allowedCharge = this.getAllowedChargePower().orElse(0);
		var allowedDischarge = this.getAllowedDischargePower().orElse(0);
		if (activePower < allowedCharge || activePower > allowedDischarge) {
			throw new OpenemsException("Active-power setpoint is outside current charge/discharge limits");
		}

		var nominalPower = this.getNominalBatteryPower();
		if (nominalPower == null || nominalPower <= 0) {
			throw new OpenemsException("Deye rated power is not available");
		}
		var rawReference = toRawPowerReference(activePower, nominalPower, this.config.invertSetpointSign());
		this.inverter.setRemoteEnableRequest(this.config.remoteEnableValue());
		this.inverter.setEmsModeRequest(this.config.emsModeValue());
		this.inverter.setReserveSocRequest(this.config.minSoc());
		this.inverter.setGridChargeEnableRequest(this.config.gridChargeEnabled());
		if (this.config.heartbeatValue() >= 0) {
			this.inverter.setHeartbeatRequest(this.config.heartbeatValue());
		}
		this.inverter.setBatteryPowerReferenceRequest(rawReference);

		if (this.inverter.getLastCommandResultChannel().getNextValue()
				.orElse(CommandResult.NONE.getValue()) != CommandResult.ACCEPTED.getValue()) {
			throw new OpenemsException("Deye driver rejected EMS setpoint: "
					+ this.inverter.getLastCommandRejectionReasonChannel().getNextValue().orElse("unknown reason"));
		}
	}

	static int toRawPowerReference(int activePower, int nominalPower, boolean invert) throws OpenemsException {
		if (nominalPower <= 0) {
			throw new OpenemsException("Nominal battery power must be positive");
		}
		var raw = Math.round((double) activePower * 1000D / nominalPower);
		if (invert) {
			raw = -raw;
		}
		if (Math.abs(raw) > 1000) {
			throw new OpenemsException("Battery power reference exceeds +/-100.0 %Pn");
		}
		return (int) raw;
	}

	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		var allowedCharge = this.getAllowedChargePower().orElse(0);
		var allowedDischarge = this.getAllowedDischargePower().orElse(0);
		return new Constraint[] { //
				this.createPowerConstraint("Deye does not expose ESS reactive control", ALL, REACTIVE, EQUALS, 0), //
				this.createPowerConstraint("Deye allowed charge power", ALL, ACTIVE, GREATER_OR_EQUALS,
						allowedCharge), //
				this.createPowerConstraint("Deye allowed discharge power", ALL, ACTIVE, LESS_OR_EQUALS,
						allowedDischarge) //
		};
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {
		if (this.config == null) {
			return 1;
		}
		var nominalPower = this.getNominalBatteryPower();
		return nominalPower == null ? 1 : Math.max(1, (int) Math.ceil(nominalPower / 1000D));
	}

	@Override
	public boolean isManaged() {
		return this.isControlReady();
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|Bat:" + this.getActivePower().asString() //
				+ "|Allowed:" + this.getAllowedChargePower().asStringWithoutUnit() + ";"
				+ this.getAllowedDischargePower().asString();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(io.openems.common.channel.AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode));
	}

}
