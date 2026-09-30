package io.openems.edge.bms.service.calibration.implement;

import java.util.Map;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;

import io.openems.edge.bms.service.reference.contracts.ActiveCalibrationEstimatorContextProvider;
import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;

/** Atomic in-memory bridge from the calibration worker to realtime calculators. */
@Component(name = "Bms.ActiveCalibrationEstimatorContextCache",
		service = { ActiveCalibrationEstimatorContextCache.class, ActiveCalibrationEstimatorContextProvider.class })
public class ActiveCalibrationEstimatorContextCache implements ActiveCalibrationEstimatorContextProvider {

	private volatile Map<String, ActiveCalibrationEstimatorContext> byStringKey = Map.of();

	@Override
	public Optional<ActiveCalibrationEstimatorContext> activeByStringKey(String stringKey) {
		return Optional.ofNullable(this.byStringKey.get(stringKey));
	}

	void publish(Map<String, ActiveCalibrationEstimatorContext> contexts) {
		var previous = this.byStringKey;
		var accepted = new java.util.LinkedHashMap<String, ActiveCalibrationEstimatorContext>();
		for (var entry : contexts.entrySet()) {
			var context = entry.getValue();
			if (context == null || !entry.getKey().equals(context.stringKey())) continue;
			var old = previous.get(entry.getKey());
			if (context.newerThan(old)) accepted.put(entry.getKey(), context);
			else if (old != null) accepted.put(entry.getKey(), old);
		}
		this.byStringKey = Map.copyOf(accepted);
	}
}
