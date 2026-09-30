package io.openems.edge.monitoring;

import static io.openems.edge.common.claim.Monitoring.SINGLETON_COMPONENT_ID;
import static io.openems.edge.common.claim.Monitoring.SINGLETON_SERVICE_PID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Hashtable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.openems.edge.common.claim.Network;

public class MonitoringImplTest {

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> firstTask;
	private ScheduledFuture<?> secondTask;
	private ConfigurationAdmin cm;
	private Configuration configuration;
	private MonitoringImpl sut;

	@Before
	public void setUp() throws Exception {
		this.executor = mock(ScheduledExecutorService.class);
		this.firstTask = mock(ScheduledFuture.class);
		this.secondTask = mock(ScheduledFuture.class);
		this.cm = mock(ConfigurationAdmin.class);
		this.configuration = mock(Configuration.class);

		var properties = new Hashtable<String, Object>();
		properties.put("id", SINGLETON_COMPONENT_ID);
		properties.put("alias", SINGLETON_SERVICE_PID);
		when(this.configuration.getProperties()).thenReturn(properties);
		when(this.cm.getConfiguration(anyString(), eq("?"))).thenReturn(this.configuration);
		doReturn(this.firstTask, this.secondTask).when(this.executor).scheduleAtFixedRate(any(Runnable.class), eq(0L),
				eq(30L), eq(TimeUnit.SECONDS));

		this.sut = new MonitoringImpl(this.executor);
		injectConfigurationAdmin(this.sut, this.cm);
	}

	@Test
	public void activateEnabledSchedulesPollingImmediatelyExactlyOnce() {
		this.sut.activate(null, config(true));

		assertTrue(this.sut.isEnabled());
		verify(this.executor, times(1)).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L),
				eq(TimeUnit.SECONDS));
	}

	@Test
	public void repeatedEnabledModificationDoesNotDuplicatePolling() {
		this.sut.activate(null, config(true));
		this.sut.modified(null, config(true));
		this.sut.modified(null, config(true));

		assertTrue(this.sut.isEnabled());
		verify(this.executor, times(1)).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L),
				eq(TimeUnit.SECONDS));
		verify(this.firstTask, never()).cancel(anyBoolean());
	}

	@Test
	public void disablingCancelsPollingAndReenablingSchedulesANewTask() {
		this.sut.activate(null, config(true));

		this.sut.modified(null, config(false));

		assertFalse(this.sut.isEnabled());
		verify(this.firstTask).cancel(false);

		this.sut.modified(null, config(true));

		assertTrue(this.sut.isEnabled());
		verify(this.executor, times(2)).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(30L),
				eq(TimeUnit.SECONDS));
		verify(this.secondTask, never()).cancel(anyBoolean());
	}

	@Test
	public void validatesTheMonitoringSingletonConfiguration() throws Exception {
		this.sut.activate(null, config(true));

		verify(this.cm).getConfiguration(SINGLETON_SERVICE_PID, "?");
		verify(this.cm, never()).getConfiguration(eq(Network.SINGLETON_SERVICE_PID), anyString());
		verify(this.configuration, never()).update(any());
	}

	private static Config config(boolean enabled) {
		var config = mock(Config.class);
		when(config.id()).thenReturn(SINGLETON_COMPONENT_ID);
		when(config.alias()).thenReturn(SINGLETON_SERVICE_PID);
		when(config.enabled()).thenReturn(enabled);
		when(config.SN()).thenReturn("2601");
		return config;
	}

	private static void injectConfigurationAdmin(MonitoringImpl monitoring, ConfigurationAdmin cm) throws Exception {
		Field field = MonitoringImpl.class.getDeclaredField("cm");
		field.setAccessible(true);
		field.set(monitoring, cm);
	}
}
