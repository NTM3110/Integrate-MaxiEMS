package io.openems.edge.controller.pvinverter.zeroexport;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id = null;
		private int powerLimit;
		private boolean allowEvnControl;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setPowerLimit(int powerLimit) {
			this.powerLimit = powerLimit;
			return this;
		}

		public Builder setAllowEvnControl(boolean allowEvnControl) {
			this.allowEvnControl = allowEvnControl;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String meter_id() {
		return "meter0";
	}

	@Override
	public String evn_id() {
		return "ctrlEvnModbus0";
	}

	@Override
	public boolean allowEvnControl() {
		return this.builder.allowEvnControl;
	}

	@Override
	public boolean usePercentage() {
		return false;
	}

	@Override
	public int powerLimit() {
		return this.builder.powerLimit;
	}

	@Override
	public int powerLimitPercent() {
		return 100;
	}

	@Override
	public double deadbandPercent() {
		return 1.0;
	}

	@Override
	public double maxRampRatePercent() {
		return 20.0;
	}

	@Override
	public double gainKp() {
		return 0.25;
	}

	@Override
	public double gainKi() {
		return 0.005;
	}

	@Override
	public double gainKaw() {
		return 0.1;
	}

	@Override
	public double filterAlpha() {
		return 0.5;
	}

	@Override
	public double outputSmoothing() {
		return 0.8;
	}
}
