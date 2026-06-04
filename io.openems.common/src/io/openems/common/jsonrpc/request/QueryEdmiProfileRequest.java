package io.openems.common.jsonrpc.request;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.utils.JsonUtils;

/**
 * Represents a JSON-RPC Request for 'queryEdmiProfile'.
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "queryEdmiProfile",
 *   "params": {
 *     "meterId": String,
 *     "fromDate": YYYY-MM-DD,
 *     "toDate": YYYY-MM-DD
 *   }
 * }
 * </pre>
 */
public class QueryEdmiProfileRequest extends JsonrpcRequest {

	public static final String METHOD = "queryEdmiProfile";

	public static QueryEdmiProfileRequest from(JsonrpcRequest r) throws OpenemsNamedException {
		var p = r.getParams();
		var meterId = JsonUtils.getAsString(p, "meterId");
		var fromDate = JsonUtils.getAsZonedDateTime(p, "fromDate");
		var toDate = JsonUtils.getAsZonedDateTime(p, "toDate");
		return new QueryEdmiProfileRequest(r, meterId, fromDate, toDate);
	}

	private final String meterId;
	private final ZonedDateTime fromDate;
	private final ZonedDateTime toDate;

	public QueryEdmiProfileRequest(String meterId, ZonedDateTime fromDate, ZonedDateTime toDate) {
		super(METHOD);
		this.meterId = meterId;
		this.fromDate = fromDate;
		this.toDate = toDate;
	}

	private QueryEdmiProfileRequest(JsonrpcRequest request, String meterId, ZonedDateTime fromDate,
			ZonedDateTime toDate) {
		super(request, METHOD);
		this.meterId = meterId;
		this.fromDate = fromDate;
		this.toDate = toDate;
	}

	@Override
	public JsonObject getParams() {
		return JsonUtils.buildJsonObject() //
				.addProperty("meterId", this.meterId) //
				.addProperty("fromDate", this.fromDate.toString()) //
				.addProperty("toDate", this.toDate.toString()) //
				.build();
	}

	public String getMeterId() {
		return this.meterId;
	}

	public ZonedDateTime getFromDate() {
		return this.fromDate;
	}

	public ZonedDateTime getToDate() {
		return this.toDate;
	}
}
