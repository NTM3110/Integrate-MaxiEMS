package io.openems.edge.bms.endpoints.maintenance;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.maintenance.contracts.BmsMaintenanceService;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Administrator-only raw BMS diagnostic operations. */
@Component(name = "io.openems.edge.bms.api.maintenance.BmsMaintenanceJsonApi", property = "entry=edge.websocket.root")
public class BmsMaintenanceJsonApi implements JsonApi {
	public static final String STATUS = "bms.tools.maintenance.status";
	public static final String IR_SCAN = "bms.tools.maintenance.irScan";
	public static final String IR_ACTIVATE = "bms.tools.maintenance.irActivate";
	public static final String TC = "bms.tools.maintenance.tc";
	public static final String ADDRESS_CHANGE = "bms.tools.maintenance.addressChange";
	public static final String STOP = "bms.tools.maintenance.stop";
	public static final String WIFI = "bms.tools.maintenance.wifi";
	public static final String GPIO = "bms.tools.maintenance.gpio";
	@Reference private PlatformAuthenticationService authentication;
	@Reference private BmsMaintenanceService maintenance;
	@Override public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(STATUS, c -> { this.authorize(c.getRequest().getParams()); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.status())); });
		builder.handleRequest(IR_SCAN, c -> { var p=c.getRequest().getParams(); this.authorize(p); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.scanIr(integer(p, "stringIndex"), integer(p, "startId"), integer(p, "endId")))); });
		builder.handleRequest(IR_ACTIVATE, c -> { var p=c.getRequest().getParams(); this.authorize(p); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.activateIr(integer(p, "stringIndex"), integer(p, "startId"), integer(p, "endId")))); });
		builder.handleRequest(TC, c -> { var p=c.getRequest().getParams(); this.authorize(p); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.tcTask(integer(p,"stringIndex"), required(p,"action"), p.has("value")&&!p.get("value").isJsonNull()?integer(p,"value"):null))); });
		builder.handleRequest(ADDRESS_CHANGE, c -> { var p=c.getRequest().getParams(); this.authorize(p); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.addressChange(integer(p,"stringIndex"), required(p,"moduleType"), integer(p,"oldId"), integer(p,"newId")))); });
		builder.handleRequest(STOP, c -> { this.authorize(c.getRequest().getParams()); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), json(this.maintenance.stop())); });
		builder.handleRequest(WIFI, c -> { var p=c.getRequest().getParams(); this.authorize(p); return new GenericJsonrpcResponseSuccess(c.getRequest().getId(), parse(this.maintenance.wifi(required(p,"action"), optional(p,"ssid"), optional(p,"bssid"), optional(p,"password")))); });
		builder.handleRequest(GPIO, c -> {
			var p = c.getRequest().getParams();
			this.authorize(p);
			// The helper receives only GPIO settings. Never pass the bearer token to a
			// privileged process or echo it back in the JSON-RPC response.
			var settings = p.deepCopy();
			settings.remove("token");
			return new GenericJsonrpcResponseSuccess(c.getRequest().getId(),
					parse(this.maintenance.gpio(required(p, "action"), settings.toString())));
		});
	}
	private void authorize(JsonObject p) { var principal=this.authentication.authenticateToken(required(p,"token")).orElseThrow(()->new SecurityException("BMS session is invalid or expired")); if(!principal.hasRole(PlatformRole.PLATFORM_ADMIN))throw new SecurityException("Administrator permission is required"); }
	private static int integer(JsonObject p,String n){
		try {
			if (!p.has(n) || p.get(n).isJsonNull()) throw new IllegalArgumentException("Missing " + n);
			var value = p.get(n).getAsDouble();
			if (!Double.isFinite(value) || value != Math.rint(value)) throw new IllegalArgumentException(n + " must be a whole number");
			return p.get(n).getAsInt();
		} catch (IllegalArgumentException e) { throw e; }
		catch (Exception e) { throw new IllegalArgumentException(n + " must be a whole number"); }
	}
	private static String required(JsonObject p,String n){if(!p.has(n)||p.get(n).isJsonNull()||p.get(n).getAsString().isBlank())throw new IllegalArgumentException("Missing "+n);return p.get(n).getAsString();}
	private static String optional(JsonObject p,String n){return p.has(n)&&!p.get(n).isJsonNull()?p.get(n).getAsString():null;}
	private static JsonObject parse(String value){try{return com.google.gson.JsonParser.parseString(value).getAsJsonObject();}catch(Exception e){var o=new JsonObject();o.addProperty("output", value);return o;}}
	private static JsonObject json(BmsMaintenanceService.TaskStatus s){var o=new JsonObject();o.addProperty("type",s.type());o.addProperty("state",s.state());o.addProperty("running",s.running());add(o,"stringIndex",s.stringIndex());add(o,"bridgeId",s.bridgeId());add(o,"serialPort",s.serialPort());add(o,"startId",s.startId());add(o,"endId",s.endId());add(o,"currentId",s.currentId());add(o,"nextId",s.nextId());add(o,"transformerRange",s.transformerRange());add(o,"calibrationSigned",s.calibrationSigned());add(o,"calibrationRaw",s.calibrationRaw());add(o,"firstCurrentSignedRaw",s.firstCurrentSignedRaw());add(o,"latestCurrentRaw",s.latestCurrentRaw());add(o,"latestCurrentSignedRaw",s.latestCurrentSignedRaw());add(o,"latestCurrentAmps",s.latestCurrentAmps());add(o,"currentSampleIndex",s.currentSampleIndex());add(o,"successfulCurrentSamples",s.successfulCurrentSamples());o.addProperty("currentChanged",Boolean.TRUE.equals(s.currentChanged()));add(o,"error",s.error());add(o,"moduleType",s.moduleType());add(o,"oldId",s.oldId());add(o,"newId",s.newId());add(o,"phase",s.phase());add(o,"verification",s.verification());o.add("activeIds",array(s.activeIds()));o.add("missingIds",array(s.missingIds()));o.add("activatedIds",array(s.activatedIds()));o.add("notActivatedIds",array(s.notActivatedIds()));o.add("output",array(s.output()));return o;}
	private static JsonArray array(Iterable<?> v){var a=new JsonArray();for(var x:v)if(x instanceof Number n)a.add(n);else a.add(String.valueOf(x));return a;}
	private static void add(JsonObject o,String n,Number v){if(v==null)o.add(n,com.google.gson.JsonNull.INSTANCE);else o.addProperty(n,v);}
	private static void add(JsonObject o,String n,String v){if(v==null)o.add(n,com.google.gson.JsonNull.INSTANCE);else o.addProperty(n,v);}
}
