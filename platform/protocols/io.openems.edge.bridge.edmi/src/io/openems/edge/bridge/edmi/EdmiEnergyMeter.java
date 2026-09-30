package io.openems.edge.bridge.edmi;

public record EdmiEnergyMeter(String meterId, String role, String sourceType) {

	public boolean isBessSource() {
		return "SOURCE".equals(this.role) && "BESS".equals(this.sourceType);
	}

	public boolean isRtsSource() {
		return "SOURCE".equals(this.role) && "RTS".equals(this.sourceType);
	}

	public boolean isSelfUse() {
		return "SELF_USE".equals(this.role);
	}

	public boolean isGridPoint() {
		return "GRID_POINT".equals(this.role);
	}

	public boolean isInterconnect() {
		return "INTERCONNECT".equals(this.role);
	}
}
