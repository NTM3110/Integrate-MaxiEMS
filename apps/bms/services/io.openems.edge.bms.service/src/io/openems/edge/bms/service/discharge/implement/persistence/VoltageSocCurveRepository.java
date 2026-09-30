package io.openems.edge.bms.service.discharge.implement.persistence;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.calculation.model.VoltageSocCurve;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Loads the active, string-level baseline curve for later use by SoC engines. */
@Component(name = "io.openems.edge.bms.platform.discharge.VoltageSocCurveRepository", service = VoltageSocCurveRepository.class, immediate = true)
public class VoltageSocCurveRepository {

	@Reference
	private BmsDatabase database;
	private final Map<Integer, VoltageSocCurve> cache = new ConcurrentHashMap<>();

	public Optional<VoltageSocCurve> get(int stringIndex) {
		var curve = this.cache.computeIfAbsent(stringIndex, this::load);
		return curve.isUsable() ? Optional.of(curve) : Optional.empty();
	}

	public void refresh(int stringIndex) {
		this.cache.put(stringIndex, this.load(stringIndex));
	}

	private VoltageSocCurve load(int stringIndex) {
		var points = new ArrayList<VoltageSocCurve.Point>();
		try (var connection = this.database.getConnection(); var query = connection.prepareStatement("""
				SELECT curve.voltage_v,curve.soc_pct
				FROM bms.soc_voltage_curve curve
				JOIN bms.bms_string string ON string.id=curve.string_id
				WHERE string.string_index=? AND curve.active=true
				ORDER BY curve.voltage_v
				""")) {
			query.setInt(1, stringIndex);
			try (var result = query.executeQuery()) {
				while (result.next()) points.add(new VoltageSocCurve.Point(result.getDouble(1), result.getDouble(2)));
			}
		} catch (Exception ignored) {
			// The caller falls back to the configured linear estimate until a curve exists.
		}
		return new VoltageSocCurve(points);
	}
}
