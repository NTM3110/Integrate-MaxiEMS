package io.openems.edge.bridge.dlms;

import java.util.HashMap;
import java.util.Map;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gurux.common.GXCommon;
import gurux.common.ReceiveParameters;
import gurux.dlms.GXByteBuffer;
import gurux.dlms.GXDateTime;
import gurux.dlms.GXDLMSClient;
import gurux.dlms.GXReplyData;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.Conformance;
import gurux.dlms.enums.ErrorCode;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.Security;
import gurux.dlms.enums.Unit;
import gurux.dlms.objects.GXDLMSData;
import gurux.dlms.objects.GXDLMSDemandRegister;
import gurux.dlms.objects.GXDLMSExtendedRegister;
import gurux.dlms.objects.GXDLMSObject;
import gurux.dlms.objects.GXDLMSRegister;
import gurux.io.BaudRate;
import gurux.io.Parity;
import gurux.io.StopBits;
import gurux.serial.GXSerial;
import gurux.dlms.GXDLMSException;
import gurux.dlms.objects.GXDLMSProfileGeneric;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.bridge.dlms.api.BridgeDlms;
import io.openems.edge.bridge.dlms.api.DlmsComponent;
import io.openems.edge.bridge.dlms.api.DlmsTargetConfig;
import io.openems.edge.common.component.OpenemsComponent;

import io.openems.edge.bridge.dlms.GXDLMSReader;
import io.openems.edge.bridge.dlms.GXDLMSSecureClient2;
import gurux.dlms.objects.GXDLMSObjectCollection;
import gurux.dlms.objects.GXXmlWriterSettings;
import gurux.dlms.enums.ObjectType;
import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.lang.reflect.Field;


import gurux.common.enums.TraceLevel;
import java.util.ArrayList;

