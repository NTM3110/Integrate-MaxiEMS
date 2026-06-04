package io.openems.edge.controller.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Helper {
	public static class Result {
        public final int index;       // posterior SoC
        public final double value;        // posterior Vp // R0 passed in
        public Result(int index, double value) {
            this.index = index;
            this.value = value;
        }
    }

	private static final Logger logger = LoggerFactory.getLogger(Helper.class);
	public static double calculateStringVoltage(int cellNumber, Double[] cellVoltages) {
		double str_voltage = 0.0;
		for	(int j = 0; j < cellNumber; j++) {
			if(cellVoltages[j] == null)
				continue;
			str_voltage += cellVoltages[j];
		}
		return str_voltage;
	}
	public static double calculateStringSOC(int cellNumber, Double[] cellSocs) {
		double str_SOC = 0.0;
		int validCellCount = 0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellSocs[i] != null) {
				if (cellSocs[i] > 0) {
					str_SOC += cellSocs[i];
					validCellCount++;
				}
			}
		}
		if (validCellCount > 0) {
			str_SOC /= validCellCount;
		}
		return str_SOC;
	}
	
	public static double calculateStringSOH(int cellNumber, Double[] cellSohs) {
		double str_SOH = 0.0;
		int validCellCount = 0;
		
		for	(int j = 0; j < cellNumber; j++) {
			if(cellSohs[j] != null){
				if (cellSohs[j] > 0) {
					str_SOH += cellSohs[j];
					validCellCount++;
				}
			}	
		}
		if (validCellCount > 0) {
			str_SOH /= validCellCount;
		}
		return str_SOH;
	}
	
	public static Result getMaxVoltageBattery(int cellNumber, Double[] cellVoltages) {
		int maxVoltageIndex = -1;
		double maxVoltage = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellVoltages[i] != null){
				if(cellVoltages[i] > maxVoltage) {
					maxVoltageIndex = i+1;
					maxVoltage = cellVoltages[i];
				}
			}
		}
		return new Result(maxVoltageIndex, maxVoltage);
	}
	
	public static Result getMinVoltageBattery(int cellNumber, Double[] cellVoltages) {
		int minVoltageIndex = -1;
		double minVoltage = 1000.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellVoltages[i] == null) {
				continue;
			}
			if(cellVoltages[i] <= 0)
				continue;
			if(cellVoltages[i] < minVoltage) {
				minVoltageIndex = i+1;
				minVoltage = cellVoltages[i];
			}
		}
		return new Result(minVoltageIndex, minVoltage);
	}
	public static double getAverageVoltageBattery(int cellNumber, Double[] cellVoltages) {
		double averageVoltage = 0.0;
		int validCellCount = 0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellVoltages[i] == null) {
				continue;
			}

			if (cellVoltages[i] <= 0) {
				continue;
			}
			averageVoltage += cellVoltages[i];
			validCellCount++;
		}
		averageVoltage /= validCellCount;
		if(validCellCount == 0) {
			// logger.warn("No valid cell count in getAverageResis")
			return 0;
		}
		return averageVoltage;
	}
	
	public static Result getMaxResistanceBattery(int cellNumber, Double[] cellInternalResistances) {
		int maxResistanceIndex = -1;
		double maxResistance = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellInternalResistances[i] == null) {
				continue;
			}

			if(cellInternalResistances[i] > maxResistance) {
				maxResistanceIndex = i+1;
				maxResistance = cellInternalResistances[i];
			}
		}
		return new Result(maxResistanceIndex, maxResistance);
	}
	
	public static double getAverageResistanceBattery(int cellNumber, Double[] cellInternalResistances) {
		double averageResistance = 0.0;
		int validCellCount = 0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellInternalResistances[i] == null) {
				continue;
			}

			if (cellInternalResistances[i] <= 0) {
				continue;
			}
			averageResistance += cellInternalResistances[i];
			validCellCount++;
		}
		averageResistance /= validCellCount;
		if(validCellCount == 0) {
			// logger.warn("No valid cell count in getAverageResis")
			return 0;
		}
		return averageResistance;
	}
	
	public static Result getMinResistanceBattery(int cellNumber, Double[] cellInternalResistances) {
		int minResistanceIndex = -1;
		double minResistance = 10000.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellInternalResistances[i] == null) {
				continue;
			}
			if(cellInternalResistances[i] < minResistance) {
				minResistanceIndex = i+1;
				minResistance = cellInternalResistances[i];
			}
		}
		return new Result(minResistanceIndex, minResistance);
	}
	
	public static Result getMaxTemperatureBattery(int cellNumber, Double[] cellTemperatures) {
		int maxTempIndex = -1;
		double maxTemp = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellTemperatures[i] == null) {
				continue;
			}
			if(cellTemperatures[i] > maxTemp) {
				maxTempIndex = i+1;
				maxTemp = cellTemperatures[i];
			}
		}
		return new Result(maxTempIndex, maxTemp);
	}
	
	public static double getAverageTemperatureBattery(int cellNumber, Double[] cellTemperatures) {
		double averageTemp = 0.0;
		int validCellCount = 0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellTemperatures[i] == null) {
				continue;
			}
			if (cellTemperatures[i] <= 0) {
				continue;
			}
			averageTemp += cellTemperatures[i];
			validCellCount++;
		}
		averageTemp /= validCellCount;
		return averageTemp;
	}
	
	public static Result getMinTemperatureBattery(int cellNumber, Double[] cellTemperatures) {
		int minTempIndex = -1;
		double minTemp = 1000.0;
		for(int i = 0; i < cellNumber; i++) {
			if(cellTemperatures[i] == null) {
				continue;
			}
			if(cellTemperatures[i] < minTemp) {
				minTempIndex = i+1;
				minTemp = cellTemperatures[i];
			}
		}
		return new Result(minTempIndex, minTemp);
	}
}
