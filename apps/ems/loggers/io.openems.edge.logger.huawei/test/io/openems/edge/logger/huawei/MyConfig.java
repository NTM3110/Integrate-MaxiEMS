package io.openems.edge.logger.huawei;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;

public class MyConfig extends AbstractComponentConfig implements Config {

	public static class Builder {
		private String id = "smartLogger0";
		private String modbusId = "modbus0";
		private int modbusUnitId = 1;
		private String inverterAddresses = "1";

		private Builder() {
		}

		public Builder withInverterAddresses(String inverterAddresses) {
			this.inverterAddresses = inverterAddresses;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String modbus_id() {
		return this.builder.modbusId;
	}

	@Override
	public int modbusUnitId() {
		return this.builder.modbusUnitId;
	}

	@Override
	public String inverterAddresses() {
		return this.builder.inverterAddresses;
	}

	@Override
	public String Modbus_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.modbus_id());
	}

	@Override
	public boolean invert_meter() {
		return false;
	}
}
