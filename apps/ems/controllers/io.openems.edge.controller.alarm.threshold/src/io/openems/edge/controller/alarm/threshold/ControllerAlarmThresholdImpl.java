package io.openems.edge.controller.alarm.threshold;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.notification.email.EmailNotificationService;
import io.openems.edge.notification.email.EnqueueResult;
import io.openems.edge.wika.api.WikaAms01Alarm;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.Wika.Alarm", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerAlarmThresholdImpl extends AbstractOpenemsComponent
		implements ControllerAlarmThreshold, Controller, OpenemsComponent {
	private final Logger log = LoggerFactory.getLogger(ControllerAlarmThresholdImpl.class);
	private final Map<AlarmKey, AlarmEvaluator> evaluators = new HashMap<>();
	private final List<AlarmTransition> batch = new ArrayList<>();

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile EmailNotificationService emailService;
	private String plantName;
	private Duration debounce = Duration.ofSeconds(10);
	private Duration batchWindow = Duration.ofSeconds(10);
	private Instant batchStarted;
	private boolean enqueueFailureLogged;

	public ControllerAlarmThresholdImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values(),
				ControllerAlarmThreshold.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		if (config.plantName().isBlank() || config.email_id().isBlank() || config.debounceSeconds() < 0
				|| config.batchWindowSeconds() < 0) {
			throw new OpenemsNamedException(OpenemsError.GENERIC, "Invalid Wika alarm controller configuration");
		}
		this.plantName = config.plantName();
		this.debounce = Duration.ofSeconds(config.debounceSeconds());
		this.batchWindow = Duration.ofSeconds(config.batchWindowSeconds());
		this.evaluators.clear();
		this.batch.clear();
		this.batchStarted = null;
		this.enqueueFailureLogged = false;
		super.activate(context, config.id(), config.alias(), config.enabled());
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "emailService", config.email_id())) {
			return;
		}
	}

	@Deactivate
	@Override
	protected void deactivate() {
		this.batch.clear();
		this.evaluators.clear();
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		var now = Instant.now(this.componentManager.getClock());
		var invalidValue = false;
		var wikaCount = 0;
		var activeWikaIds = new HashSet<String>();
		for (var component : this.componentManager.getEnabledComponents()) {
			if (component instanceof WikaAms01Alarm wika) {
				wikaCount++;
				activeWikaIds.add(wika.id());
				for (var spec : AlarmSpec.VALUES) {
					try {
						int value = TypeUtils.getAsType(OpenemsType.INTEGER,
								wika.channel(spec.flagChannel()).value().getOrError());
						if (!spec.type().isValid(value)) {
							invalidValue = true;
							continue;
						}
						this.evaluate(wika, spec, value, now);
					} catch (Exception e) {
						// Missing Modbus data must never be interpreted as a recovery.
					}
				}
			}
		}
		this.evaluators.keySet().removeIf(key -> !activeWikaIds.contains(key.componentId()));
		this.channel(ControllerAlarmThreshold.ChannelId.NO_WIKA_FOUND).setNextValue(wikaCount == 0);
		this.channel(ControllerAlarmThreshold.ChannelId.INVALID_ALARM_VALUE).setNextValue(invalidValue);
		this.flushBatchIfDue(now);
		this.updateCounts();
	}

	private void evaluate(WikaAms01Alarm wika, AlarmSpec spec, int value, Instant now) {
		var key = new AlarmKey(wika.id(), spec.type(), spec.compartment());
		var evaluator = this.evaluators.computeIfAbsent(key, ignored -> new AlarmEvaluator());
		evaluator.evaluate(value, now, this.debounce).ifPresent(change -> {
			var timestamp = ZonedDateTime.ofInstant(now, this.componentManager.getClock().getZone());
			var alarmStartedAt = change.alarmStartedAt() == null ? timestamp
					: ZonedDateTime.ofInstant(change.alarmStartedAt(), this.componentManager.getClock().getZone());
			var deviationValue = this.readDeviationValue(wika, spec);
			this.batch.add(new AlarmTransition(wika.id(), wika.alias(), spec.type(), spec.compartment(),
					change.oldLevel(), change.newLevel(), timestamp, deviationValue, alarmStartedAt,
					change.alarmDuration()));
			if (this.batchStarted == null) {
				this.batchStarted = now;
			}
		});
	}

	private String readDeviationValue(WikaAms01Alarm wika, AlarmSpec spec) {
		if (spec.valueChannel() == null) {
			return null;
		}
		var value = wika.channel(spec.valueChannel()).value().asStringWithoutUnit();
		if ("UNDEFINED".equals(value)) {
			return "Không có dữ liệu";
		}
		return value + spec.valueUnit();
	}

	private void flushBatchIfDue(Instant now) {
		if (this.batchStarted == null || Duration.between(this.batchStarted, now).compareTo(this.batchWindow) < 0) {
			return;
		}
		var service = this.emailService;
		if (service == null) {
			this.channel(ControllerAlarmThreshold.ChannelId.EMAIL_ENQUEUE_FAILED).setNextValue(true);
			return;
		}
		var result = service.enqueue(AlarmEmailRenderer.render(this.plantName, List.copyOf(this.batch)));
		if (result == EnqueueResult.ACCEPTED) {
			this.batch.clear();
			this.batchStarted = null;
			this.channel(ControllerAlarmThreshold.ChannelId.EMAIL_ENQUEUE_FAILED).setNextValue(false);
			this.enqueueFailureLogged = false;
		} else {
			this.channel(ControllerAlarmThreshold.ChannelId.EMAIL_ENQUEUE_FAILED).setNextValue(true);
			if (!this.enqueueFailureLogged) {
				this.log.warn("Unable to enqueue Wika alarm email: {}", result);
				this.enqueueFailureLogged = true;
			}
		}
	}

	private void updateCounts() {
		var active = this.evaluators.values().stream().filter(evaluator -> evaluator.confirmedLevel() > 0).count();
		var pendingTransitions = this.evaluators.values().stream().filter(AlarmEvaluator::isPending).count();
		this.channel(ControllerAlarmThreshold.ChannelId.ACTIVE_ALARM_COUNT).setNextValue((int) active);
		this.channel(ControllerAlarmThreshold.ChannelId.PENDING_TRANSITION_COUNT).setNextValue((int) pendingTransitions);
	}

	@Override
	public String debugLog() {
		return "Active:" + this.channel(ControllerAlarmThreshold.ChannelId.ACTIVE_ALARM_COUNT).value().asString()
				+ " Pending:"
				+ this.channel(ControllerAlarmThreshold.ChannelId.PENDING_TRANSITION_COUNT).value().asString();
	}
}
