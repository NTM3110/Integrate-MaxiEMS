package io.openems.edge.bridge.iec104.api.worker;

import java.io.IOException;

import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ie.IeQualifierOfInterrogation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.worker.AbstractWorker;
import io.openems.edge.bridge.iec104.api.Iec104Bridge;

/**
 * The Iec104Worker handles periodic background tasks for the IEC 104 Bridge.
 * 
 * <p>
 * Primary responsibilities:
 * <ul>
 * <li>Sending cyclic General Interrogation (GI) commands to ensure fresh data
 * <li>Monitoring connection health
 * </ul>
 */
public class Iec104Worker extends AbstractWorker {

	private final Logger log = LoggerFactory.getLogger(Iec104Worker.class);

	private final Iec104Bridge bridge;
	private int cycleTimeMs;

	/**
	 * Constructor for {@link Iec104Worker}.
	 * 
	 * @param bridge                       the {@link Iec104Bridge}
	 * @param cyclicInterrogationIntervalS the interval in seconds for cyclic GI
	 */
	public Iec104Worker(Iec104Bridge bridge, int cyclicInterrogationIntervalS) {
		this.bridge = bridge;
		this.cycleTimeMs = cyclicInterrogationIntervalS > 0 ? cyclicInterrogationIntervalS * 1000 : 10000;
	}

	/**
	 * Updates the cyclic interrogation interval.
	 * 
	 * @param cyclicInterrogationIntervalS the interval in seconds
	 */
	public void setCyclicInterrogationInterval(int cyclicInterrogationIntervalS) {
		this.cycleTimeMs = cyclicInterrogationIntervalS > 0 ? cyclicInterrogationIntervalS * 1000 : 10000;
	}

	@Override
	protected void forever() throws InterruptedException {
		Connection connection = this.bridge.getConnection();

		if (connection != null) {
			try {
				// Send cyclic General Interrogation to all active components
				for (int commonAddress : this.bridge.getCommonAddresses()) {
					connection.interrogation(commonAddress, CauseOfTransmission.ACTIVATION,
							new IeQualifierOfInterrogation(20));
					this.log.debug("Sent cyclic General Interrogation to Common Address [{}]", commonAddress);
				}
			} catch (IOException e) {
				this.log.warn("Failed to send cyclic General Interrogation: {}", e.getMessage());
			}
		}

		Thread.sleep(this.cycleTimeMs);
	}

	@Override
	protected int getCycleTime() {
		return this.cycleTimeMs;
	}

}
