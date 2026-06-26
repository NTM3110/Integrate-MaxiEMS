# IEC 104 Master/Client Receive and Command Response Flow

## Overview

This document describes how the OpenEMS IEC 104 driver (acting as a master/client) receives data from the server/slave and how command responses are handled.

## Table of Contents

1. [Connection Setup](#connection-setup)
2. [Data Receive Path](#data-receive-path)
3. [Command Send Flow](#command-send-flow)
4. [Command Response Handling](#command-response-handling)
5. [Current Limitations](#current-limitations)
6. [Future Improvements](#future-improvements)

## Connection Setup

The driver establishes a connection using the j60870 library's `ClientConnectionBuilder`:

```java
ClientConnectionBuilder builder = new ClientConnectionBuilder(address)
    .setPort(this.config.port())
    .setConnectionTimeout(this.config.connectionTimeout())
    .setConnectionEventListener(this);  // <-- This registers the callback

this.connection = builder.build();
this.connection.startDataTransfer();
```

The `ConnectionEventListener` interface provides three callbacks:
- `newASdu()` - Called when an ASDU is received from the server
- `connectionClosed()` - Called when the connection is closed
- `dataTransferStateChanged()` - Called when data transfer starts/stops

## Data Receive Path

When the server sends data, the following chain occurs:

```
IEC104 Server/Slave sends ASDU
    ↓
j60870 ConnectionEventListener.newASdu(connection, aSdu)
    ↓
Iec104BridgeImpl.newASdu(aSdu)  [Line 133-142]
    ↓
AbstractIec104Bridge.handleASdu(aSdu)  [Line 66-72]
    ↓
Iec104Protocol.handleAsdu(aSdu)  [Line 60-83]
    ↓
parseAndSetValue(element, ie, ioa, asduType)  [Line 97-184]
    ↓
Iec104Element.setValue(value)
    ↓
Channel.setNextValue(value)  [via onUpdateCallback]
```

### Key Code: Iec104BridgeImpl.newASdu()

```java
@Override
public void newASdu(Connection connection, ASdu aSdu) {
    // Increment ASDU counter
    this.asduReceivedCount.incrementAndGet();

    // Log incoming data
    this.logInfo(this.log, "Received ASDU [" + aSdu.getTypeIdentification() + "] COT="
            + aSdu.getCauseOfTransmission() + " #IOA=" + aSdu.getInformationObjects().length);

    this.handleASdu(aSdu);
}
```

### Key Code: AbstractIec104Bridge.handleASdu()

```java
protected void handleASdu(ASdu aSdu) {
    var commonAddress = aSdu.getCommonAddress();
    this.protocols.values().stream()
        .filter(registration -> registration.commonAddress() == commonAddress)
        .map(ProtocolRegistration::protocol)
        .forEach(protocol -> protocol.handleAsdu(aSdu));
}
```

### Key Code: Iec104Protocol.handleAsdu()

```java
public void handleAsdu(ASdu aSdu) {
    ASduType asduType = aSdu.getTypeIdentification();
    for (InformationObject io : aSdu.getInformationObjects()) {
        int ioa = io.getInformationObjectAddress();
        Iec104Element<?> element = this.elements.get(ioa);

        if (element == null) {
            continue;  // No element mapped for this IOA
        }

        // Type ID must match what the element expects
        if (element.getType() != asduType) {
            LOG.debug("ASduType mismatch for IOA [{}]: expected [{}], got [{}]", 
                ioa, element.getType(), asduType);
            continue;
        }

        InformationElement[][] ies = io.getInformationElements();
        if (ies != null && ies.length > 0 && ies[0].length > 0) {
            InformationElement ie = ies[0][0];
            this.parseAndSetValue(element, ie, ioa, asduType);
        }
    }
}
```

### Supported ASDU Types for Data Reception

| ASDU Type | Description | Parsed Value Type |
|-----------|-------------|-------------------|
| M_SP_NA_1, M_SP_TA_1, M_SP_TB_1, M_PS_NA_1 | Single point information | Boolean |
| M_DP_NA_1, M_DP_TA_1, M_DP_TB_1 | Double point information | Integer (ordinal) |
| M_ST_NA_1, M_ST_TA_1, M_ST_TB_1 | Step position information | Integer |
| M_ME_NA_1, M_ME_TA_1, M_ME_TD_1, M_ME_ND_1 | Normalized value | Float |
| M_ME_NB_1, M_ME_TB_1, M_ME_TE_1 | Scaled value | Integer |
| M_ME_NC_1, M_ME_TC_1 | Short float | Float |
| M_BO_NA_1, M_BO_TA_1, M_BO_TB_1 | Bitstring (32 bits) | Integer |
| M_IT_NA_1, M_IT_TA_1, M_IT_TB_1 | Integrated totals (counter) | Long |

## Command Send Flow

Commands are sent via JSON-RPC API:

```
Postman/Client sends JSON-RPC request
    ↓
ComponentJsonApi routes to sendIec104Command handler
    ↓
AbstractOpenemsIec104Component.buildJsonApiRoutes()  [Line 98-115]
    ↓
sendIec104Command(channelId, value, select)  [Line 117-137]
    ↓
Iec104Protocol.createWriteCommand(commonAddress, ioa, value, select)  [Line 219-308]
    ↓
bridge.sendCommand(ASdu)  [Line 136]
    ↓
Iec104BridgeImpl.sendCommand(ASdu)  [Line 167-176]
    ↓
connection.send(ASdu)  [Line 172]
    ↓
IEC104 Server/Slave receives command
```

### Key Code: sendIec104Command()

```java
private void sendIec104Command(String channelId, JsonElement value, boolean select) throws Exception {
    var element = this.elementsByChannelId.get(channelId);
    if (element == null) {
        throw new OpenemsException("No IEC104 element mapped for channel [" + channelId + "]");
    }

    Channel<?> channel = this.channel(channelId);
    Object typedValue = JsonUtils.getAsType(channel.getType(), value);
    var command = this.getIec104Protocol().createWriteCommand(
        this.getCommonAddress(), element.getIoa(), typedValue, select);
    
    if (command == null) {
        throw new OpenemsException("Unsupported IEC104 command for channel [" + channelId + "]");
    }

    var bridge = this.iec104Bridge.get();
    if (bridge == null) {
        throw new OpenemsException("IEC104 Bridge is not connected");
    }

    bridge.sendCommand(command);
}
```

### Command ASDU Types

| Command Type | ASDU Type | Type ID | Description |
|-------------|-----------|---------|-------------|
| Single command | C_SC_NA_1 | 45 | Boolean on/off |
| Double command | C_DC_NA_1 | 46 | Double point state |
| Regulating step | C_RC_NA_1 | 47 | Step up/down |
| Set point normalized | C_SE_NA_1 | 48 | Normalized value |
| Set point scaled | C_SE_NB_1 | 49 | Scaled value |
| Set point short float | C_SE_NC_1 | 50 | Float value |
| Bitstring command | C_BO_NA_1 | 51 | 32-bit state |

### Select vs Execute

The `select` parameter controls the qualifier:
- `select = true` → Select qualifier (prepares the command)
- `select = false` → Execute qualifier (executes immediately)

```java
// Example: Single command with select
new IeSingleCommand((Boolean) value, 0, select)

// Example: Set point command with select
new IeQualifierOfSetPointCommand(0, select)
```

## Command Response Handling

### Expected Protocol Flow

Per IEC 104 protocol, after receiving a command with `CauseOfTransmission.ACTIVATION`, the server should respond with:

1. **ActCon** (Activation Confirmation)
   - COT = `ACTIVATION_CONFIRMATION`
   - Meaning: "I received and accepted your command"

2. **ActTerm** (Activation Termination)
   - COT = `ACTIVATION_TERMINATION`
   - Meaning: "Command execution is complete"

### Response Type Mapping

| Command Sent | Response Type | COT Values |
|-------------|---------------|-----------|
| C_SC_NA_1 (45) | C_SC_NA_1 (45) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |
| C_DC_NA_1 (46) | C_DC_NA_1 (46) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |
| C_RC_NA_1 (47) | C_RC_NA_1 (47) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |
| C_SE_NA_1 (48) | C_SE_NA_1 (48) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |
| C_SE_NB_1 (49) | C_SE_NB_1 (49) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |
| C_SE_NC_1 (50) | C_SE_NC_1 (50) | ACTIVATION_CONFIRMATION, ACTIVATION_TERMINATION |

### Current Response Path

Command responses arrive through the same callback:

```
Server sends ActCon/ActTerm
    ↓
Iec104BridgeImpl.newASdu()  [logs it]
    ↓
AbstractIec104Bridge.handleASdu()
    ↓
Iec104Protocol.handleAsdu()
    ↓
Type check: element.getType() != asduType  ← MISMATCH!
    ↓
Response is silently dropped
```

## Current Limitations

### 1. Command Responses Are Ignored

The `Iec104Protocol.handleAsdu()` method checks if the ASDU type matches the element's expected type:

```java
if (element.getType() != asduType) {
    LOG.debug("ASduType mismatch for IOA [{}]: expected [{}], got [{}]", 
        ioa, element.getType(), asduType);
    continue;
}
```

**Problem**: Command responses use command type IDs (e.g., `C_SC_NA_1`), but mapped elements expect measurement type IDs (e.g., `M_SP_NA_1`). This causes the response to be dropped.

### 2. No Command Tracking

The current implementation:
- ✅ Sends commands correctly
- ✅ Logs received ASDUs
- ❌ Does not track pending commands
- ❌ Does not match responses to requests
- ❌ Does not confirm command success/failure

### 3. JSON-RPC Returns Immediately

The JSON-RPC handler returns a success response immediately after sending:

```java
var result = JsonUtils.buildJsonObject()
    .addProperty("componentId", this.id())
    .addProperty("channelId", channelId)
    .addProperty("select", select)
    .build();
return new GenericJsonrpcResponseSuccess(request.getId(), result);
```

This response does **not** indicate whether the server accepted or executed the command.

### 4. No Timeout Handling

If the server never responds, the client has no way to detect the missing response.

## Future Improvements

### 1. Add Command Tracking

Add a pending command tracker to the bridge:

```java
// In AbstractIec104Bridge or Iec104Protocol
private final Map<Integer, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

record PendingCommand(
    int ioa, 
    long sentTime, 
    CompletableFuture<CommandResult> future
) {}
```

### 2. Track Before Sending

Register the command before sending:

```java
public void sendCommand(ASdu command) {
    for (InformationObject io : command.getInformationObjects()) {
        int ioa = io.getInformationObjectAddress();
        var future = new CompletableFuture<CommandResult>();
        pendingCommands.put(ioa, 
            new PendingCommand(ioa, System.currentTimeMillis(), future));
    }
    connection.send(command);
}
```

### 3. Handle Command Responses

In `Iec104Protocol.handleAsdu()`, check for command responses:

```java
public void handleAsdu(ASdu aSdu) {
    var cot = aSdu.getCauseOfTransmission();
    
    // Check if this is a command response
    if (cot == CauseOfTransmission.ACTIVATION_CONFIRMATION || 
        cot == CauseOfTransmission.ACTIVATION_TERMINATION) {
        
        for (InformationObject io : aSdu.getInformationObjects()) {
            int ioa = io.getInformationObjectAddress();
            var pending = pendingCommands.remove(ioa);
            if (pending != null) {
                boolean completed = (cot == CauseOfTransmission.ACTIVATION_TERMINATION);
                pending.future().complete(new CommandResult(completed));
            }
        }
        return;
    }
    
    // Otherwise, process as measurement data...
}
```

### 4. Make sendIec104Command Async

Wait for command completion with timeout:

```java
private void sendIec104Command(String channelId, JsonElement value, boolean select) 
        throws Exception {
    // ... existing code ...
    
    bridge.sendCommand(command);
    
    // Wait for response with timeout
    var pending = pendingCommands.get(element.getIoa());
    if (pending != null) {
        try {
            var result = pending.future().get(5, TimeUnit.SECONDS);
            if (!result.success()) {
                throw new OpenemsException("Command execution failed");
            }
        } catch (TimeoutException e) {
            throw new OpenemsException("Command response timeout");
        }
    }
}
```

### 5. Update JSON-RPC Response

Include command execution status:

```java
var result = JsonUtils.buildJsonObject()
    .addProperty("componentId", this.id())
    .addProperty("channelId", channelId)
    .addProperty("select", select)
    .addProperty("executed", true)  // <-- Add execution status
    .build();
return new GenericJsonrpcResponseSuccess(request.getId(), result);
```

## File References

- `Iec104BridgeImpl.java` - Connection management and ASDU reception
- `AbstractIec104Bridge.java` - Protocol routing and bridge abstraction
- `Iec104Protocol.java` - ASDU parsing and command creation
- `AbstractOpenemsIec104Component.java` - Component-level command API
- `Iec104Worker.java` - Cyclic general interrogation

## Related Files

| File | Path | Purpose |
|------|------|---------|
| Iec104BridgeImpl.java | `io.openems.edge.bridge.iec104/src/io/openems/edge/bridge/iec104/Iec104BridgeImpl.java` | Main bridge implementation |
| AbstractIec104Bridge.java | `io.openems.edge.bridge.iec104/src/io/openems/edge/bridge/iec104/api/AbstractIec104Bridge.java` | Abstract bridge base |
| Iec104Protocol.java | `io.openems.edge.bridge.iec104/src/io/openems/edge/bridge/iec104/api/Iec104Protocol.java` | Protocol handling |
| AbstractOpenemsIec104Component.java | `io.openems.edge.bridge.iec104/src/io/openems/edge/bridge/iec104/api/AbstractOpenemsIec104Component.java` | Component base with JSON-RPC |
| Iec104Worker.java | `io.openems.edge.bridge.iec104/src/io/openems/edge/bridge/iec104/api/worker/Iec104Worker.java` | Background worker |
