# How @Bridge Listens for OSGi Cycle Events

## Overview

The `@Bridge` components (like `AbstractModbusBridge` and `AbstractDlmsBridge`) listen for two critical OSGi cycle events to coordinate the timing of read and write operations:

1. **`TOPIC_CYCLE_EXECUTE_WRITE`** → Triggers write tasks ASAP
2. **`TOPIC_CYCLE_BEFORE_PROCESS_IMAGE`** → Triggers read tasks to be fresh

---

## Event Flow in the Cycle

The cycle events are triggered sequentially by the `CycleWorker` in this order:

```
1. TOPIC_CYCLE_BEFORE_PROCESS_IMAGE
   ↓
2. [Process Image Update - channels switch to next values]
   ↓
3. TOPIC_CYCLE_AFTER_PROCESS_IMAGE
   ↓
4. TOPIC_CYCLE_BEFORE_CONTROLLERS
   ↓
5. [Controllers execute their logic]
   ↓
6. TOPIC_CYCLE_AFTER_CONTROLLERS
   ↓
7. TOPIC_CYCLE_BEFORE_WRITE
   ↓
8. TOPIC_CYCLE_EXECUTE_WRITE  ← WRITE TASKS TRIGGERED HERE
   ↓
9. TOPIC_CYCLE_AFTER_WRITE
```

---

## How @Bridge Implements Event Listening

### 1. **Event Handler Registration**

The bridge implements `EventHandler` interface:

```java
public abstract class AbstractModbusBridge extends AbstractOpenemsComponent
    implements BridgeModbus, EventHandler, StartStoppable {
    
    @Override
    public void handleEvent(Event event) {
        if (this.config == null || !this.isEnabled()) {
            return;
        }
        switch (event.getTopic()) {
        case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE 
            -> this.worker.onBeforeProcessImage();

        case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE 
            -> this.worker.onExecuteWrite();
        }
    }
}
```

The bridge is automatically registered as an OSGi `EventHandler` through the component annotations, allowing it to receive events from the OSGi Event Admin service.

---

## 2. **TOPIC_CYCLE_EXECUTE_WRITE** - Write Tasks

### Purpose
Execute all write operations **as early as possible** in the cycle to ensure commands are sent to devices immediately.

### Flow

```
TOPIC_CYCLE_EXECUTE_WRITE event
    ↓
AbstractModbusBridge.handleEvent()
    ↓
ModbusWorker.onExecuteWrite()
    ↓
CycleTasksManager.onExecuteWrite()
    ↓
[Write tasks are prioritized and executed]
    ↓
Device receives write commands
```

### Why Early?
- **Immediate Response**: Commands are sent to devices right away
- **Predictable Timing**: Write operations happen at a consistent point in the cycle
- **Avoid Conflicts**: Ensures writes don't interfere with ongoing reads

### Example: Writing a Relay State
```java
case TOPIC_CYCLE_EXECUTE_WRITE -> {
    this.executeWrite(this.getRelayChannel(), 0);
}
```

---

## 3. **TOPIC_CYCLE_BEFORE_PROCESS_IMAGE** - Read Tasks

### Purpose
Execute all read operations **as late as possible** to ensure fresh data is available exactly when needed (before the process image is updated).

### Flow

```
TOPIC_CYCLE_BEFORE_PROCESS_IMAGE event
    ↓
AbstractModbusBridge.handleEvent()
    ↓
ModbusWorker.onBeforeProcessImage()
    ↓
CycleTasksManager.onBeforeProcessImage()
    ↓
[Read tasks are executed]
    ↓
Fresh data is available
    ↓
Process image is updated with new values
    ↓
Controllers use the fresh data
```

### Why Late?
- **Fresh Data**: Ensures the most recent values from devices are available
- **Minimal Staleness**: Reduces the time between reading and using the data
- **Synchronized**: Data is guaranteed to be fresh when controllers execute

### Example: Reading Meter Values
```java
case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> {
    // Read current power, voltage, etc. from meter
    this.readMeterData();
}
```

---

## 4. **Task Scheduling Strategy**

The `ModbusWorker` uses a sophisticated scheduling strategy:

```java
/**
 * The ModbusWorker schedules the execution of all Modbus-Tasks, like reading
 * and writing modbus registers.
 *
 * It tries to execute all Write-Tasks as early as possible (directly after the
 * TOPIC_CYCLE_EXECUTE_WRITE event) and all Read-Tasks as late as possible to
 * have values available exactly when they are needed (i.e. at the
 * TOPIC_CYCLE_BEFORE_PROCESS_IMAGE event).
 */
public class ModbusWorker extends AbstractImmediateWorker {
    
    public void onExecuteWrite() {
        this.cycleTasksManager.onExecuteWrite();
    }

    public void onBeforeProcessImage() {
        this.cycleTasksManager.onBeforeProcessImage();
    }
}
```

---

## 5. **Task Execution in the Worker Thread**

The bridge uses a dedicated worker thread (`AbstractImmediateWorker`) that continuously executes tasks:

