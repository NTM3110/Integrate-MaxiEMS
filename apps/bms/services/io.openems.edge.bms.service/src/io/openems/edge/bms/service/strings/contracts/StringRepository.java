package io.openems.edge.bms.service.strings.contracts;

import io.openems.edge.bms.service.strings.model.PersistentStringModels;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition;

public interface StringRepository {
	List<Definition> list() throws SQLException;
	Optional<Definition> findByIndex(int stringIndex) throws SQLException;
	Definition synchronize(io.openems.edge.bms.service.strings.model.BmsStringModels.Definition definition) throws SQLException;
	void delete(Definition definition) throws SQLException;
}
