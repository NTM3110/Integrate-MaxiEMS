package io.openems.edge.controller.tools;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.type.CreateComponentConfig;
import io.openems.common.jsonrpc.request.CreateComponentConfigRequest;
import io.openems.common.jsonrpc.request.UpdateComponentConfigRequest;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.user.User;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.openems.common.exceptions.OpenemsError;
import java.io.IOException;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.common.jsonrpc.base.JsonrpcResponseError;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.component.OpenemsComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

import io.openems.edge.bms.huasu.tc.BmsHuasuTc;
import io.openems.edge.bms.huasu.ta.BmsHuasuTa;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.channel.Doc;

import io.openems.edge.controller.api.Controller;

@Designate(ocd = Config.class, factory = true)
@Component(
        name = "Controller.BmsHuasuTc.BulkCreator",
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE
)
public class ControllerBmsHuasuTcBulkCreatorImpl extends AbstractOpenemsComponent 
            implements ComponentJsonApi, OpenemsComponent, Controller {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        ;
        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    public ControllerBmsHuasuTcBulkCreatorImpl() {
        super(OpenemsComponent.ChannelId.values(),
              Controller.ChannelId.values(),
              ChannelId.values());
    }

    private final Logger log = LoggerFactory.getLogger(ControllerBmsHuasuTcBulkCreatorImpl.class);
    private final String factoryIdTa = "BMS.Huasu.TA";
    private final String factoryIdTc = "BMS.Huasu.TC";
    private final String baseComponentTcId = "bmsHuasuTc";
    private final String baseComponentTaId = "bmsHuasuTa";


    @Reference
    private ConfigurationAdmin cm;

    @Reference
    private ComponentManager componentManager;

    private Config config;

    private SoCEngine socEngine;

    // a simple system user to call ComponentManager methods
    private final User systemUser = new User("system", "system", Language.EN, Role.ADMIN);

	private String savedStringId;

    @Activate
    protected void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        try{
            this.config = config;
            deleteComponents();
            this.createComponents();
        } catch (Exception e) {
            this.log.error("Failed to create BMS components", e);
        }
        socEngine = new SoCEngine(config.cellCapacityAh(), config.cellCutoffVoltage(), config.cellFloatVoltage());
    }

    @Modified
    protected void modified(ComponentContext context, Config config) {
        super.modified(context, config.id(), config.alias(), config.enabled());
        try {
            if (this.config != null) {
                deleteComponents();
            }
            this.config = config;
            this.createComponents();

            socEngine.setCnominal(config.cellCapacityAh());
            socEngine.setVcutoff(config.cellCutoffVoltage());
            socEngine.setVfloat(config.cellFloatVoltage());
        } catch (Exception e) {
            this.log.error("Failed to create BMS components on modified", e);
        }
    }

    @Deactivate
    protected void deactivate() {
        // The '*' matches any characters (e.g., bmsHuasuTc1, bmsHuasuTc2, etc.)
        try {
            if (this.config != null) {
                deleteComponents();
            }
        } catch (Exception e) {
            this.log.error("Error while deleting BMS components on deactivate", e);
        }
        super.deactivate();
    }

    /**
    * Handle create components.
    * @throws OpenemsNamedException on error while creating components
    */

   private void deleteComponents() throws OpenemsNamedException {
        final String stringId = this.config.stringId();

        try{
            String filter = String.format("(&(|(%s=%s)(%s=%s))(|(id=%s*)(id=%s*)))",
                    ConfigurationAdmin.SERVICE_FACTORYPID, 
                    this.factoryIdTa, 
                    ConfigurationAdmin.SERVICE_FACTORYPID,
                    this.factoryIdTc,
                    this.baseComponentTcId + "-str" + stringId,
                    this.baseComponentTaId + "-str" + stringId
            );
            org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);
            if (configs != null && configs.length > 0) {
                //There is configuration already, we assume that it was created by this component and delete it to create new ones with the new configuration
                for (org.osgi.service.cm.Configuration config : configs) {
                    this.log.info("Existing BMS component found: {}", config.getPid());
                    config.delete();
                }
            }
        } catch (Exception e) {
            this.log.error("Error while deleting BMS components", e);
            throw new OpenemsNamedException(OpenemsError.GENERIC, "Failed to delete BMS components: " + e.getMessage());
        }
   }

    private void createComponents() throws OpenemsNamedException {
        final int numberOfCells = this.config.numberOfCells();
        final String modbusId = this.config.modbusId();;
        final int startUnitId = this.config.startUnitId();
        final String stringId = this.config.stringId();

        if (numberOfCells <= 0) {
            this.log.info("numberOfCells <= 0, nothing to create");
            return;
        }

        try {
            // String factoryIdTa = "BMS.Huasu.TA";
            // String factoryIdTc = "BMS.Huasu.TC";
            // String baseComponentTcId = "bmsHuasuTc";
            // String baseComponentTaId = "bmsHuasuTa";

                
            // The '*' matches any characters (e.g., bmsHuasuTc1, bmsHuasuTc2, etc.)
            String filter = String.format("(&(|(%s=%s)(%s=%s))(|(id=%s*)(id=%s*)))",
                ConfigurationAdmin.SERVICE_FACTORYPID, 
                this.factoryIdTa, 
                ConfigurationAdmin.SERVICE_FACTORYPID,
                this.factoryIdTc,
                this.baseComponentTcId + "-str" + savedStringId,
                this.baseComponentTaId + "-str" + savedStringId
            );
			savedStringId = stringId;
            org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);
            if (configs != null && configs.length > 0) {
                //There is configuration already, we assume that it was created by this component and delete it to create new ones with the new configuration
                for (org.osgi.service.cm.Configuration config : configs) {
                    this.log.info("Existing BMS component found: {}", config.getPid());
                    config.delete();
                }
            }

            this.log.info("Creating new BMS components...");

            // for (int j = 0; j < stringCount; j++) {

            for (int i = 0; i < numberOfCells; i++) {
                java.util.Dictionary<String, Object> props = new java.util.Hashtable<>();

                props.put("id", baseComponentTaId + "-str" + stringId + "-cell" + (i + 1));
                props.put("alias", "BMS Huasu TA " + "-str" + stringId + "-cell" + (i + 1));
                props.put("enabled", true);
                props.put("modbusId", modbusId);
                props.put("modbusUnitId", startUnitId + i);

                org.osgi.service.cm.Configuration newConfig = this.cm.createFactoryConfiguration(factoryIdTa, "?");
                newConfig.update(props);
            }

            java.util.Dictionary<String, Object> props = new java.util.Hashtable<>();
            props.put("id", baseComponentTcId + "-str" + stringId);
            props.put("alias", "BMS Huasu TC of String " + stringId);
            props.put("enabled", true);
            props.put("modbusId", modbusId);
            props.put("modbusUnitId", 206);

            org.osgi.service.cm.Configuration newConfig = this.cm.createFactoryConfiguration(factoryIdTc, "?");
            newConfig.update(props);
        } catch (Exception e) {
            this.log.error("Error while creating BMS components", e);
           throw new OpenemsNamedException(OpenemsError.GENERIC, "Failed to create BMS components: " + e.getMessage());
        }
    }

    public static final String GET_STRING_DATA_METHOD = "getStringData";
    
    @Override
    public void buildJsonApiRoutes(JsonApiBuilder builder) {
        builder.handleRequest(GET_STRING_DATA_METHOD, call -> {
            try {
                // 1. Get the TC Component (String representation)
                // The configured ID matches the pattern: "bmsHuasuTc-str" + stringId
                String stringId = this.config.stringId();
                String tcId = "bmsHuasuTc-str" + stringId;
                BmsHuasuTc bmsTcModule = this.componentManager.getComponent(tcId);
                // Read a channel from the TC module
                Integer ambientTemp = bmsTcModule.getAmbientTemperature().get();
                Integer stringCurrent = bmsTcModule.getStringCurrent().get();
                Double stringSoc = bmsTcModule.getStringSoC().get();
                Double stringSoh = bmsTcModule.getStringSoH().get();

                JsonObject stringDataJson = new JsonObject();
                stringDataJson.addProperty("ambientTemperature", ambientTemp != null ? ambientTemp / 10.0 : null); // convert to °C
                stringDataJson.addProperty("stringCurrent", stringCurrent != null ? stringCurrent / 10.0 : null); // convert to A
                stringDataJson.addProperty("stringSoC", stringSoc);
                stringDataJson.addProperty("stringSoH", stringSoh);

                // JsonObject stringJson = new JsonObject();
                // stringJson.add("stringData", stringDataJson);
                // 2. Get the TA Component (Cell representation)
                // The configured ID matches the pattern: "bmsHuasuTa-str" + stringId + "-cell" + cellId
                 // Example for cell 1
                JsonArray cellsArray = new JsonArray();
                for(int i = 0; i < this.config.numberOfCells(); i++){
                    JsonObject cellData = new JsonObject();
                    String taId = "bmsHuasuTa-str" + stringId + "-cell" + (i + 1);
                    BmsHuasuTa bmsTaModule = this.componentManager.getComponent(taId);
                    // Read a channel from the TA module
                    Integer cellVoltage = bmsTaModule.getBatteryVoltage().get();
                    Integer cellTemperature = bmsTaModule.getBatteryTemperature().get();
                    Integer cellInternalResistance = bmsTaModule.getBatteryInternalResistance().get();
                    Double cellSoC = bmsTaModule.getBatterySoC().get();
                    Double cellSoH = bmsTaModule.getBatterySoH().get();
                    cellData.addProperty("cellId", i + 1);
                    cellData.addProperty("voltage", cellVoltage);
                    cellData.addProperty("temperature", cellTemperature);
                    cellData.addProperty("internalResistance", cellInternalResistance);
                    cellData.addProperty("soc", cellSoC);
                    cellData.addProperty("soh", cellSoH);
                    cellsArray.add(cellData);
                }
                // Put the data into your JSON-RPC response
                // JsonObject cellsJson = new JsonObject();
                // cellsJson.add("cellsData", cellsArray);
                JsonObject json = new JsonObject();
                json.add("string_data", stringDataJson);
                json.add("cells_data", cellsArray);
                return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json);
                // response.put("string1_voltage", stringVoltage);
                // response.put("string1_cell1_voltage", cellVoltage);
            } catch (OpenemsNamedException e) {
                // This exception is thrown if the component with the requested ID is not deployed/enabled
                // or if the channel has no active value yet (for .getOrError()).
                return new JsonrpcResponseError(call.getRequest().getId(), e);
            }
        });
    }

    // private void setLatestSoC(BmsHuasuTc bmsTcModule, double soc, BmsHuasuTa bmsTaModule) {
    //     Integer rawCellVoltage = bmsTaModule.getSingleVoltage().get();

    // }

    private void pushCalculatedDatatoChannels(int cellNumber, Double[] cellVoltages, Double[] cellTemperatures, Double[] cellInternalResistances, Double[] cellSocs, Double[] cellSohs, BmsHuasuTc bmsTcModule) {
		// logger.info("Pushing calculated data to channels. stringNumber: {}, cellNumber: {}");
		double strSOC = Helper.calculateStringSOC(cellNumber, cellSocs);
        bmsTcModule._setStringSoC(strSOC);
		double strSOH = Helper.calculateStringSOH(cellNumber, cellSohs);
        bmsTcModule._setStringSoH(strSOH);


        Helper.Result result = Helper.getMaxVoltageBattery(cellNumber, cellVoltages);
		double maxVoltage = result.value;
        bmsTcModule._setMaxVoltage(maxVoltage);
		int maxVoltageIndex = result.index;
        bmsTcModule._setMaxVoltageCellIndex(maxVoltageIndex);
        Helper.Result minVoltageResult = Helper.getMinVoltageBattery(cellNumber, cellVoltages);
        double minVoltage = minVoltageResult.value;
        bmsTcModule._setMinVoltage(minVoltage);
        int minVoltageIndex = minVoltageResult.index;
        bmsTcModule._setMinVoltageCellIndex(minVoltageIndex);
        double strVoltage = Helper.calculateStringVoltage(cellNumber, cellVoltages);
        bmsTcModule._setStringVoltage(strVoltage);
        double averageVoltage = strVoltage / cellNumber;
        bmsTcModule._setAvgVoltage(averageVoltage);

        Helper.Result maxTempResult = Helper.getMaxTemperatureBattery(cellNumber, cellTemperatures);
        double maxTemp = maxTempResult.value;
        bmsTcModule._setMaxTemperature(maxTemp);
        int maxTempIndex = maxTempResult.index;
        bmsTcModule._setMaxTemperatureCellIndex(maxTempIndex);
        Helper.Result minTempResult = Helper.getMinTemperatureBattery(cellNumber, cellTemperatures);
        double minTemp = minTempResult.value;
        bmsTcModule._setMinTemperature(minTemp);
        int minTempIndex = minTempResult.index;
        bmsTcModule._setMinTemperatureCellIndex(minTempIndex); 
        double averageTemp = Helper.getAverageTemperatureBattery(cellNumber, cellTemperatures);
        bmsTcModule._setAvgTemperature(averageTemp);


        Helper.Result maxResistanceResult = Helper.getMaxResistanceBattery(cellNumber, cellInternalResistances);
        double maxResistance = maxResistanceResult.value;
        bmsTcModule._setMaxResistance((int)maxResistance);
        int maxResistanceIndex = maxResistanceResult.index;
        bmsTcModule._setMaxResistanceCellIndex(maxResistanceIndex);
        Helper.Result minResistanceResult = Helper.getMinResistanceBattery(cellNumber, cellInternalResistances);
        double minResistance = minResistanceResult.value;
        bmsTcModule._setMinResistance((int)minResistance);
        int minResistanceIndex = minResistanceResult.index;
        bmsTcModule._setMinResistanceCellIndex(minResistanceIndex); 
        double averageResistance = Helper.getAverageResistanceBattery(cellNumber, cellInternalResistances);
        bmsTcModule._setAvgResistance(averageResistance);

	}


    @Override
    public void run() throws OpenemsNamedException{

        try{
            this.log.info("ControllerBmsHuasuTcBulkCreatorImpl is running...");
            String tcId = "bmsHuasuTc-str" + this.config.stringId();
            BmsHuasuTc bmsTcModule = this.componentManager.getComponent(tcId);
            Integer stringTempRaw = bmsTcModule.getAmbientTemperatureChannel().value().get();
            Double stringTemp = stringTempRaw != null ? stringTempRaw / 10.0 : null; // convert to °C
            Integer stringCurrentRaw = bmsTcModule.getStringCurrentChannel().value().get();
            Double stringCurrent = stringCurrentRaw != null ? stringCurrentRaw / 10.0 : null; // convert to A

            this.log.info("String ambient temperature: {}", stringTemp);
            Double[] cellVoltages = new Double[this.config.numberOfCells()];
            Double[] cellTemperatures = new Double[this.config.numberOfCells()];
            Double[] cellInternalResistances = new Double[this.config.numberOfCells()];
            Double[] cellSocs = new Double[this.config.numberOfCells()];
            Double[] cellSohs = new Double[this.config.numberOfCells()];
            boolean dataReady = false;
            for(int i = 0;  i < this.config.numberOfCells(); i++){
                String taId = "bmsHuasuTa-str" + this.config.stringId() + "-cell" + (i + 1);
                BmsHuasuTa bmsTaModule = this.componentManager.getComponent(taId);
                Double cellSoC = bmsTaModule.getBatterySoC().get();
                this.log.info("Cell {} SoC: {}", i + 1, cellSoC);

                if(bmsTaModule.getBatteryVoltage().get() != null){
                    dataReady = true;
                    if (cellSoC == null) {
                        cellVoltages[i] = (double) bmsTaModule.getBatteryVoltage().get() / 1000; // convert mV to V
                        cellTemperatures[i] = (double) bmsTaModule.getBatteryTemperature().get() / 10; // convert to °C
                        this.log.info("Cell {} voltage: {}", i + 1, cellVoltages[i]);
                        cellSoC = socEngine.initialSoCFromVoltage(cellVoltages[i]);
                        this.log.info("Cell {} initial SoC from voltage: {}", i + 1, cellSoC);
                        bmsTaModule._setBatterySoC(cellSoC);
                        cellSocs[i] = cellSoC;
                        Double cellInternalResistance = (double) bmsTaModule.getBatteryInternalResistance().get(); // convert uOhm to Ohm
                        cellInternalResistances[i] = cellInternalResistance;
                        this.log.info("Cell {} internal resistance: {}", i + 1, cellInternalResistances[i]);
                        Double cellSoH = SoHEngine.updatedSoHRegular(cellInternalResistance, this.config.ratedResistanceUohm());
                        this.log.info("Cell {} initial SoH from resistance: {}", i + 1, cellSoH);
                        bmsTaModule._setBatterySoH(cellSoH);
                        cellSohs[i] = cellSoH;
                    }
                    else{
                        if(cellVoltages[i] == null || cellTemperatures[i] == null || cellInternalResistances[i] == null){
                            cellVoltages[i] = (double) bmsTaModule.getBatteryVoltage().get() / 1000; // convert mV to V
                            cellTemperatures[i] = (double) bmsTaModule.getBatteryTemperature().get() / 10; // convert to °C
                            cellInternalResistances[i] = (double) bmsTaModule.getBatteryInternalResistance().get(); // convert uOhm to Ohm
                        }
                        else{
                            if(SoCEngine.updateVoltageWithTemp(cellVoltages[i], cellTemperatures[i]) - SoCEngine.updateVoltageWithTemp(bmsTaModule.getBatteryVoltage().get()/1000, bmsTaModule.getBatteryTemperature().get()/10) < 0.0){
                                stringCurrent = -stringCurrent; // charge
                            }
                            cellVoltages[i] = (double) bmsTaModule.getBatteryVoltage().get() / 1000; // convert mV to V
                            cellTemperatures[i] = (double) bmsTaModule.getBatteryTemperature().get() / 10;
                            cellInternalResistances[i] = (double) bmsTaModule.getBatteryInternalResistance().get(); // convert uOhm to Ohm
                        }
                        double updatedSoC = socEngine.updatedSoCEKF(cellVoltages[i], stringCurrent, cellTemperatures[i], 1);
                        this.log.info("Cell {} updated SoC from EKF: {}", i + 1, updatedSoC * 100);
                        bmsTaModule._setBatterySoC(updatedSoC * 100);
                        cellSocs[i] = updatedSoC * 100;

                        double updatedSoH = SoHEngine.updatedSoHRegular(cellInternalResistances[i], this.config.ratedResistanceUohm());
                        this.log.info("Cell {} updated SoH from resistance: {}", i + 1, updatedSoH * 100);
                        bmsTaModule._setBatterySoH(updatedSoH * 100);
                        cellSohs[i] = updatedSoH * 100;
                    }
                }else {
                    this.log.warn("Cell {} voltage is null, cannot start calculating SoC", i + 1);
                }
            }
            if(dataReady){
                pushCalculatedDatatoChannels(this.config.numberOfCells(), cellVoltages, cellTemperatures, cellInternalResistances, cellSocs, cellSohs, bmsTcModule);
                this.log.info("---------------End loop: String voltage: {} -------------------", bmsTcModule.getStringVoltage().toString());
            }
        }
        catch (Exception e) {
            this.log.error("Error in ControllerBmsHuasuTcBulkCreatorImpl run method", e);
            throw new OpenemsNamedException(OpenemsError.GENERIC, "Error in ControllerBmsHuasuTcBulkCreatorImpl run method: " + e.getMessage());
        }
    }
}