@Component(//
		name = "Bridge.Dlms.Serial", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@Designate(ocd = ConfigSerial.class, factory = true)
public class BridgeDlmsSerialImpl extends AbstractDlmsBridge implements BridgeDlms, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(BridgeDlmsSerialImpl.class);

	private static final int DEFAULT_CLIENT_ADDRESS = 16;
	private static final int DEFAULT_SERVER_ADDRESS = 9938;
	private static final long METER_READ_PAUSE_MS = 10_000L;
	private static final Object METER_READ_LOCK = new Object();
	private static long lastMeterReadFinishedAt = 0L;

	private String portName = "";
	private int baudRate;
	private int serverAddress = DEFAULT_SERVER_ADDRESS;
	private int logicalAddress;
	private boolean useLogicalNameReferencing;
	private Parity parity = Parity.NONE;
	private StopBits stopBits = StopBits.ONE;
	private int dataBits = 8;
	private Authentication authentication = Authentication.NONE;
	private String password = "";
	private Security security = Security.NONE;
	private String outputFile = "";

	private GXSerial serial = null;
	private GXDLMSSecureClient2 client = null;
	private GXDLMSReader reader = null;
	private GXDLMSObjectCollection associationViewCache = null;


	public BridgeDlmsSerialImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				BridgeDlms.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, ConfigSerial config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		this.initClient();
	}

	@Modified
	private void modified(ComponentContext context, ConfigSerial config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		this.closeConnection();
		this.initClient();
	}

	private void applyConfig(ConfigSerial config) {
		this.portName = config.portName();
		this.baudRate = config.baudRate();
		this.logicalAddress = config.logicalAddress();
		this.useLogicalNameReferencing = config.useLogicalNameReferencing();
		this.parity = Parity.valueOf(config.parity().toUpperCase());
		this.stopBits = StopBits.values()[config.stopBits() - 1];
		this.dataBits = config.dataBits();
		this.authentication = Authentication.valueOf(config.authentication().toUpperCase());
		this.password = config.password();
		this.security = Security.valueOf(config.security().toUpperCase());
		this.outputFile = config.outputFile();
		this._setCycleDelay(config.delay());
		this.worker.setCycleDelay(config.delay());
	}

	private void initClient() {
		this.client = new GXDLMSSecureClient2(this.useLogicalNameReferencing);
		this.client.setClientAddress(DEFAULT_CLIENT_ADDRESS);
		// Combine Logical and Physical addresses into one DLMS-compliant integer
		int combinedAddress = GXDLMSClient.getServerAddress(this.logicalAddress, this.serverAddress);

		// Apply the combined value to the client
		this.client.setServerAddress(combinedAddress);
		this.client.setInterfaceType(InterfaceType.HDLC);
		this.client.setAuthentication(this.authentication);
		this.client.setPassword(this.password.getBytes());
		this.client.getCiphering().setSecurity(this.security);
		this.client.getProposedConformance().remove(Conformance.DELTA_VALUE_ENCODING);
	}

	@Override
	public synchronized void applyTarget(DlmsComponent parent) throws Exception {
		if (!(parent instanceof DlmsTargetConfig targetConfig)) {
			return;
		}
		this.applyTargetAddress(targetConfig.serverAddress());
	}

	@Override
	public synchronized void applyTargetAddress(int nextServerAddress) throws Exception {
		if (this.serverAddress != nextServerAddress) {
			this.serverAddress = nextServerAddress;
			this.closeConnection();
			this.initClient();
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.closeConnection();
	}

	private synchronized void closeConnection() {
		if (this.reader != null) {
			try {
				this.reader.close();
			} catch (Exception e) {
				this.log.debug("Ignoring DLMS disconnect failure while closing connection: {}", e.getMessage());
			}
		}
		if (this.serial != null) {
			try {
				if (this.serial.isOpen()) {
					this.serial.close();
				}
			} catch (Exception e) {
				this.log.error("Error closing serial port: " + e.getMessage());
			}
			this.serial = null;
		}
		this.reader = null;
		this.associationViewLoaded = false;
	}

	private synchronized void ensureConnection() throws Exception {
		if (this.serial == null) {
			this.serial = this.openSerial();
			this.initializeConnectionWithRetry();
		} else if (!this.serial.isOpen()) {
			this.serial = this.openSerial();
			this.initializeConnectionWithRetry();
		}
	}

	private GXSerial openSerial() throws Exception {
		var nextSerial = new GXSerial();
		nextSerial.setPortName(this.portName);
		nextSerial.setBaudRate(BaudRate.forValue(this.baudRate));
		nextSerial.setParity(this.parity);
		nextSerial.setStopBits(this.stopBits);
		nextSerial.setDataBits(this.dataBits);
		nextSerial.setReadTimeout(15_000);
		nextSerial.setWriteTimeout(15_000);
		nextSerial.open();
		Thread.sleep(1000);
		return nextSerial;
	}

	private void initializeConnectionWithRetry() throws Exception {
		try {
			this.initializeReaderConnection("Initializing Connection (Handshake)...");
		} catch (Exception e) {
			if (!this.isPermanentAssociationReject(e)) {
				this.log.error("Handshake failed, resetting connection: {}", e.getMessage(), e);
				this.closeConnection();
				this.initClient();
				throw e;
			}
			this.log.warn("Handshake was rejected by meter; closing session and waiting before one retry: {}",
					e.getMessage());
			this.closeConnection();
			this.initClient();
			Thread.sleep(20_000);
			this.serial = this.openSerial();
			try {
				this.initializeReaderConnection("Retrying Connection (Handshake)...");
			} catch (Exception retryException) {
				this.log.error("Handshake retry failed, resetting connection: {}", retryException.getMessage(),
						retryException);
				this.closeConnection();
				this.initClient();
				throw retryException;
			}
		}
	}

	private void initializeReaderConnection(String message) throws Exception {
		this.reader = new GXDLMSReader(this.client, this.serial, TraceLevel.WARNING, null);
		this.log.info(message);
		this.reader.initializeConnection();
		this.log.info("Handshake successful! Connection established.");
	}

	private boolean isPermanentAssociationReject(Exception e) {
		var message = e.getMessage();
		return message != null && message.toLowerCase().contains("permanently rejected");
	}

	private boolean associationViewLoaded = false;

	private File getAssociationCacheFile() {
		var fileName = "meter_cache.xml";
		if (this.outputFile != null && !this.outputFile.isBlank()) {
			var configuredName = new File(this.outputFile).getName();
			if (!configuredName.isBlank()) {
				fileName = configuredName;
			}
		}
		var dataDir = System.getProperty("openems.data.dir");
		var cacheDir = dataDir != null && !dataDir.isBlank() ? new File(dataDir) : new File("data");
		return new File(cacheDir, fileName);
	}

	private void ensureAssociationView(){
		if (!this.associationViewLoaded) {
			if (this.associationViewCache != null && !this.associationViewCache.isEmpty()) {
				this.client.getObjects().clear();
				this.client.getObjects().addAll(this.associationViewCache);
				this.associationViewLoaded = true;
				this.log.debug("Loaded Association View from in-memory cache ({} objects).",
						this.associationViewCache.size());
				return;
			}
			var cacheFile = this.getAssociationCacheFile();
			if (cacheFile.exists()) {
				this.log.info("Loading DLMS Association View from cache file [{}]", cacheFile.getAbsolutePath());
				try {
					GXDLMSObjectCollection c = GXDLMSObjectCollection.load(cacheFile.getAbsolutePath());
					if (!c.isEmpty()) {
						this.associationViewCache = c;
						this.client.getObjects().clear();
						this.client.getObjects().addAll(c);
						this.associationViewLoaded = true;
						this.log.info("Loaded Association View from cache file ({} objects).", c.size());
						return;
					}
				} catch (Exception ex) {
					this.log.error("Failed to load Association View cache file [{}]: {}", cacheFile.getAbsolutePath(),
							ex.getMessage());
				}
			}

			this.log.info("Association View cache file [{}] does not exist; reading it once from meter.",
					cacheFile.getAbsolutePath());
			try {
				this.log.info("Reading Association View (Object List)...");
				this.reader.getAssociationView();
				this.associationViewCache = this.client.getObjects();
				this.associationViewLoaded = true;
				this.log.info("Association View loaded (" + this.client.getObjects().size() + " objects).");
				var parent = cacheFile.getParentFile();
				if (parent != null && !parent.exists()) {
					parent.mkdirs();
				}
				this.client.getObjects().save(cacheFile.getAbsolutePath(), new GXXmlWriterSettings());
				this.log.info("Saved Association View cache file [{}]", cacheFile.getAbsolutePath());
			} catch (Exception ex) {
				this.log.warn("Could not read Association View: " + ex.getMessage());
				this.closeConnection();
				this.initClient();
			}
		}
	}

	public synchronized Object read(String obis, int attributeIndex) throws Exception {
		this.ensureConnection();
		Object val = null;

		// Only attempt to load association view if we haven't loaded it yet
		this.ensureAssociationView();

		if(associationViewLoaded) {
			// Try looking up object by OBIS in the loaded collection
			GXDLMSObject obj = this.client.getObjects().findByLN(ObjectType.NONE, obis);
			try {
				if (obj != null) {
					val = reader.read(obj, attributeIndex);
				} else {
					// Fallback: read directly by constructing a register object with the OBIS code.
					// This works even when getAssociationView is unavailable.
					gurux.dlms.objects.GXDLMSRegister reg = new gurux.dlms.objects.GXDLMSRegister();
					reg.setLogicalName(obis);
					val = reader.read(reg, attributeIndex);
				}
			} catch (Exception e) {
				this.log.error("DLMS read failed for OBIS [{}] attr [{}]: {}", obis, attributeIndex, e.getMessage(), e);
				// Reset the connection so the next cycle triggers a fresh reconnect + handshake
				this.closeConnection();
				this.initClient();
				throw e;
			}
		}

		return val;
	}

	@Override
	public synchronized Object[] readProfile(String obis, Date start, Date end) throws Exception {
		synchronized (METER_READ_LOCK) {
			this.waitBeforeMeterRead("DLMS profile read");
			try {
				return this.readProfileUnlocked(obis, start, end);
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	@Override
	public synchronized Object[] readProfileForTarget(DlmsComponent parent, String obis, Date start, Date end)
			throws Exception {
		synchronized (METER_READ_LOCK) {
			try {
				this.applyTarget(parent);
				this.waitBeforeMeterRead("DLMS profile read");
				return this.readProfileUnlocked(obis, start, end);
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	@Override
	public synchronized Object[] readProfileForTargetAddress(String componentId, int serverAddress, String obis,
			Date start, Date end) throws Exception {
		synchronized (METER_READ_LOCK) {
			try {
				this.applyTargetAddress(serverAddress);
				this.waitBeforeMeterRead("DLMS profile read");
				return this.readProfileUnlocked(obis, start, end);
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	private Object[] readProfileUnlocked(String obis, Date start, Date end) throws Exception {
		this.ensureConnection();
		this.ensureAssociationView();

		// Find the ProfileGeneric object by OBIS
		GXDLMSObject obj = this.client.getObjects()
				.findByLN(ObjectType.PROFILE_GENERIC, obis);

		if (obj == null) {
			this.log.warn("ProfileGeneric object not found for OBIS [{}]", obis);
			return new Object[0];
		}

		GXDLMSProfileGeneric pg = (GXDLMSProfileGeneric) obj;

		// Ensure capture columns are loaded (attribute 3)
		if (pg.getCaptureObjects().isEmpty()) {
			this.reader.read(pg, 3);
		}
		for (var captureObject : pg.getCaptureObjects()) {
			var captureTarget = captureObject.getKey();
			try {
				if (captureTarget instanceof GXDLMSDemandRegister) {
					this.reader.read(captureTarget, 4);
				} else if (captureTarget instanceof GXDLMSRegister || captureTarget instanceof GXDLMSExtendedRegister) {
					this.reader.read(captureTarget, 3);
				}
			} catch (Exception e) {
				this.log.debug("Could not read scaler/unit for captured object [{}]: {}", captureTarget.getLogicalName(),
						e.getMessage());
			}
		}

		try {
			Calendar startCalendar = Calendar.getInstance();
			startCalendar.setTime(start);
			Calendar endCalendar = Calendar.getInstance();
			endCalendar.setTime(end);
			return this.reader.readRowsByRange(pg, new GXDateTime(startCalendar), new GXDateTime(endCalendar));
		} catch (Exception e) {
			this.log.error("readProfile failed [{}]: {}", obis, e.getMessage(), e);
			this.closeConnection();
			this.initClient();
			throw e;
		}
	}

	private void waitBeforeMeterRead(String operation) throws InterruptedException {
		var elapsed = System.currentTimeMillis() - lastMeterReadFinishedAt;
		var waitMs = METER_READ_PAUSE_MS - elapsed;
		if (lastMeterReadFinishedAt > 0 && waitMs > 0) {
			this.log.info("Waiting {} ms before next {}", waitMs, operation);
			try {
				Thread.sleep(waitMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			}
		}
	}

	public static void printObjectFields(Object obj) {
		if (obj == null) {
			System.out.println("Object is null");
			return;
		}
		System.out.println("Class: " + obj.getClass().getName());
		if (obj.getClass().getName().startsWith("java.lang.")) {
			System.out.println("Value: " + obj);
			return;
		}
		java.lang.reflect.Field[] fields = obj.getClass().getDeclaredFields();

		for (java.lang.reflect.Field field : fields) {
			try {
				field.setAccessible(true);
				String name = field.getName();
				Object value = field.get(obj);
				System.out.println(name + ": " + value);
			} catch (java.lang.reflect.InaccessibleObjectException | IllegalAccessException e) {
				System.out.println(field.getName() + ": [Inaccessible]");
			}
		}
	}

	@Override
	public synchronized Object[] readBillingValues() throws Exception {
		synchronized (METER_READ_LOCK) {
			this.waitBeforeMeterRead("DLMS billing read");
			try {
				return this.readBillingValuesUnlocked();
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	@Override
	public synchronized Object[] readBillingValuesForTarget(DlmsComponent parent) throws Exception {
		synchronized (METER_READ_LOCK) {
			try {
				this.applyTarget(parent);
				this.waitBeforeMeterRead("DLMS billing read");
				return this.readBillingValuesUnlocked();
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	@Override
	public synchronized Object[] readBillingValuesForTargetAddress(String componentId, int serverAddress)
			throws Exception {
		synchronized (METER_READ_LOCK) {
			try {
				this.applyTargetAddress(serverAddress);
				this.waitBeforeMeterRead("DLMS billing read");
				return this.readBillingValuesUnlocked();
			} finally {
				this.finishMeterReadSession();
				lastMeterReadFinishedAt = System.currentTimeMillis();
			}
		}
	}

	private void finishMeterReadSession() {
		this.closeConnection();
		this.initClient();
	}

	private Object[] readBillingValuesUnlocked() throws Exception {
		this.ensureConnection();
		this.ensureAssociationView();
		String[] reference_codes = {
				// "1-1:1.8.0"
				"0-0:42.0.0", "0-0:97.97.0", "0-0:1.0.0", "1-0:0.0.0",
				"1-0:0.0.1", "1-0:0.0.2", "1-0:0.0.3", "0-0:96.1.0",
				"0-0:96.1.1", "1-1:2.8.0", "1-1:3.8.0", "1-1:4.8.0",
				"1-1:1.8.0", "1-1:9.8.0", "1-1:1.8.1", "1-1:1.8.2",
				"1-1:1.8.3", "1-1:2.8.1", "1-1:2.8.2", "1-1:2.8.3",
				"1-1:1.8.4", "1-1:2.8.4",
				"1-1:1.9.0", "1-1:9.9.0", "1-1:2.5.0", "1-1:3.5.0",
				"1-1:4.5.0", "1-1:1.5.0", "1-1:9.5.0", "1-1:1.6.0",
				"1-1:2.6.0", "1-1:1.6.1", "1-1:2.6.1", "1-1:1.6.2",
				"1-1:2.6.2", "1-1:1.6.3", "1-1:2.6.3", "1-1:1.6.4",
				"1-1:2.6.4", "1-1:1.2.0", "1-1:2.2.0", "1-1:1.2.1",
				"1-1:2.2.1", "1-1:1.2.2", "1-1:2.2.2", "1-1:1.2.3",
				"1-1:2.2.3", "1-1:1.2.4", "1-1:2.2.4", "1-1:32.7.0",
				"1-1:52.7.0", "1-1:72.7.0", "1-4:32.7.0", "1-4:52.7.0",
				"1-4:72.7.0", "1-1:31.7.0", "1-1:51.7.0", "1-1:71.7.0",
				"1-4:31.7.0", "1-4:51.7.0", "1-4:71.7.0", "1-1:91.7.0",
				"1-1:14.7.0", "1-4:16.7.0", "1-4:36.7.0", "1-4:56.7.0",
				"1-4:76.7.0", "1-4:131.7.0", "1-4:151.7.0", "1-4:171.7.0",
				"1-4:191.7.0", "1-1:13.7.0", "1-1:33.7.0", "1-1:53.7.0",
				"1-1:73.7.0", "1-1:81.7.0", "1-1:81.7.1", "1-1:81.7.2",
				"1-1:81.7.4", "1-1:81.7.5", "1-1:81.7.6", "0-0:96.7.1",
				"0-0:96.7.2", "0-0:96.7.3", "1-0:0.1.0", "1-0:0.1.2",
				"0-0:96.8.0", "0-0:96.2.0", "0-0:96.2.1", "0-1:96.2.5",
				"0-0:96.2.2", "0-0:96.2.7", "0-0:96.3.1", "0-0:96.3.2",
				"0-0:96.4.0", "0-0:96.5.0", "0-0:96.6.0", "0-0:96.6.3",
				"1-0:0.2.0", "1-0:0.2.1", "1-0:0.2.2", "1-0:0.2.7",
				"0-0:96.90", "1-0:0.2.4", "0-0:96.99.8", "0-0:96.90.2",
				"0-0:96.90.1", "1-1:0.3.0", "1-1:0.3.1", "1-1:0.4.0",
				"1-1:0.4.1", "1-1:0.4.2", "1-1:0.4.3"
		};

		var objects = new ArrayList<Object>();

		for (int i = 0; i < reference_codes.length; i++) {
			String standard_obis = reference_codes[i].replace('-', '.').replace(':', '.');
					long dotCount = standard_obis.chars().filter(ch -> ch == '.').count();
					if (dotCount == 4) {
						standard_obis += ".255";
					}
					this.log.debug("Standard obis: {}", standard_obis);
					GXDLMSObject obj = this.client.getObjects()
							.findByLN(ObjectType.NONE, standard_obis);

					Map<String, Object> data = new HashMap<>();
					data.put("obis", standard_obis);
					if (obj == null) {
						data.put("description", "Unknown");
						data.put("error", "Object not found in association view");
						objects.add(data);
						continue;
					}

					data.put("description", obj.getDescription() != null ? obj.getDescription() : "Unknown");
					try {
						if (obj instanceof GXDLMSDemandRegister) {
							this.reader.read(obj, 4);
						} else if (obj instanceof GXDLMSRegister || obj instanceof GXDLMSExtendedRegister) {
							this.reader.read(obj, 3);
						}
						var unit = this.getBillingUnit(obj);
						if (unit != null) {
							data.put("unit", this.formatBillingUnit(unit));
						}
					} catch (Exception e) {
						this.log.debug("Skipping unsupported scaler/unit attribute for OBIS [{}]: {}", standard_obis,
								e.getMessage());
					}
					try {
						var value = this.reader.read(obj, 2);
						data.put("value", this.scaleBillingValue(value, this.getBillingUnit(obj)));
					} catch (Exception e) {
						this.log.warn("Skipping billing value OBIS [{}] because attribute 2 failed: {}", standard_obis,
								e.getMessage());
						data.put("error", e.getMessage());
					}
			objects.add(data);
		}
		return objects.toArray();
	}

	private Unit getBillingUnit(GXDLMSObject obj) {
		if (obj instanceof GXDLMSDemandRegister) {
			return ((GXDLMSDemandRegister) obj).getUnit();
		}
		if (obj instanceof GXDLMSRegister) {
			return ((GXDLMSRegister) obj).getUnit();
		}
		return null;
	}

	private String formatBillingUnit(Unit unit) {
		if (unit == null) {
			return null;
		}
		switch (unit.name()) {
		case "ACTIVE_ENERGY":
			return "MWh";
		case "REACTIVE_ENERGY":
			return "Mvarh";
		case "AMBIENT_ENERGY":
		case "APPARENT_ENERGY":
			return "MVAh";
		case "VOLTAGE":
			return "kV";
		case "CURRENT":
			return "A";
		case "FREQUENCY":
			return "Hz";
		case "ACTIVE_POWER":
			return "MW";
		case "REACTIVE_POWER":
			return "Mvar";
		case "APPARENT_POWER":
			return "MVA";
		case "PHASE_ANGLE_DEGREE":
			return "\u00b0";
		case "MINUTE":
			return "min";
		case "ACTIVE":
			return "imp/Wh";
		case "REACTIVE":
			return "imp/varh";
		default:
			return unit.name();
		}
	}

	private Object scaleBillingValue(Object value, Unit unit) {
		if (!(value instanceof Number)) {
			return value;
		}
		var raw = ((Number) value).doubleValue();
		if (this.isBillingMegaUnit(unit)) {
			return raw / 1_000_000d;
		}
		if (this.isBillingKiloUnit(unit)) {
			return raw / 1_000d;
		}
		return value;
	}

	private boolean isBillingMegaUnit(Unit unit) {
		if (unit == null) {
			return false;
		}
		switch (unit.name()) {
		case "ACTIVE_ENERGY":
		case "REACTIVE_ENERGY":
		case "AMBIENT_ENERGY":
		case "APPARENT_ENERGY":
		case "ACTIVE_POWER":
		case "REACTIVE_POWER":
		case "APPARENT_POWER":
			return true;
		default:
			return false;
		}
	}

	private boolean isBillingKiloUnit(Unit unit) {
		return unit != null && "VOLTAGE".equals(unit.name());
	}
}
