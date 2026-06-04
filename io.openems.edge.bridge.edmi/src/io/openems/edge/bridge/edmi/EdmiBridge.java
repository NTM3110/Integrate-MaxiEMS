package io.openems.edge.bridge.edmi;

import com.atdigital.imr.EdmiDateTime;
import com.sun.jna.Structure;
import io.openems.edge.bridge.edmi.api.EdmiTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

import com.influxdb.client.write.Point;

import java.time.Instant;
import java.util.List;

import com.google.gson.JsonArray;

public interface EdmiBridge extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		;
		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Adds an EDMI Task.
	 * 
	 * @param task the {@link EdmiTask}
	 */
	void addTask(EdmiTask task);

	/**
	 * Removes an EDMI Task.
	 * 
	 * @param task the {@link EdmiTask}
	 */
	void removeTask(EdmiTask task);

	ProfileIngestionSettings getProfileIngestionSettings();

	void registerEnergyMeter(String meterId, String role, String sourceType);

	void unregisterEnergyMeter(String meterId);

	void processProfileTimestamp(Instant timestamp);

	/**
	 * Sends a request read profile and get record.
	 *
	 * @return the response value
	 * @throws Exception on error
	 */
	Object readProfile(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, int serialNumber, String username, String password, short survey) throws Exception;

	/**
	 * Sends a prioritized profile read request through the EDMI worker.
	 *
	 * @return the response value
	 * @throws Exception on error
	 */
	Object readProfileImmediately(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, int serialNumber,
			String username, String password, short survey) throws Exception;


	/**
	 * Sends a request read billing values and get record.
	 *
	 * @return the response value
	 * @throws Exception on error
	 */
	List<Object> readBillingValues(String username, String password, int serialNumber) throws Exception;

	/**
	 * Writes a Point to InfluxDB.
	 * 
	 * @param point the InfluxDB Point
	 */
	void writeToInflux(Point point);

	/**
	 * Queries historical billing values from InfluxDB.
	 *
	 * @param meterId the meter id tag
	 * @param start   the start timestamp as inclusive {@link Instant}
	 * @param end     the end timestamp as inclusive {@link Instant}
	 * @param fields  the fields to query
	 * @return the queried records
	 * @throws Exception on error
	 */
	JsonArray queryBillingValuesFromInflux(String meterId, Instant start, Instant end, List<String> fields)
			throws Exception;

	/**
	 * Queries historical profile values from InfluxDB.
	 *
	 * @param meterId the meter id tag
	 * @param start   the start timestamp as inclusive {@link Instant}
	 * @param end     the end timestamp as inclusive {@link Instant}
	 * @param fields  the fields to query
	 * @return the queried records
	 * @throws Exception on error
	 */
	JsonArray queryProfileValuesFromInflux(String meterId, Instant start, Instant end, List<String> fields)
			throws Exception;

	JsonArray querySeparatedEnergyFromInflux(Instant start, Instant end, List<String> fields) throws Exception;
}
