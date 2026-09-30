package io.openems.edge.bms.service.strings.contracts;

import java.util.List;
import java.sql.SQLException;

import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;

/** Public feature boundary; implementations and JDBC remain in Platform. */
public interface StringManagementOperations {
	record Readiness(String operationId, int stringIndex, long generation, String operation, int expectedCellCount,
			String bridgeId, long startedAt, Long configAdminCompletedAt, Long componentsAvailableAt,
			Long firstTaReadAt, Long firstTcReadAt, Long completeReadAt, Long calculatorSnapshotAt,
			boolean complete, List<Integer> successfulTaIds, List<Integer> pendingTaIds, List<Integer> failedTaIds,
			String tcState, int calculatorFailureCount, int observedFailureCount, int restartCount) {
	}

	Detail create(Definition request) throws Exception;

	Detail update(Definition request) throws Exception;

	List<Definition> list() throws Exception;

	Detail getData(int stringIndex) throws Exception;

	Readiness readiness(int stringIndex);

	void reconcile() throws SQLException;

	void delete(int stringIndex) throws Exception;
}
