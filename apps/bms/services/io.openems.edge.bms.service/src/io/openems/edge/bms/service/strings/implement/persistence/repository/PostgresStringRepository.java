package io.openems.edge.bms.service.strings.implement.persistence.repository;

import io.openems.edge.bms.service.strings.contracts.StringRepository;

import io.openems.edge.bms.service.strings.model.PersistentStringModels;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;
import io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition;

@Component(name = "io.openems.edge.bms.platform.strings.PostgresStringRepository", service = StringRepository.class)
public class PostgresStringRepository implements StringRepository {

	@Reference
	private BmsDatabase database;

	@Override
	public List<Definition> list() throws SQLException {
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("""
					SELECT s.*, t.low_voltage, t.high_voltage, t.high_temperature, t.high_resistance
					FROM bms.bms_string s LEFT JOIN bms.string_threshold t ON t.string_id = s.id
					ORDER BY s.string_index
					""" );
				var result = statement.executeQuery()) {
			var definitions = new ArrayList<Definition>();
			while (result.next()) definitions.add(map(result));
			return definitions;
		}
	}

	@Override
	public Optional<Definition> findByIndex(int stringIndex) throws SQLException {
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("""
					SELECT s.*, t.low_voltage, t.high_voltage, t.high_temperature, t.high_resistance
					FROM bms.bms_string s LEFT JOIN bms.string_threshold t ON t.string_id = s.id WHERE s.string_index = ?
					""")) {
			statement.setInt(1, stringIndex);
			try (var result = statement.executeQuery()) { return result.next() ? Optional.of(map(result)) : Optional.empty(); }
		}
	}

	@Override
	public Definition synchronize(io.openems.edge.bms.service.strings.model.BmsStringModels.Definition source)
			throws SQLException {
		var key = source.id();
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				var id = resolveIdentity(connection, source.stringIndex(), key);
				try (var upsert = connection.prepareStatement("""
						INSERT INTO bms.bms_string(id, string_index, string_key, display_name, cell_count, brand, model,
							capacity_ah, bridge_id, start_ta_unit_id, cutoff_voltage, float_voltage, boost_voltage,
							rated_resistance_uohm,soc_checkpoint_policy)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT (id) DO UPDATE SET string_index=EXCLUDED.string_index,
							string_key=EXCLUDED.string_key, display_name=EXCLUDED.display_name,
							cell_count=EXCLUDED.cell_count, brand=EXCLUDED.brand, model=EXCLUDED.model,
							capacity_ah=EXCLUDED.capacity_ah, bridge_id=EXCLUDED.bridge_id,
							start_ta_unit_id=EXCLUDED.start_ta_unit_id, cutoff_voltage=EXCLUDED.cutoff_voltage,
							float_voltage=EXCLUDED.float_voltage, boost_voltage=EXCLUDED.boost_voltage,
							rated_resistance_uohm=EXCLUDED.rated_resistance_uohm,
							soc_checkpoint_policy=EXCLUDED.soc_checkpoint_policy, updated_at=now()
						"""); var thresholds = connection.prepareStatement("""
						INSERT INTO bms.string_threshold(string_id, low_voltage, high_voltage, high_temperature,
							high_resistance, rated_resistance_uohm)
						VALUES (?, ?, ?, ?, ?, ?)
						ON CONFLICT (string_id) DO UPDATE SET low_voltage=EXCLUDED.low_voltage,
							high_voltage=EXCLUDED.high_voltage, high_temperature=EXCLUDED.high_temperature,
							high_resistance=EXCLUDED.high_resistance,
							rated_resistance_uohm=EXCLUDED.rated_resistance_uohm, updated_at=now()
						""")) {
					upsert.setObject(1, id); upsert.setInt(2, source.stringIndex()); upsert.setString(3, key);
					upsert.setString(4, source.stringName()); upsert.setInt(5, source.cellQty());
					upsert.setString(6, source.cellBrand()); upsert.setString(7, source.cellModel());
					upsert.setDouble(8, source.ratedCapacity()); upsert.setString(9, source.bridgeId());
					upsert.setInt(10, source.startTaUnitId()); upsert.setDouble(11, source.cutoffVoltage());
					upsert.setDouble(12, source.floatVoltage()); upsert.setDouble(13, source.boostVoltage());
					upsert.setDouble(14, source.rNew()); upsert.setString(15, source.socCheckpointPolicy().name());
					upsert.executeUpdate();
					thresholds.setObject(1, id); thresholds.setDouble(2, source.lowVoltageThreshold());
					thresholds.setDouble(3, source.highVoltageThreshold());
					thresholds.setDouble(4, source.highTemperatureThreshold());
					thresholds.setDouble(5, source.highResistanceThreshold());
					thresholds.setDouble(6, source.rNew()); thresholds.executeUpdate();
				}
				connection.commit();
				return new Definition(id, source.stringIndex(), key, source.stringName(), source.cellQty(),
						source.cellBrand(), source.cellModel(), source.ratedCapacity(), source.cutoffVoltage(),
						source.floatVoltage(), source.boostVoltage(), source.rNew(),
						source.highResistanceThreshold(), source.highTemperatureThreshold(),
						source.lowVoltageThreshold(), source.highVoltageThreshold(), source.bridgeId(),
						source.startTaUnitId(), source.socCheckpointPolicy());
			} catch (SQLException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	@Override
	public void delete(Definition definition) throws SQLException {
		try (var connection = this.database.getConnection(); var statement = connection.prepareStatement("DELETE FROM bms.bms_string WHERE id = ?")) {
			statement.setObject(1, definition.id()); statement.executeUpdate();
		}
	}

	private static Definition map(ResultSet result) throws SQLException {
		return new Definition(result.getObject("id", UUID.class), result.getInt("string_index"), result.getString("string_key"),
				result.getString("display_name"), result.getInt("cell_count"), result.getString("brand"), result.getString("model"),
				result.getDouble("capacity_ah"), requiredDouble(result, "cutoff_voltage"),
				requiredDouble(result, "float_voltage"), requiredDouble(result, "boost_voltage"),
				result.getDouble("rated_resistance_uohm"), result.getDouble("high_resistance"), result.getDouble("high_temperature"),
				result.getDouble("low_voltage"), result.getDouble("high_voltage"), result.getString("bridge_id"), result.getInt("start_ta_unit_id"),
				io.openems.edge.bms.service.strings.model.SocCheckpointPolicy.parse(result.getString("soc_checkpoint_policy")));
	}

	private static UUID resolveIdentity(java.sql.Connection connection, int stringIndex, String stringKey)
			throws SQLException {
		try (var query = connection.prepareStatement("""
				SELECT id, string_index, string_key FROM bms.bms_string
				WHERE string_index=? OR string_key=? FOR UPDATE
				""")) {
			query.setInt(1, stringIndex);
			query.setString(2, stringKey);
			try (var result = query.executeQuery()) {
				UUID id = null;
				while (result.next()) {
					if (id != null || result.getInt("string_index") != stringIndex
							|| !stringKey.equals(result.getString("string_key"))) {
						throw new SQLException("String identity conflicts with an existing PostgreSQL row");
					}
					id = result.getObject("id", UUID.class);
				}
				return id == null ? UUID.randomUUID() : id;
			}
		}
	}

	private static double requiredDouble(ResultSet result, String column) throws SQLException {
		var value = result.getBigDecimal(column);
		if (value == null) {
			throw new SQLException("BMS string column [" + column + "] is NULL; reconcile it from ConfigAdmin");
		}
		return value.doubleValue();
	}
}
