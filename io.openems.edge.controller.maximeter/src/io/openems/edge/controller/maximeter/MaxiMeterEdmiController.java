package io.openems.edge.controller.maximeter;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.controller.api.Controller;

/**
 * Interface for MaxiMeterEdmiController.
 * Manages all EDMI meters and bridges, provides centralized profile configuration.
 */
public interface MaxiMeterEdmiController extends OpenemsComponent, Controller, ComponentJsonApi {
	
	/**
	 * Update meter status when billing task reads from meter
	 */
	void updateMeterStatus(String meterId, String status, Double lastReadingValue, String errorMessage);
	
	/**
	 * Get current meter status for all meters
	 */
	java.util.List<MaxiMeterEdmiControllerImpl.MeterStatus> getAllMeterStatus();
	
	/**
	 * Read profile data from meter hardware via JSON-RPC
	 */
	com.google.gson.JsonObject readProfileFromMeter(String meterId, String startDate, String endDate, String survey);
	
	/**
	 * Get profile ingestion settings that apply to all bridges
	 */
	java.util.Map<String, Object> getProfileIngestionSettings();
	
	/**
	 * Update profile ingestion settings for all bridges
	 */
	boolean updateProfileIngestionSettings(java.util.Map<String, Object> settings);
}