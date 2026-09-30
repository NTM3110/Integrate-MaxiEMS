package io.openems.edge.bridge.edmi.csvinjector;

import java.nio.file.Path;
import java.time.Instant;

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

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.controller.energy.calculator.api.EnergyCalculator;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Bridge.EDMI.CsvInjector", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class CsvProfileInjector extends AbstractOpenemsComponent implements OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(CsvProfileInjector.class);
	private volatile EdmiBridge edmiBridge;

	private volatile EnergyCalculator energyCalculator;

	private Thread injectorThread;
	private volatile boolean stopThread = false;
	@Reference
	void bindEdmiBridge(EdmiBridge edmiBridge) {
		this.edmiBridge = edmiBridge;
	}

	void unbindEdmiBridge(EdmiBridge edmiBridge) {
		if (this.edmiBridge == edmiBridge) {
			this.edmiBridge = null;
		}
	}
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, target = "(component.name=Controller.Energy.Calculator)")
	void bindEnergyCalculator(EnergyCalculator energyCalculator) {
		this.log.info("EnergyCalculator bound: {}", energyCalculator);
		this.energyCalculator = energyCalculator;
	}

	void unbindEnergyCalculator(EnergyCalculator energyCalculator) {
		this.log.info("EnergyCalculator unbound: {}", energyCalculator);
		if (this.energyCalculator == energyCalculator) {
			this.energyCalculator = null;
		}
	}

	public CsvProfileInjector() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
//		if (config.enabled()) {
			this.startInjection(config);
//		}
//        else {
//
//        }
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.stopThread = true;
		if (this.injectorThread != null) {
			this.injectorThread.interrupt();
		}
		super.deactivate();
	}

	private void startInjection(Config config)
    {
        this.log.info("------------------------ Activate CSC Profile injection -------------------------");
		this.stopThread = false;
		var options = new CsvProfileInjectionService.Options(//
				Path.of(config.csvPath()), //
				config.injectionDelayMs(), //
				config.queryDelayMs(), //
				config.timestampOffsetMinutes(), //
				() -> this.stopThread, //
				this::calculateEnergyForProfileTimestamp);

		this.injectorThread = new Thread(() -> this.runInjection(options), "csv-injector-" + this.id());
		this.injectorThread.setDaemon(true);
		this.injectorThread.start();
	}
	private void calculateEnergyForProfileTimestamp(Instant timestamp) {
		var calculator = this.energyCalculator;
		if (calculator == null) {
			this.log.warn("No EnergyCalculator available for timestamp [{}]", timestamp);
			return;
		}
		try {
			this.log.info("Triggering energy calculation for timestamp [{}]", timestamp);
			calculator.calculateForIntervalEnding(timestamp);
			this.log.info("Energy calculation completed for timestamp [{}]", timestamp);
		} catch (Exception e) {
			this.log.error("Energy calculation failed for timestamp [{}]: {}", timestamp, e.getMessage(), e);
		}
	}

	private void runInjection(CsvProfileInjectionService.Options options) {
		try {
			this.log.info("Starting CSV profile injection from [{}] with timestampOffsetMinutes=[{}]",
					options.csvPath(), options.timestampOffsetMinutes());
			var bridge = this.edmiBridge;
			if (bridge == null) {
				this.log.warn("CSV injection skipped because Bridge.EDMI is not available.");
				return;
			}
			var result = CsvProfileInjectionService.inject(bridge, options, this.log);
			this.log.info("CSV injection complete. intervals=[{}], rows=[{}], first=[{}], last=[{}]",
					result.intervalCount(), result.rowCount(), result.firstTimestamp(), result.lastTimestamp());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.log.info("CSV injection interrupted.");
		} catch (Exception e) {
			this.log.error("CSV injection failed: {}", e.getMessage(), e);
		}
	}
}
