package io.openems.edge.controller.maximeter;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Common interface for MaxiMeter REST handlers.
 * Both EDMI and DLMS variants implement this interface.
 */
public interface MaxiMeterRestHandler {

	/**
	 * Handle incoming HTTP request.
	 */
	boolean handle(Request request, Response response, Callback callback) throws Exception;

	/**
	 * Get the handler path prefix for routing.
	 */
	String getPathPrefix();
}
