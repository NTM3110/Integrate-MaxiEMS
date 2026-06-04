# Clarification: What is a "Channel" in OpenEMS?

## Short Answer

**NO** - The channel in the constructor is **NOT** the entire Modbus channel. 

There are **two different concepts** with similar names:

1. **OpenEMS Channel** - A single piece of data (e.g., "Active Power", "Voltage")
2. **Modbus Register/Element** - The physical Modbus address where data is stored

---

## The Confusion

When you see code like this:

```java
m(ElectricityMeter.ChannelId.ACTIVE_POWER, 
  new FloatDoublewordElement(30013 - offset),
  INVERT_IF_TRUE(invert))
```

This is **mapping**:
- **Left side**: OpenEMS Channel (`ACTIVE_POWER`)
- **Right side**: Modbus Element (`FloatDoublewordElement` at address 30013)

---

## What is an OpenEMS Channel?

An OpenEMS **Channel** is a **single data point** that represents:

```
┌─────────────────────────────────────────┐
│         OpenEMS Channel                 │
├─────────────────────────────────────────┤
│ • Channel ID: "ACTIVE_POWER"            │
│ • Type: LONG (integer)                  │
│ • Unit: Watts (W)                       │
│ • Current Value: 5000                   │
│ • Min/Max: -10000 / +10000              │
│ • Access: READ_ONLY                     │
│ • Persistence: HIGH                     │
└─────────────────────────────────────────┘
```

### Examples of Channels

| Channel ID | Type | Unit | Purpose |
|-----------|------|------|---------|
| `ACTIVE_POWER` | LONG | W | Current active power |
| `VOLTAGE` | LONG | V | Current voltage |
| `CURRENT` | LONG | A | Current current |
| `FREQUENCY` | LONG | Hz | Grid frequency |
| `CYCLE_TIME_IS_TOO_SHORT` | BOOLEAN | - | Is cycle too short? |
| `BRIDGE_IS_STOPPED` | BOOLEAN | - | Is bridge stopped? |

---

## What is a Modbus Element?

A Modbus **Element** is a **physical register** in the Modbus device:

```
┌─────────────────────────────────────────┐
│      Modbus Element (Register)          │
├──────────────��──────────────────────────┤
│ • Type: FloatDoublewordElement          │
│ • Start Address: 30013                  │
│ • Length: 2 registers (32-bit float)    │
│ • Byte Order: BIG_ENDIAN                │
│ • Word Order: MSWLSW                    │
│ • Raw Value: 0x44160000 (5000.0 in IEEE 754) │
└─────────────────────────────────────────┘
```

### Types of Modbus Elements

| Element Type | Size | Purpose |
|-------------|------|---------|
| `UnsignedWordElement` | 1 register (16-bit) | Unsigned integer |
| `SignedWordElement` | 1 register (16-bit) | Signed integer |
| `FloatDoublewordElement` | 2 registers (32-bit) | IEEE 754 float |
| `SignedDoublewordElement` | 2 registers (32-bit) | Signed 32-bit int |
| `CoilElement` | 1 coil | Boolean (on/off) |
| `DiscreteInputElement` | 1 input | Boolean (read-only) |

---

## The Mapping Process

Here's how they work together:

```
┌──────────────────────────────────────────────────────────────┐
│                    Meter Device (Modbus)                     │
├──────��───────────────────────────────────────────────────────┤
│ Register 30013-30014: 0x44160000 (5000.0 W)                  │
│ Register 30001-30002: 0x43750000 (245.0 V)                   │
│ Register 30007-30008: 0x41200000 (10.0 A)                    │
└──────────────────────────────────────────────────────────────┘
                            ↓
                    [Modbus Read Task]
                            ↓
┌──────────────────────────────────────────────────────────────┐
│              Modbus Elements (Raw Data)                       │
├──────────────────────────────────────────────────────────────┤
│ FloatDoublewordElement(30013): 5000.0                        │
│ FloatDoublewordElement(30001): 245.0                         │
│ FloatDoublewordElement(30007): 10.0                          │
└────────���─────────────────────────────────────────────────────┘
                            ↓
                    [Conversion/Mapping]
                            ↓
┌──────────────────────────────────────────────────────────────┐
│           OpenEMS Channels (Typed Data)                       │
├──────────────────────────────────────────────────────────────┤
│ ACTIVE_POWER Channel: 5000 W                                 │
│ VOLTAGE Channel: 245 V                                       │
│ CURRENT Channel: 10 A                                        │
└──────────────────────────────────────────────────────────────┘
                            ↓
                    [Available to Controllers]
                            ↓
┌─────────────────────────────────────────���────────────────────┐
│              Controllers Read Channels                        │
├──────────────────────────────────────────────────────────────┤
│ if (activePower > 5000) { ... }                              │
│ if (voltage < 200) { ... }                                   │
└──────────────────────────────────────────────────────────────┘
```

