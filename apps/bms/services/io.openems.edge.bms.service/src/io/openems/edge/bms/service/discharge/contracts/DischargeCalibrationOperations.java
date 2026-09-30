package io.openems.edge.bms.service.discharge.contracts;

import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.strings.contracts.BmsStringManager;

/** Public feature boundary; implementations and JDBC remain in Platform. */
public interface DischargeCalibrationOperations {
	void create(int stringIndex, Instant start, boolean automatic, double startCurrent, String type,
			Double ratedCapacityAh) throws Exception;

	void stop(long eventId) throws Exception;

	void delete(long eventId) throws Exception;

	void activateBaseline(int stringIndex, long eventId) throws Exception;
}