```java
@Override
protected void forever() throws InterruptedException {
    var task = this.cycleTasksManager.getNextTask();

    // execute the task
    var result = this.execute.apply(task);

    switch (result) {
    case ExecuteState.Ok es ->
        // Task executed successfully
        this.markComponentAsDefective(task.getParent(), false);
    case ExecuteState.Error es -> {
        // Task failed
        this.markComponentAsDefective(task.getParent(), true);
        this.invalidate.accept(task.getElements());
    }
    }
}
```

---

## 6. **Complete Cycle Timeline**

```
┌─────────────────────────────────────────────────────────────────┐
│                    OPENEMS CYCLE (e.g., 5000ms)                 │
└─────────────────────────────────────────────────────────────────┘

1. BEFORE_PROCESS_IMAGE
   └─→ @Bridge: onBeforeProcessImage()
       └─→ Execute READ tasks
           └─→ Fetch fresh data from devices
               └─→ Meter readings, sensor values, etc.

2. Process Image Update
   └─→ All channels switch to next values
   └─→ Controllers see the fresh data

3. BEFORE_CONTROLLERS
   └─→ Controllers prepare

4. Execute Controllers
   └─→ Controllers use fresh data to make decisions
   └─→ Controllers set output channels

5. AFTER_CONTROLLERS
   └─→ Controllers finished

6. BEFORE_WRITE
   └─→ Prepare for writes

7. EXECUTE_WRITE
   └─→ @Bridge: onExecuteWrite()
       └─→ Execute WRITE tasks
           └─→ Send commands to devices
               └─→ Relay states, power setpoints, etc.

8. AFTER_WRITE
   └─→ Cycle complete, wait for next cycle
```

---

## 7. **Practical Example: Modbus Bridge**

### Scenario: Reading Power Meter and Controlling a Relay

```
Cycle Start (t=0ms)
│
├─ BEFORE_PROCESS_IMAGE (t=100ms)
│  └─ Read power meter via Modbus
│     └─ Get: Active Power = 5000W, Voltage = 230V
│
├─ Process Image Update
│  └─ Channel values updated
│
├─ BEFORE_CONTROLLERS
│
├─ Execute Controllers
│  └─ Controller reads: Power = 5000W
│  └─ Decision: "Power is too high, turn off relay"
│  └─ Sets: RelayChannel = OFF
│
├─ AFTER_CONTROLLERS
│
├─ BEFORE_WRITE
│
├─ EXECUTE_WRITE (t=4900ms)
│  └─ Write relay state via Modbus
│     └─ Send: "Turn relay OFF"
│
└─ AFTER_WRITE
   └─ Cycle complete, wait ~100ms for next cycle
```

---

## 8. **Error Handling**

If a read or write fails, the bridge marks the component as defective:

```java
private void markComponentAsDefective(ModbusComponent component, boolean isDefective) {
    if (component != null) {
        if (isDefective) {
            // Component is defective
            this.defectiveComponents.add(component.id());
            component._setModbusCommunicationFailed(true);
        } else {
            // Read from/Write to Component was successful
            this.defectiveComponents.remove(component.id());
            component._setModbusCommunicationFailed(false);
        }
    }
}
```

Defective components are retried with increasing delays to avoid overwhelming the network.

---

## 9. **Key Benefits of This Architecture**

| Aspect | Benefit |
|--------|---------|
| **Timing Predictability** | Reads and writes happen at consistent points in the cycle |
| **Data Freshness** | Controllers always work with the latest data |
| **Responsiveness** | Commands are sent to devices immediately after decisions |
| **Scalability** | Multiple bridges can coexist without conflicts |
| **Fault Tolerance** | Failed components are tracked and retried intelligently |
| **Performance** | Dedicated worker threads prevent blocking the main cycle |

---

## 10. **DLMS Bridge Implementation**

The DLMS Bridge follows a similar pattern but with a simpler implementation:

```java
public class DlmsWorker extends AbstractImmediateWorker {
    
    @Override
    protected void forever() throws InterruptedException {
        DlmsTask task = this.taskManager.getOneTask();
        if (task == null) {
            Thread.sleep(100);
            return;
        }

        ExecuteState result = this.execute.apply(task);
        if (result instanceof ExecuteState.Ok) {
            this.markComponentAsDefective(task.getParent(), false);
        } else if (result instanceof ExecuteState.Error) {
            this.markComponentAsDefective(task.getParent(), true);
        }
    }
}
```

The DLMS Bridge doesn't explicitly listen for cycle events in the same way as Modbus, but it uses the same worker pattern to execute tasks continuously.

---

## Summary

The `@Bridge` components use OSGi event listening to coordinate I/O operations with the OpenEMS cycle:

- **`TOPIC_CYCLE_EXECUTE_WRITE`**: Triggers write tasks immediately to send commands to devices
- **`TOPIC_CYCLE_BEFORE_PROCESS_IMAGE`**: Triggers read tasks to fetch fresh data before controllers execute

This ensures:
1. ✅ Predictable timing
2. ✅ Fresh data for controllers
3. ✅ Immediate command execution
4. ✅ Synchronized operation across all components
