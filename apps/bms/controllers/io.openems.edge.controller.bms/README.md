# BMS Modbus Server

`Controller.Bms.ModbusTcp` exports contract v3 as FC4 input registers from one
immutable `BmsMonitoringSnapshot` per request. It does not expose per-cell
registers, poll TA/TC, or create BMS components. `transport` selects `TCP` or
`SERIAL_RTU`; missing transport remains TCP for compatibility and the persisted
TCP properties are `bindAddress` (IP literal, default `0.0.0.0`) and compatibility
property `port` (default `1502`). Serial device settings are copied
server-side from managed Port 1/2/3 after the bridge is safely reserved.

Workbook addresses are one-based and the Modbus protocol offset is
`workbookAddress - 1`. For string ID `n`, `base = n * 1000`; therefore String 1
uses `1001..1036`, String 2 uses `2001..2036`, and String 10 uses
`10001..10036`. Only those 36 addresses are mapped.

Relative addresses `1..34` contain 17 signed 32-bit measurements in network
word order. Relative address `35` is the unsigned 16-bit Alarm Active Mask and
relative address `36` is the unsigned 16-bit Alarm Validity Mask. Bits `0..11`
follow `BmsMonitoringSnapshot.Alarm` order; bits `12..15` are zero. A validity
bit is one only when the alarm evaluation is available, and an active bit is one
only when that alarm is both available and active. A missing frame returns zero
for both masks. Measurement unavailable remains `0x80000000`. The canonical
address, alarm bit, label, unit, scale, and type catalog is
`BmsMonitoringModbusMap`.
