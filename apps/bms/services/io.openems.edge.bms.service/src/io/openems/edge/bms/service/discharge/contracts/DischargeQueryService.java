package io.openems.edge.bms.service.discharge.contracts;

import com.google.gson.JsonObject;

/** Legacy discharge wire projections, backed by private Platform persistence. */
public interface DischargeQueryService {
	JsonObject events();
	JsonObject samples(int eventId);
	JsonObject config();
	void saveConfig(JsonObject params);
	JsonObject curve(int stringIndex);
	void promoteCurve(int stringIndex, int sourceEventId);
}
