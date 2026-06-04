package io.openems.common.jsonrpc.response;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;

/**
 * Represents a JSON-RPC Response for 'queryEdmiProfile'.
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "result": {
 *     "records": [
 *       {
 *         "time_stamp": String,
 *         "record_status": String,
 *         "total_energy_tot_imp_wh": Number,
 *         "total_energy_tot_exp_wh": Number,
 *         "total_energy_tot_imp_va": Number,
 *         "total_energy_tot_exp_va": Number
 *       }, ...
 *     ]
 *   }
 * }
 * </pre>
 */
public class QueryEdmiProfileResponse extends JsonrpcResponseSuccess {

	private final List<JsonObject> records;

	public QueryEdmiProfileResponse(UUID id, List<JsonObject> records) {
		super(id);
		this.records = records;
	}

	@Override
	public JsonObject getResult() {
		var recordsArray = new JsonArray();
		for (var record : this.records) {
			recordsArray.add(record);
		}
		var result = new JsonObject();
		result.add("records", recordsArray);
		return result;
	}
}
