package io.openems.edge.controller.servicemanager;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

@Designate(ocd = Config.class, factory = true)
@Component(
    name = "Controller.ServiceManager",
    immediate = true,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
public class ServiceManagerImpl extends AbstractOpenemsComponent 
        implements OpenemsComponent, Controller {

    private final Logger log = LoggerFactory.getLogger(ServiceManagerImpl.class);

    @Reference
    private ConfigurationAdmin cm;

    private Config config;
    private List<String> createdConfigurations = new ArrayList<>();

    public ServiceManagerImpl() {
        super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values());
    }

    @Activate
    protected void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        if (config.enabled()) {
            applyServiceConfiguration();
        }
    }

    @Modified
    protected void modified(ComponentContext context, Config config) {
        super.modified(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        if (config.enabled()) {
            // Clear existing and reapply
            clearExistingConfigurations();
            applyServiceConfiguration();
        }
    }

    @Deactivate
    protected void deactivate() {
        clearExistingConfigurations();
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsError.OpenemsNamedException {
        // Service manager doesn't need to run periodically
        // It only acts on configuration changes
    }

    private void applyServiceConfiguration() {
        switch (this.config.serviceMode()) {
            case MAXIEMS:
                applyMaxiEMSService();
                break;
            case AT_DIGITAL_METER_DLMS:
                applyDLMSService();
                break;
            case CUSTOM:
                applyCustomService();
                break;
        }
    }

    private void applyMaxiEMSService() {
        log.info("Applying MaxiEMS service configuration");
        
        // Define MaxiEMS components
        String[][] components = {
            {"Controller.Api.ModbusTcp.Evn", "ctrlModbusEvn0", "EVN Modbus Controller"},
            {"Controller.Api.Websocket", "ctrlWebsocket0", "WebSocket Controller"},
            {"Controller.PvInverter.FixPowerLimit", "ctrlPowerLimit0", "Power Limit Controller"},
            {"Controller.PvInverter.LimitReactivePower", "ctrlReactivePower0", "Reactive Power Controller"},
            {"Monitoring.maxicom", "monitoring0", "MaxiCOM Monitoring"},
            {"Scheduler.AllAlphabetically", "scheduler0", "Scheduler"},
            {"Controller.Api.Rest.ReadWrite", "ctrlRest0", "REST API Controller"},
            {"Timedata.Rrd4j", "timedataRrd4j0", "RRD4j Timedata"}
        };
        
        for (String[] comp : components) {
            createComponentConfiguration(comp[0], comp[1], comp[2]);
        }
    }

    private void applyDLMSService() {
        log.info("Applying AT Digital Meter DLMS service configuration");
        
        // Parse bridge configuration
        String[] comPorts = parseCommaSeparated(this.config.dlmsComPorts());
        int numBridges = comPorts.length;
        
        // Create DLMS bridges
        for (int i = 0; i < numBridges; i++) {
            String bridgeId = "bridgeDlms" + i;
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("serialPort", comPorts[i]);
            props.put("baudRate", 9600);
            createComponentConfiguration("Bridge.Dlms", bridgeId, "DLMS Bridge " + (i + 1), props);
        }
        
        // Parse meter configuration
        String[] meterAddresses = parseCommaSeparated(this.config.dlmsMeterAddresses());
        String[] meterBridgeAssignments = parseCommaSeparated(this.config.dlmsMeterBridgeAssignments());
        int numMeters = meterAddresses.length;
        
        // Create Landis meters
        for (int i = 0; i < numMeters; i++) {
            String meterId = "meterLandis" + i;
            String bridgeId = "bridgeDlms" + (i < meterBridgeAssignments.length ? 
                Integer.parseInt(meterBridgeAssignments[i].trim()) : 0);
            
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("bridgeId", bridgeId);
            props.put("deviceAddress", Integer.parseInt(meterAddresses[i].trim()));
            createComponentConfiguration("Meter.Landis", meterId, "Landis Meter " + (i + 1), props);
        }
    }

    private void applyCustomService() {
        log.info("Applying custom service configuration");
        String[] factoryPids = parseCommaSeparated(this.config.customComponentList());
        
        for (int i = 0; i < factoryPids.length; i++) {
            String factoryPid = factoryPids[i].trim();
            String componentId = "custom" + i;
            createComponentConfiguration(factoryPid, componentId, "Custom Component " + (i + 1));
        }
    }

    private void createComponentConfiguration(String factoryPid, String id, String alias) {
        createComponentConfiguration(factoryPid, id, alias, new Hashtable<>());
    }

    private void createComponentConfiguration(String factoryPid, String id, String alias, 
            Dictionary<String, Object> additionalProps) {
        try {
            // Check if configuration already exists
            String filter = String.format("(&(%s=%s)(id=%s))", 
                ConfigurationAdmin.SERVICE_FACTORYPID, factoryPid, id);
            Configuration[] existingConfigs = this.cm.listConfigurations(filter);
            
            if (existingConfigs != null && existingConfigs.length > 0) {
                log.info("Configuration already exists for {}:{}, updating", factoryPid, id);
                // Update existing configuration
                Dictionary<String, Object> props = existingConfigs[0].getProperties();
                if (props == null) {
                    props = new Hashtable<>();
                }
                props.put("enabled", true);
                props.put("alias", alias);
                
                // Add additional properties
                for (java.util.Enumeration<String> keys = additionalProps.keys(); keys.hasMoreElements();) {
                    String key = keys.nextElement();
                    props.put(key, additionalProps.get(key));
                }
                
                existingConfigs[0].update(props);
                trackConfiguration(existingConfigs[0].getPid());
            } else {
                // Create new configuration
                Configuration newConfig = this.cm.createFactoryConfiguration(factoryPid, "?");
                Dictionary<String, Object> props = new Hashtable<>();
                props.put("id", id);
                props.put("alias", alias);
                props.put("enabled", true);
                
                // Add additional properties
                for (java.util.Enumeration<String> keys = additionalProps.keys(); keys.hasMoreElements();) {
                    String key = keys.nextElement();
                    props.put(key, additionalProps.get(key));
                }
                
                newConfig.update(props);
                trackConfiguration(newConfig.getPid());
                log.info("Created configuration for {}:{}", factoryPid, id);
            }
        } catch (Exception e) {
            log.error("Failed to create configuration for {}:{}", factoryPid, id, e);
        }
    }

    private void clearExistingConfigurations() {
        log.info("Clearing {} existing configurations", createdConfigurations.size());
        for (String pid : createdConfigurations) {
            try {
                Configuration config = this.cm.getConfiguration(pid, "?");
                if (config != null) {
                    // Disable instead of delete to preserve configuration
                    Dictionary<String, Object> props = config.getProperties();
                    if (props != null) {
                        props.put("enabled", false);
                        config.update(props);
                        log.info("Disabled configuration {}", pid);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to disable configuration {}", pid, e);
            }
        }
        createdConfigurations.clear();
    }

    private void trackConfiguration(String pid) {
        if (!createdConfigurations.contains(pid)) {
            createdConfigurations.add(pid);
        }
    }

    private String[] parseCommaSeparated(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        return value.split(",");
    }
}
