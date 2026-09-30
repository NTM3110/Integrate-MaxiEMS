package io.openems.edge.controller.alarm.threshold;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {
	private final String plantName;
	private final int debounceSeconds;
	private final int batchWindowSeconds;

	public MyConfig(String id, String plantName, int debounceSeconds, int batchWindowSeconds) {
		super(Config.class, id);
		this.plantName = plantName;
		this.debounceSeconds = debounceSeconds;
		this.batchWindowSeconds = batchWindowSeconds;
	}

	@Override
	public String plantName() {
		return this.plantName;
	}

	@Override
	public String email_id() {
		return "email0";
	}

	@Override
	public int debounceSeconds() {
		return this.debounceSeconds;
	}

	@Override
	public int batchWindowSeconds() {
		return this.batchWindowSeconds;
	}

	@Override
	public String emailService_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.email_id());
	}
}
