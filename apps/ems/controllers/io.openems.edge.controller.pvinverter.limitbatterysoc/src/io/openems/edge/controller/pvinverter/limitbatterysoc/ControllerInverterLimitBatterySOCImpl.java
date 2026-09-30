package io.openems.edge.controller.pvinverter.limitbatterysoc;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Inverter.LimitBatterySOC", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerInverterLimitBatterySOCImpl extends AbstractOpenemsComponent
        implements ControllerInverterLimitBatterySOC, Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ControllerInverterLimitBatterySOCImpl.class);

    @Reference
    private ComponentManager componentManager;

    private String pvInverterId;
    private int maxChargeSOC = 80;
    private int maxDischargeSOC = 20;

    public ControllerInverterLimitBatterySOCImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerInverterLimitBatterySOC.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.pvInverterId = config.pvInverter_id();
        this.maxChargeSOC = config.maxChargeSOC();
        this.maxDischargeSOC = config.maxDischargeSOC();
    }

    @Override
    @Deactivate
    protected void deactivate() {
        ManagedSymmetricPvInverter pvInverter;
        try {
            pvInverter = this.componentManager.getComponent(this.pvInverterId);
            pvInverter.setMaxChargeSocLimit(null);
            pvInverter.setMaxDischargeSocLimit(null);
        } catch (OpenemsNamedException e) {
            this.logError(this.log, e.getMessage());
        }

        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        ManagedSymmetricPvInverter pvInverter = this.componentManager.getComponent(this.pvInverterId);
        pvInverter.setMaxChargeSocLimit(this.maxChargeSOC);
        pvInverter.setMaxDischargeSocLimit(this.maxDischargeSOC);
    }
}