---

## Real Example: Eastron Meter

```java
// From MeterEastronSdm630Impl.java

m(ElectricityMeter.ChannelId.VOLTAGE, 
  new FloatDoublewordElement(30001 - offset)
    .wordOrder(WordOrder.MSWLSW)
    .byteOrder(ByteOrder.BIG_ENDIAN),
  SCALE_FACTOR_3)
```

Breaking this down:

| Part | Meaning |
|------|---------|
| `ElectricityMeter.ChannelId.VOLTAGE` | **OpenEMS Channel**: The data point representing voltage |
| `new FloatDoublewordElement(30001)` | **Modbus Element**: Read from register 30001-30002 as a 32-bit float |
| `.wordOrder(WordOrder.MSWLSW)` | **Byte order**: Most Significant Word first, Least Significant Word second |
| `.byteOrder(ByteOrder.BIG_ENDIAN)` | **Byte order**: Big-endian format |
| `SCALE_FACTOR_3` | **Converter**: Divide by 1000 (scale factor 3 = 10^-3) |

**Result**: 
- Read raw value from Modbus register 30001-30002
- Convert from IEEE 754 float
- Apply scale factor (divide by 1000)
- Store in `VOLTAGE` channel
- Controllers can now read the voltage value

---

## Constructor Channels vs. Modbus Channels

### Constructor Channels (What We Discussed Earlier)

```java
public BridgeModbusSerialImpl() {
    super(
        OpenemsComponent.ChannelId.values(),      // ~3 channels
        BridgeModbus.ChannelId.values(),          // ~3 channels
        BridgeModbusSerial.ChannelId.values(),    // ~5 channels
        StartStoppable.ChannelId.values()         // ~2 channels
    );
}
```

These are **bridge management channels** like:
- `CYCLE_TIME_IS_TOO_SHORT`
- `BRIDGE_IS_STOPPED`
- `PORT_NAME`
- `BAUDRATE`

### Modbus Channels (What Devices Expose)

```java
// In a meter component
m(ElectricityMeter.ChannelId.ACTIVE_POWER, 
  new FloatDoublewordElement(30013))
```

These are **device data channels** like:
- `ACTIVE_POWER`
- `VOLTAGE`
- `CURRENT`
- `FREQUENCY`

---

## Summary

| Aspect | OpenEMS Channel | Modbus Element |
|--------|-----------------|-----------------|
| **What is it?** | A single data point | A physical register |
| **Example** | `ACTIVE_POWER = 5000 W` | `Register 30013 = 0x44160000` |
| **Type** | Typed (LONG, BOOLEAN, etc.) | Raw bytes |
| **Scope** | System-wide (all components) | Device-specific |
| **Access** | Via `channel(ChannelId)` | Via Modbus protocol |
| **Lifecycle** | Created in constructor | Created during mapping |
| **Purpose** | Data exchange between components | Communication with device |

---

## The Constructor is NOT About Modbus

The constructor in `BridgeModbusSerialImpl`:

```java
public BridgeModbusSerialImpl() {
    super(
        OpenemsComponent.ChannelId.values(),
        BridgeModbus.ChannelId.values(),
        BridgeModbusSerial.ChannelId.values(),
        StartStoppable.ChannelId.values()
    );
}
```

Is **NOT** about Modbus data channels. It's about:
- ✅ Bridge management channels
- ✅ Component lifecycle channels
- ✅ System monitoring channels
- ❌ NOT device data channels (those are created separately via `m()` mapping)

The Modbus data channels (like `ACTIVE_POWER`, `VOLTAGE`) are created **separately** in the component that uses the bridge (e.g., `MeterEastronSdm630Impl`), not in the bridge itself.
