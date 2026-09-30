package io.openems.edge.bms.controllers.calculation.legacy;

import io.openems.edge.bms.service.calculation.model.CapacityTemperatureFactor;

public class SoHEngine {
	private static final double C_NOMINAL_AH = 100.0; // Ah
    private static final double C_NOMINAL_AS = C_NOMINAL_AH * 3600;
	private static final double RESISTANCE_SOH_DECAY = 0.55;

	
	public static class Status {
        public boolean isStart;        // isStart
        public boolean isEnd;   // R0 passed in
		public double usedQ;

        public Status(boolean isStart, boolean isEnd, double usedQ) {
            this.isStart = isStart;
			this.isEnd = isEnd;
			this.usedQ = usedQ;
        }
    }
	

	public static double usedQ;
	public static Status updatedSoHNominal(double deltaT, double current, int temperature, boolean isStart, boolean isEnd) {
		var temperatureFactor = CapacityTemperatureFactor.factor(temperature);
		if (isStart && temperatureFactor != null) {
			usedQ += current * deltaT * temperatureFactor;
		}
    	if(isEnd) {
    		usedQ = 0.0; 
    		return new Status(isStart,isEnd, usedQ / C_NOMINAL_AS);
    	}
    	return new Status(isStart, isEnd, usedQ / C_NOMINAL_AS);
    }
	public static double updatedSoHRegular(double resistance, double rNew) {
        double currentRNew = (rNew > 0) ? rNew : 1450.0;
		if (resistance <= 0 || resistance <= currentRNew) {
			return 1.0;
		}
		double resistanceMultiplier = resistance / currentRNew;
		return clamp(Math.exp(-RESISTANCE_SOH_DECAY * (resistanceMultiplier - 1.0)), 0.0, 1.0);
    }

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
