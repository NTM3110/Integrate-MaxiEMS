package io.openems.edge.data.smartlogger;

import static io.openems.edge.common.claim.Data.SINGLETON_COMPONENT_ID;
import static io.openems.edge.common.claim.Data.SINGLETON_SERVICE_PID;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

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
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.JsonElement;

import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = SINGLETON_SERVICE_PID, //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.OPTIONAL, //
		property = {
				"enabled = true"
		}
)
public class SmartLoggerDataImpl extends AbstractOpenemsComponent implements SmartLoggerData, Controller, OpenemsComponent {

	@Reference
	protected ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference(//
			policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL //
	)
	private volatile Timedata timedata;

	private Config config = null;
	private LocalDate currentDate = null;
	private float maxEnergyTodayCache = 0f;

	// True once the one-time startup restore of yesterday's energy has been attempted
	// with a live Timedata reference. Prevents infinite retries when there is simply
	// no historical data.
	private boolean yesterdayEnergyRestored = false;

	public SmartLoggerDataImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				SmartLoggerData.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		super.activate(context, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);
		this.config = config;

		// For factory=false singletons there is never a second instance to deactivate,
		// so we must NOT return early: returning early leaves the component half-registered
		// and OsgiValidateWorker marks it as "Missing Bundle" with no auto-restart.
		OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID);

		// Timedata is OPTIONAL+DYNAMIC so it may not be bound yet. If restore fails,
		// apply the config fallback and retry from run() once Timedata is available.
		if (this.restoreYesterdayEnergyOnStartup()) {
			this.yesterdayEnergyRestored = true;
		} else {
			this.applyInitialEnergyYesterday();
		}
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);

		if (Float.compare(config.initialEnergyYesterday(), this.config.initialEnergyYesterday()) != 0
				&& config.initialEnergyYesterday() != 0) {
			this.channel(SmartLoggerData.ChannelId.ENERGY_YESTERDAY).setNextValue(config.initialEnergyYesterday());
		}

		this.config = config;

		// Same reasoning as activate(): never return early for a singleton.
		OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID);

		if (!this.yesterdayEnergyRestored) {
			if (this.restoreYesterdayEnergyOnStartup()) {
				this.yesterdayEnergyRestored = true;
			} else {
				this.applyInitialEnergyYesterday();
			}
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Retry restore until both Timedata and at least one inverter are available.
		// Once inverters are up, attempt once: if history exists use it, else fall back to config.
		if (!this.yesterdayEnergyRestored && this.timedata != null) {
			var hasInverters = !this.componentManager
					.getEnabledComponentsOfType(ManagedSymmetricPvInverter.class).isEmpty();
			if (hasInverters) {
				if (!this.restoreYesterdayEnergyOnStartup()) {
					this.applyInitialEnergyYesterday();
				}
				this.yesterdayEnergyRestored = true;
			}
		}

		// Single pass over all enabled inverters
		float currentDailyEnergy = 0f;
		float ratePowerKw = 0f;
		for (var inverter : this.componentManager.getEnabledComponentsOfType(ManagedSymmetricPvInverter.class)) {
			currentDailyEnergy += this.readEnergyProductionToday(inverter);
			ratePowerKw += this.readRatePower(inverter);
		}

		// Track the daily maximum to survive inverter resets near midnight
		this.maxEnergyTodayCache = Math.max(this.maxEnergyTodayCache, currentDailyEnergy);
		this.channel(SmartLoggerData.ChannelId.MAX_ENERGY_TODAY).setNextValue(this.maxEnergyTodayCache);
		this.channel(SmartLoggerData.ChannelId.CAPACITY).setNextValue(ratePowerKw);

		var today = LocalDate.now(this.componentManager.getClock());
		if (this.currentDate == null) {
			this.currentDate = today;
			return;
		}

		// Day rollover: save today's peak as yesterday's energy and reset for new day.
		// Reset to 0 (not currentDailyEnergy) so the new day's max tracks correctly
		// even if the inverter hasn't reset to 0 at the exact rollover moment.
		if (!today.equals(this.currentDate)) {
			this.channel(SmartLoggerData.ChannelId.ENERGY_YESTERDAY).setNextValue(this.maxEnergyTodayCache);
			this.channel(SmartLoggerData.ChannelId.DEBUG_LAST_DAILY_ENERGY).setNextValue(this.maxEnergyTodayCache);
			this.maxEnergyTodayCache = 0f;
			this.currentDate = today;
		}
	}

	/**
	 * Reads today's energy production from a single inverter's EnergyProductionToday channel.
	 *
	 * @return energy in kWh, or 0 if the channel is absent or has no value
	 */
	private float readEnergyProductionToday(ManagedSymmetricPvInverter inverter) {
		var channel = this.getOptionalChannel(inverter, "EnergyProductionToday");
		if (channel == null || !channel.value().isDefined()) {
			return 0f;
		}
		var kwh = this.convertToKiloWattHours(channel.value().get(), channel.channelDoc().getUnit());
		return kwh != null ? kwh : 0f;
	}

	/**
	 * Reads the rated power from a single inverter. Falls back through RatePower →
	 * _PropertyRatePower → MaxActivePower.
	 *
	 * @return rated power in kW, or 0 if no suitable channel is found
	 */
	private float readRatePower(ManagedSymmetricPvInverter inverter) {
		Channel<?> channel = this.getOptionalChannel(inverter, "RatePower");
		if (channel == null || !channel.value().isDefined()) {
			channel = this.getOptionalChannel(inverter, "_PropertyRatePower");
		}
		if (channel == null || !channel.value().isDefined()) {
			channel = this.getOptionalChannel(inverter, "MaxActivePower");
		}
		if (channel == null || !channel.value().isDefined()) {
			return 0f;
		}
		var kw = this.convertToKiloWatts(channel.value().get(), channel.channelDoc().getUnit());
		return kw != null ? kw : 0f;
	}

	/**
	 * Calculates total daily energy from all enabled PV inverters in kWh.
	 *
	 * @return summed daily energy [kWh]
	 */
	public float calculateTotalEnergy() {
		float totalKwh = 0f;
		for (var inverter : this.componentManager.getEnabledComponentsOfType(ManagedSymmetricPvInverter.class)) {
			totalKwh += this.readEnergyProductionToday(inverter);
		}
		return totalKwh;
	}

	private Channel<?> getOptionalChannel(OpenemsComponent component, String channelId) {
		try {
			return component.channel(channelId);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private boolean restoreYesterdayEnergyOnStartup() {
		var yesterday = LocalDate.now(this.componentManager.getClock()).minusDays(1);
		Float yesterdayEnergy = this.queryTotalEnergyForDate(yesterday);
		if (yesterdayEnergy == null) {
			return false;
		}

		this.channel(SmartLoggerData.ChannelId.ENERGY_YESTERDAY).setNextValue(yesterdayEnergy);
		this.channel(SmartLoggerData.ChannelId.DEBUG_LAST_DAILY_ENERGY).setNextValue(yesterdayEnergy);
		return true;
	}

	private void applyInitialEnergyYesterday() {
		if (this.config.initialEnergyYesterday() == 0) {
			return;
		}

		this.channel(SmartLoggerData.ChannelId.ENERGY_YESTERDAY).setNextValue(this.config.initialEnergyYesterday());
		this.channel(SmartLoggerData.ChannelId.DEBUG_LAST_DAILY_ENERGY).setNextValue(this.config.initialEnergyYesterday());
	}

	private Float queryTotalEnergyForDate(LocalDate date) {
		var currentTimedata = this.timedata;
		if (currentTimedata == null) {
			return null;
		}

		Map<ChannelAddress, Unit> unitsByChannel = new HashMap<>();
		Set<ChannelAddress> channelAddresses = new HashSet<>();
		for (var inverter : this.componentManager.getEnabledComponentsOfType(ManagedSymmetricPvInverter.class)) {
			var channel = this.getOptionalChannel(inverter, "EnergyProductionToday");
			if (channel == null) {
				continue;
			}
			var channelAddress = new ChannelAddress(inverter.id(), "EnergyProductionToday");
			channelAddresses.add(channelAddress);
			unitsByChannel.put(channelAddress, channel.channelDoc().getUnit());
		}
		if (channelAddresses.isEmpty()) {
			return null;
		}

		var zone = this.componentManager.getClock().getZone();
		var fromDate = date.atStartOfDay(zone);
		var toDate = date.plusDays(1).atStartOfDay(zone);

		try {
			SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> historicData = currentTimedata.queryHistoricData(//
					null, //
					fromDate, //
					toDate, //
					channelAddresses, //
					new Resolution(1, ChronoUnit.MINUTES));
			if (historicData == null || historicData.isEmpty()) {
				return null;
			}

			// Take the max value per inverter across the day (channel is cumulative, rising)
			Map<ChannelAddress, Float> maxKwhByChannel = new HashMap<>();
			for (var row : historicData.values()) {
				for (var channelAddress : channelAddresses) {
					Float rawValue = JsonUtils.getAsOptionalFloat(row.get(channelAddress)).orElse(null);
					Float valueKwh = this.convertToKiloWattHours(rawValue, unitsByChannel.get(channelAddress));
					if (valueKwh == null) {
						continue;
					}
					maxKwhByChannel.merge(channelAddress, valueKwh, Math::max);
				}
			}

			if (maxKwhByChannel.isEmpty()) {
				return null;
			}
			float totalKwh = 0f;
			for (var valueKwh : maxKwhByChannel.values()) {
				totalKwh += valueKwh;
			}
			return totalKwh;
		} catch (OpenemsNamedException e) {
			return null;
		}
	}

	@Override
	public String debugLog() {
		return "EnergyYesterday=" + this.channel(SmartLoggerData.ChannelId.ENERGY_YESTERDAY).value().asString()
				+ " MaxEnergyToday=" + this.channel(SmartLoggerData.ChannelId.MAX_ENERGY_TODAY).value().asString();
	}

	/**
	 * Converts a numeric energy value to kWh.
	 *
	 * @param value source value
	 * @param unit  source unit
	 * @return value in kWh; {@code null} if unsupported or value is not a Number
	 */
	private Float convertToKiloWattHours(Object value, Unit unit) {
		if (!(value instanceof Number)) {
			return null;
		}
		var safeUnit = (unit != null) ? unit : Unit.WATT_HOURS;
		double numericValue = ((Number) value).doubleValue();
		return switch (safeUnit) {
			case WATT_HOURS -> (float) (numericValue / 1000.0);
			case KILOWATT_HOURS -> (float) numericValue;
			default -> null;
		};
	}

	private Float convertToKiloWatts(Object value, Unit unit) {
		if (!(value instanceof Number)) {
			return null;
		}
		var safeUnit = (unit != null) ? unit : Unit.WATT;
		double numericValue = ((Number) value).doubleValue();
		return switch (safeUnit) {
			case WATT -> (float) (numericValue / 1000.0);
			case KILOWATT -> (float) numericValue;
			default -> null;
		};
	}
}
