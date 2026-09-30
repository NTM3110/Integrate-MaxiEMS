package io.openems.edge.customerinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Dictionary;
import java.util.Hashtable;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.common.jsonrpc.base.JsonrpcResponseError;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public class CustomerInfoImplTest {

	@Test
	public void testConfigDefaults() throws Exception {
		assertEquals("CustomerInfo", Config.class.getDeclaredMethod("id").getDefaultValue());
		assertEquals("", Config.class.getDeclaredMethod("alias").getDefaultValue());
		assertEquals(true, Config.class.getDeclaredMethod("enabled").getDefaultValue());
		assertEquals("", Config.class.getDeclaredMethod("businessName").getDefaultValue());
		assertEquals("", Config.class.getDeclaredMethod("businessCode").getDefaultValue());
		assertEquals("", Config.class.getDeclaredMethod("address").getDefaultValue());
		assertEquals("", Config.class.getDeclaredMethod("installLocation").getDefaultValue());
	}

	@Test
	public void testGetCustomerInfoWithEmptyFields() {
		var sut = activate(config("", "", "", ""));

		var result = callGetCustomerInfo(sut);

		assertFalse(result.get("filled").getAsBoolean());
		assertCustomerInfo(result.getAsJsonObject("data"), "", "", "", "");
	}

	@Test
	public void testGetCustomerInfoTreatsUnicodeWhitespaceAsNotFilled() {
		var sut = activate(config("\u2003", "", "", ""));

		var result = callGetCustomerInfo(sut);

		assertFalse(result.get("filled").getAsBoolean());
		assertCustomerInfo(result.getAsJsonObject("data"), "\u2003", "", "", "");
	}

	@Test
	public void testGetCustomerInfoWithStoredValuesAndModifiedConfig() {
		var sut = activate(config("AT Energy", "0312345678", "Ho Chi Minh City", "Factory 1"));

		var result = callGetCustomerInfo(sut);
		assertTrue(result.get("filled").getAsBoolean());
		assertCustomerInfo(result.getAsJsonObject("data"), "AT Energy", "0312345678", "Ho Chi Minh City",
				"Factory 1");

		sut.modified(null, config("New Business", "0987654321", "Da Nang", "Factory 2"));

		result = callGetCustomerInfo(sut);
		assertTrue(result.get("filled").getAsBoolean());
		assertCustomerInfo(result.getAsJsonObject("data"), "New Business", "0987654321", "Da Nang", "Factory 2");
	}

	@Test
	public void testSetCustomerInfoUpdatesExistingConfiguration() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		var properties = new Hashtable<String, Object>();
		properties.put("id", "existing-id");
		properties.put("alias", "Existing Alias");
		properties.put("enabled", false);
		when(configuration.getProperties()).thenReturn(properties);
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("Old Business", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");
		params.addProperty("businessCode", "0312345678");
		params.addProperty("address", "Ho Chi Minh City");
		params.addProperty("installLocation", "Factory 1");

		var response = callSetCustomerInfo(sut, params);

		assertTrue(response instanceof GenericJsonrpcResponseSuccess);
		assertEquals(0, ((GenericJsonrpcResponseSuccess) response).getResult().size());
		verify(cm).getConfiguration(CustomerInfoImpl.SERVICE_PID, null);
		var updated = captureUpdatedProperties(configuration);
		assertEquals("existing-id", updated.get("id"));
		assertEquals("Existing Alias", updated.get("alias"));
		assertEquals(false, updated.get("enabled"));
		assertEquals("AT Energy", updated.get("businessName"));
		assertEquals("0312345678", updated.get("businessCode"));
		assertEquals("Ho Chi Minh City", updated.get("address"));
		assertEquals("Factory 1", updated.get("installLocation"));
	}

	@Test
	public void testSetCustomerInfoDefaultsMissingOptionalFields() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		when(configuration.getProperties()).thenReturn(new Hashtable<>());
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");

		callSetCustomerInfo(sut, params);

		var updated = captureUpdatedProperties(configuration);
		assertEquals("", updated.get("businessCode"));
		assertEquals("", updated.get("address"));
		assertEquals("", updated.get("installLocation"));
	}

	@Test
	public void testSetCustomerInfoSeedsStandardPropertiesWhenConfigurationIsEmpty() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		when(configuration.getProperties()).thenReturn(null);
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("Configured Business", "", "", "", "Configured Alias", false), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");
		params.addProperty("businessCode", "0312345678");
		params.addProperty("address", "Ho Chi Minh City");
		params.addProperty("installLocation", "Factory 1");

		callSetCustomerInfo(sut, params);

		var updated = captureUpdatedProperties(configuration);
		assertEquals(CustomerInfoImpl.COMPONENT_ID, updated.get("id"));
		assertEquals("Configured Alias", updated.get("alias"));
		assertEquals(false, updated.get("enabled"));
		assertEquals("AT Energy", updated.get("businessName"));
		assertEquals("0312345678", updated.get("businessCode"));
		assertEquals("Ho Chi Minh City", updated.get("address"));
		assertEquals("Factory 1", updated.get("installLocation"));
	}

	@Test
	public void testSetCustomerInfoMergesConfigurationKeysCaseInsensitively() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		var properties = new Hashtable<String, Object>();
		properties.put("ID", "existing-id");
		properties.put("Alias", "Existing Alias");
		properties.put("Enabled", false);
		properties.put("BusinessName", "Old Business");
		when(configuration.getProperties()).thenReturn(properties);
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");
		params.addProperty("businessCode", "0312345678");
		params.addProperty("address", "Ho Chi Minh City");
		params.addProperty("installLocation", "Factory 1");

		callSetCustomerInfo(sut, params);

		var updated = captureUpdatedProperties(configuration);
		assertLogicalProperty(updated, "id", "existing-id");
		assertLogicalProperty(updated, "alias", "Existing Alias");
		assertLogicalProperty(updated, "enabled", false);
		assertLogicalProperty(updated, "businessName", "AT Energy");
		assertLogicalProperty(updated, "businessCode", "0312345678");
		assertLogicalProperty(updated, "address", "Ho Chi Minh City");
		assertLogicalProperty(updated, "installLocation", "Factory 1");
	}

	@Test
	public void testSetCustomerInfoRejectsUnicodeWhitespaceBusinessName() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "\u2003");

		var response = callSetCustomerInfo(sut, params);

		verify(configuration, never()).update(any(Dictionary.class));
		assertTrue(response instanceof JsonrpcResponseError);
	}

	@Test
	public void testSetCustomerInfoRejectsMissingAndAsciiBlankBusinessName() throws Exception {
		var missingConfiguration = mock(Configuration.class);
		var missingCm = mock(ConfigurationAdmin.class);
		when(missingCm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(missingConfiguration);
		var missingSut = activate(config("", "", "", ""), missingCm);

		var missingResponse = callSetCustomerInfo(missingSut, new JsonObject());

		assertTrue(missingResponse instanceof JsonrpcResponseError);
		verify(missingConfiguration, never()).update(any(Dictionary.class));

		var blankConfiguration = mock(Configuration.class);
		var blankCm = mock(ConfigurationAdmin.class);
		when(blankCm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(blankConfiguration);
		var blankSut = activate(config("", "", "", ""), blankCm);
		var blankParams = new JsonObject();
		blankParams.addProperty("businessName", "   ");

		var blankResponse = callSetCustomerInfo(blankSut, blankParams);

		assertTrue(blankResponse instanceof JsonrpcResponseError);
		verify(blankConfiguration, never()).update(any(Dictionary.class));
	}

	@Test
	public void testSetCustomerInfoRejectsNonStringOptionalField() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");
		params.addProperty("address", 42);

		var response = callSetCustomerInfo(sut, params);

		assertTrue(response instanceof JsonrpcResponseError);
		verify(configuration, never()).update(any(Dictionary.class));
	}

	@Test
	public void testSetCustomerInfoReturnsErrorWhenConfigurationUpdateFails() throws Exception {
		var configuration = mock(Configuration.class);
		var cm = mock(ConfigurationAdmin.class);
		when(configuration.getProperties()).thenReturn(new Hashtable<>());
		doThrow(new IOException("write failed")).when(configuration).update(any(Dictionary.class));
		when(cm.getConfiguration(CustomerInfoImpl.SERVICE_PID, null)).thenReturn(configuration);
		var sut = activate(config("", "", "", ""), cm);
		var params = new JsonObject();
		params.addProperty("businessName", "AT Energy");

		var response = callSetCustomerInfo(sut, params);

		assertTrue(response instanceof JsonrpcResponseError);
		assertTrue(response.toJsonObject().getAsJsonObject("error").get("message").getAsString()
				.contains("write failed"));
	}

	private static CustomerInfoImpl activate(Config config) {
		return activate(config, mock(ConfigurationAdmin.class));
	}

	private static CustomerInfoImpl activate(Config config, ConfigurationAdmin cm) {
		var sut = new CustomerInfoImpl();
		try {
			injectConfigurationAdmin(sut, cm);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		sut.activate(null, config);
		return sut;
	}

	private static Config config(String businessName, String businessCode, String address, String installLocation) {
		return config(businessName, businessCode, address, installLocation, "", true);
	}

	private static Config config(String businessName, String businessCode, String address, String installLocation,
			String alias, boolean enabled) {
		var config = mock(Config.class);
		when(config.id()).thenReturn(CustomerInfoImpl.COMPONENT_ID);
		when(config.alias()).thenReturn(alias);
		when(config.enabled()).thenReturn(enabled);
		when(config.businessName()).thenReturn(businessName);
		when(config.businessCode()).thenReturn(businessCode);
		when(config.address()).thenReturn(address);
		when(config.installLocation()).thenReturn(installLocation);
		return config;
	}

	private static JsonObject callGetCustomerInfo(CustomerInfoImpl sut) {
		var builder = new JsonApiBuilder();
		sut.buildJsonApiRoutes(builder);
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(
				new GenericJsonrpcRequest("getCustomerInfo", new JsonObject()));

		builder.handle(call);

		return ((GenericJsonrpcResponseSuccess) call.getResponse()).getResult();
	}

	private static JsonrpcResponse callSetCustomerInfo(CustomerInfoImpl sut, JsonObject params) {
		var builder = new JsonApiBuilder();
		sut.buildJsonApiRoutes(builder);
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest("setCustomerInfo", params));

		builder.handle(call);

		return call.getResponse();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Dictionary<String, Object> captureUpdatedProperties(Configuration configuration) throws Exception {
		var captor = ArgumentCaptor.forClass(Dictionary.class);
		verify(configuration).update(captor.capture());
		return captor.getValue();
	}

	private static void assertLogicalProperty(Dictionary<String, Object> properties, String logicalName,
			Object expectedValue) {
		var matchingKeys = 0;
		Object actualValue = null;
		var keys = properties.keys();
		while (keys.hasMoreElements()) {
			var key = keys.nextElement();
			if (key.equalsIgnoreCase(logicalName)) {
				matchingKeys++;
				actualValue = properties.get(key);
			}
		}
		assertEquals("Expected exactly one key matching " + logicalName, 1, matchingKeys);
		assertEquals(expectedValue, actualValue);
	}

	private static void assertCustomerInfo(JsonObject data, String businessName, String businessCode, String address,
			String installLocation) {
		assertEquals(businessName, data.get("businessName").getAsString());
		assertEquals(businessCode, data.get("businessCode").getAsString());
		assertEquals(address, data.get("address").getAsString());
		assertEquals(installLocation, data.get("installLocation").getAsString());
	}

	private static void injectConfigurationAdmin(CustomerInfoImpl sut, ConfigurationAdmin cm) throws Exception {
		Field field = CustomerInfoImpl.class.getDeclaredField("cm");
		field.setAccessible(true);
		field.set(sut, cm);
	}
}
