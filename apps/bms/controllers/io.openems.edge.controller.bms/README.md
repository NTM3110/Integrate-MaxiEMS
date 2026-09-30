# BMS Modbus/TCP Server

`Controller.Bms.ModbusTcp` exports only FC4 input registers from immutable
`BmsStringSnapshot` generations. It has no serial/Modbus client behavior and
does not create BMS components. Configure its `port` (default `1502`) and
`unitId` through ConfigAdmin.

Workbook addresses are 1-based; Modbus request offsets are `address - 1`.
Each string uses a 10,000-register offset. Cell ranges are 1001-1110
(resistance), 1301-1410 (voltage), 1601-1710 (temperature), 1901-2010 (SoC),
and 2201-2310 (SoH). Aggregate registers are 3001-5001.

All missing, null, non-finite, or values whose encoded word would be `0xFFFF`
are the raw word `0xFFFF`; clients must check it before signed conversion or scaling. Voltage is
millivolts, temperature is tenths of a degree, aggregate SoC/SoH are tenths of
a percent, and cell SoC/SoH are whole percent. Resistance and cell IDs are
integers. Current at 3001 is signed tenths of an amp (two's-complement after
checking the sentinel). Register 5001 is `1` for any cell availability warning
or a string/TC warning, otherwise `0`.
