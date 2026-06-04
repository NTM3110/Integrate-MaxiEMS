package io.openems.edge.meter.edmi;

public final class EdmiRegisters {
    public static final String PHASE_A_VOLTAGE = "0xE000";
    public static final String PHASE_B_VOLTAGE = "0xE001";
    public static final String PHASE_C_VOLTAGE = "0xE002";

    public static final String PHASE_A_CURRENT = "0xE010";
    public static final String PHASE_B_CURRENT = "0xE011";
    public static final String PHASE_C_CURRENT = "0xE012";

    public static final String PHASE_A_WATTS = "0xE030";
    public static final String PHASE_B_WATTS = "0xE031";
    public static final String PHASE_C_WATTS = "0xE032";
    public static final String P_TOTAL = "0xE033";

    public static final String PHASE_A_VARS = "0xE040";
    public static final String PHASE_B_VARS = "0xE041";
    public static final String PHASE_C_VARS = "0xE042";
    public static final String Q_TOTAL = "0xE043";

    public static final String FREQUENCY = "0xE060";

    public static final String TOTAL_IMPORT_KWH = "0x0069";
    public static final String TOTAL_EXPORT_KWH = "0x0169";

    private EdmiRegisters() {
    }
}
