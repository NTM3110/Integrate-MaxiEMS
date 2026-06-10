package io.openems.edge.meter.iec104.test;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.edge.bridge.iec104.api.AbstractOpenemsIec104Component;
import io.openems.edge.bridge.iec104.api.Iec104Bridge;
import io.openems.edge.bridge.iec104.api.Iec104Component;
import io.openems.edge.bridge.iec104.api.Iec104Protocol;
import io.openems.edge.bridge.iec104.api.element.Iec104DoublePointElement;
import io.openems.edge.bridge.iec104.api.element.Iec104FloatElement;
import io.openems.edge.bridge.iec104.api.element.Iec104NormalizedValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104ScaledValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104SinglePointElement;
import io.openems.edge.bridge.iec104.api.element.Iec104StepPositionElement;
import io.openems.edge.common.component.OpenemsComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IEC104 Test Meter implementation.
 * 
 * <p>
 * Demonstrates IEC104 bridge usage with various measurement types.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.IEC104.Test", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class Iec104TestMeterImpl extends AbstractOpenemsIec104Component
		implements Iec104TestMeter, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(Iec104TestMeterImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	private int commonAddress;

	public Iec104TestMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Iec104Component.ChannelId.values(), //
				Iec104TestMeter.ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		this.commonAddress = config.commonAddress();
		this.log.info("Activating IEC104 Test Meter [id={}, commonAddress={}]", config.id(), this.commonAddress);
		try {
			super.activate(context, config.id(), config.alias(), config.enabled(), this.cm,
					"Iec104Bridge", config.bridge_id());
		} catch (Exception e) {
			this.log.error("Failed to activate IEC104 Test Meter: {}", e.getMessage());
		}
	}

	@Deactivate
	@Override
	protected void deactivate() {
		this.log.info("Deactivating IEC104 Test Meter");
		super.deactivate();
	}

	@Reference(//
			cardinality = ReferenceCardinality.OPTIONAL, //
			policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY //
	)
	@Override
	protected void setIec104Bridge(Iec104Bridge bridge) {
		super.setIec104Bridge(bridge);
		this.log.info("IEC104 Bridge connected");
	}

	@Override
	protected void unsetIec104Bridge(Iec104Bridge bridge) {
		super.unsetIec104Bridge(bridge);
		this.log.info("IEC104 Bridge disconnected");
	}

	@Override
	public int getCommonAddress() {
		return this.commonAddress;
	}

	@Override
	protected Iec104Protocol defineIec104Protocol() {
		return new Iec104Protocol(//
				// IOA 1001: Single Point (M_SP_NA_1, Type ID 1)
				m(Iec104TestMeter.ChannelId.IOA_1001_SINGLE_POINT, new Iec104SinglePointElement(1001)),
				//
				// IOA 1003: Double Point (M_DP_NA_1, Type ID 3)
				m(Iec104TestMeter.ChannelId.IOA_1003_DOUBLE_POINT, new Iec104DoublePointElement(1003)),
				//
				// IOA 1005: Step Position (M_ST_NA_1, Type ID 5)
				m(Iec104TestMeter.ChannelId.IOA_1005_STEP_POSITION, new Iec104StepPositionElement(1005)),
				//
				// IOA 1009: Normalized Value (M_ME_NA_1, Type ID 9)
				m(Iec104TestMeter.ChannelId.IOA_1009_NORMALIZED_VALUE, new Iec104NormalizedValueElement(1009)),
				//
				// IOA 1011: Scaled Value (M_ME_NB_1, Type ID 11)
				m(Iec104TestMeter.ChannelId.IOA_1011_SCALED_VALUE, new Iec104ScaledValueElement(1011)),
				//
				// IOA 1013: Short Float (M_ME_NC_1, Type ID 13)
				m(Iec104TestMeter.ChannelId.IOA_1013_FLOAT_VALUE, new Iec104FloatElement(1013))//
		);
	}

	@Override
	public String debugLog() {
		return "IEC104 SP:" + this.channel(Iec104TestMeter.ChannelId.IOA_1001_SINGLE_POINT).value().asString()
				+ " | DP:" + this.channel(Iec104TestMeter.ChannelId.IOA_1003_DOUBLE_POINT).value().asString()
				+ " | ST:" + this.channel(Iec104TestMeter.ChannelId.IOA_1005_STEP_POSITION).value().asString()
				+ " | NA:" + this.channel(Iec104TestMeter.ChannelId.IOA_1009_NORMALIZED_VALUE).value().asString()
				+ " | SC:" + this.channel(Iec104TestMeter.ChannelId.IOA_1011_SCALED_VALUE).value().asString()
				+ " | FL:" + this.channel(Iec104TestMeter.ChannelId.IOA_1013_FLOAT_VALUE).value().asString();
	}

}
