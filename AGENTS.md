# OpenEMS — Agent Guide

This file is a concise, project-specific guide for AI coding agents working on this repository. It describes the architecture, build system, module layout, development conventions, and testing strategy as they actually exist in this codebase.

## 1. Project overview

This repository is a fork/customization of [OpenEMS](https://openems.io/) — the Open Source Energy Management System. OpenEMS is a modular Java/OSGi platform for monitoring, controlling, and optimizing distributed energy resources such as battery storage systems, PV inverters, EV chargers, heat pumps, and smart meters.

The three canonical OpenEMS components are:

* **OpenEMS Edge** — runs on-site, communicates with devices, executes control algorithms, and persists time-series data.
* **OpenEMS Backend** — runs in the cloud or a data center, aggregates multiple Edge instances, and provides monitoring/API access.
* **OpenEMS UI** — the Angular-based web user interface.

> **Note:** In this working tree the `ui/` directory is **not present**. The Java backend and Edge bundles are intact, but UI-specific npm/Angular work cannot be performed here.

This fork contains project-specific additions and documentation for:

* **EDMI smart-meter integration** (`io.openems.edge.bridge.edmi`, `io.openems.edge.meter.edmi`) with serial IMR-protocol communication, InfluxDB storage, and energy-separation calculations.
* **IEC 60870-5-104 (IEC 104) bridge** (`io.openems.edge.bridge.iec104`, `io.openems.edge.meter.iec104.test`) for SCADA-style communication.
* **DLMS/COSEM bridge** (`io.openems.edge.bridge.dlms`) using the Gurux library.

Relevant background reading already in the repository:

* `README.md` — high-level project introduction and links to upstream documentation.
* `EDMIDriverGuide.md` — detailed EDMI architecture, configuration, and JSON-RPC API.
* `EDMI_TESTING_GUIDE.md` / `EDMI_EnergySeparation_Testing_Guide.md` — manual testing with CSV injection and InfluxDB.
* `IEC104_COMMAND_RESPONSE_FLOW.md` — IEC 104 receive/command flow and known limitations.
* `BRIDGE_CYCLE_EVENTS_EXPLANATION.md` — how `@Bridge` components participate in the OpenEMS cycle.
* `CHANNEL_VS_MODBUS_ELEMENT_CLARIFICATION.md` — distinction between OpenEMS Channels and physical register/element mappings.
* `CONSTRUCTOR_CHANNEL_INITIALIZATION_EXPLANATION.md` — channel initialization conventions.

## 2. Technology stack

| Layer | Technology | Version / Notes |
|-------|------------|-----------------|
| Language | Java | 21 (`javac.source`/`target` in `gradle.properties`) |
| Build tool | Gradle with Bnd workspace plugin | Bnd 7.2.1 (`gradle.properties`) |
| Module system | OSGi R8 with Declarative Services (DS) | `org.osgi.service.component` annotations |
| OSGi framework | Apache Felix | 7.0.5 (see `.bndrun` files) |
| HTTP / Web Console | Apache Felix HTTP Jetty 12 + Felix Web Console | Ports configured in `.bndrun` files |
| Logging | OPS4J Pax Logging (Log4j2 backend) | `org.ops4j.pax.logging.*` |
| Dependency repository | Maven Central via `cnf/pom.xml` (BndPomRepository) | plus local `cnf/build/release` and `cnf/build/templates` |
| Time-series DB | InfluxDB (Edge + Backend), RRD4J (Edge), TimescaleDB (Backend option) | See `io.openems.*.timedata.*` bundles |
| Wrapper bundles | `io.openems.wrapper` | OSGi-wrappers for non-OSGi libraries (InfluxDB client, Retrofit, Tribuo, Gurux, j60870, etc.) |
| Testing | JUnit 4/5, Mockito, SLF4J simple, Python simulators | Mixed; see per-bundle `test/` folders |
| Documentation | Antora | `doc/build.gradle` plus `doc/site.yml` |

## 3. Repository structure

```
.
├── build.gradle                 # Root Gradle build script; defines OpenEMS-Build/OpenEMS-Test tasks
├── settings.gradle              # Includes every directory containing a bnd.bnd file
├── gradle.properties            # Java 21, Bnd 7.2.1, Gradle tuning
├── cnf/                         # Bnd workspace configuration
│   ├── build.bnd                # Workspace defaults (buildpath, testpath, repos, Java 21)
│   ├── pom.xml                  # Maven dependencies consumed by BndPomRepository
│   ├── checkstyle.xml           # Checkstyle rules (currently disabled in Gradle)
│   ├── templates/               # Bnd project templates
│   └── release/                 # Local release repo
├── io.openems.common/           # Shared utilities, JSON-RPC, WebSocket, Excel/OPC helpers, Bnd templates
├── io.openems.common.bridge.http/
├── io.openems.core.logger/      # Logging bootstrap bundle
├── io.openems.shared.influxdb/  # Shared InfluxDB connector code
├── io.openems.wrapper/          # OSGi wrapper bundles for third-party libraries
├── io.openems.oem.openems/      # OEM branding defaults
├── io.openems.edge.*            # Edge bundles (~190 bundles)
│   ├── io.openems.edge.application/      # Edge application + EdgeApp.bndrun
│   ├── io.openems.edge.common/           # Edge base classes and Channel framework
│   ├── io.openems.edge.core/             # Core Edge services (ComponentManager, Cycle, Scheduler, Sum, ...)
│   ├── io.openems.edge.bridge.{modbus,mbus,mqtt,http,onewire,dlms,edmi,iec104}/
│   ├── io.openems.edge.controller.*      # Control algorithms
│   ├── io.openems.edge.ess.*             # Energy storage systems
│   ├── io.openems.edge.battery.*         # Batteries
│   ├── io.openems.edge.batteryinverter.*
│   ├── io.openems.edge.meter.*           # Meters (including EDMI, IEC104 test, Landis DLMS)
│   ├── io.openems.edge.evcs.* / evse.*   # EV charging
│   ├── io.openems.edge.pvinverter.*      # PV inverters
│   ├── io.openems.edge.scheduler.*       # Schedulers
│   ├── io.openems.edge.timedata.*        # Time-series persistence
│   ├── io.openems.edge.timeofusetariff.*
│   ├── io.openems.edge.predictor.*
│   └── ...
├── io.openems.backend.*         # Backend bundles (~20 bundles)
│   ├── io.openems.backend.application/   # Backend application + BackendApp.bndrun
│   ├── io.openems.backend.edge.application/  # BackendEdgeApp.bndrun
│   ├── io.openems.backend.common/
│   ├── io.openems.backend.core/
│   ├── io.openems.backend.metadata.*
│   ├── io.openems.backend.timedata.*
│   ├── io.openems.backend.b2brest/
│   ├── io.openems.backend.b2bwebsocket/
│   ├── io.openems.backend.uiwebsocket/
│   └── ...
├── doc/                         # Antora documentation source
├── tools/                       # Build helpers, Debian/Docker packaging, Drone/Woodpecker CI scripts
├── config/                      # Runtime OSGi Config Admin files (gitignored; present locally)
├── data/                        # Runtime data, e.g. RRD4J files (gitignored)
└── build/                       # Gradle + assembled fat JARs (gitignored)
```

The repository contains **232 top-level OSGi bundle directories**. Each bundle is a Gradle subproject and must contain a `bnd.bnd` file; `settings.gradle` discovers them automatically.

## 4. Build system and commands

### 4.1 Prerequisites

* JDK 21
* (Optional) InfluxDB, PostgreSQL, Odoo for full runtime testing of backend features

### 4.2 Key Gradle tasks

All commands are run from the repository root using the wrapper:

```bash
# On Windows
.\gradlew.bat <task>

# On Linux/macOS
./gradlew <task>
```

| Task | Purpose |
|------|---------|
| `./gradlew build` | Compile and test all subprojects. |
| `./gradlew buildEdge` | Assemble the Edge fat JAR at `build/openems-edge.jar`. |
| `./gradlew buildBackend` | Assemble the Backend fat JAR at `build/openems-backend.jar`. |
| `./gradlew buildBackendEdge` | Assemble the Backend-Edge fat JAR at `build/openems-backend-edge.jar`. |
| `./gradlew testEdge` | Run JUnit tests for all Edge-labeled bundles. |
| `./gradlew testBackend` | Run JUnit tests for all Backend-labeled bundles. |
| `./gradlew assembleEdge` / `assembleBackend` | Compile and package without tests. |
| `./gradlew cleanEdge` / `cleanBackend` | Clean per-side outputs. |
| `./gradlew buildAggregatedJavadocs` | Generate combined Javadoc to `build/www/javadoc`. |
| `./gradlew buildAntoraDocs` | Build Antora documentation to `build/www`. |
| `./gradlew copyBundleReadmes` | Collect per-bundle `readme.adoc` files into the doc tree. |

The fat-JAR tasks rely on Bnd `resolve.<App>` / `export.<App>` tasks from the application bundles. The output path can be overridden with Gradle properties or environment variables (`oems.edge.output`, `OEMS_EDGE_OUTPUT`, etc.).

### 4.3 Bndrun resolution

The `.bndrun` files (`EdgeApp.bndrun`, `BackendApp.bndrun`, `BackendEdgeApp.bndrun`) declare which bundles are required at runtime. Use Bnd's Gradle tasks to resolve them:

```bash
./gradlew :io.openems.edge.application:resolve.EdgeApp
./gradlew :io.openems.edge.application:export.EdgeApp
```

`tools/prepare-commit.sh` contains an `update_bndrun` helper that regenerates `-runrequires` from the bundle list and re-resolves all three applications. This script also normalizes Eclipse `.classpath`/`.project` files and ensures every `test/` directory has a `.gitignore`.

### 4.4 CI / packaging

* There is **no `.github/` directory** in this fork; GitHub Actions are not configured here.
* `tools/drone/openems-build.sh` builds a Docker image (`openems-build`) for Drone/Woodpecker CI using Eclipse Temurin JDK 21, Node 24, npm 11, Angular CLI, and Chrome headless.
* `tools/build-debian-package.sh`, `tools/create-release.sh`, and `tools/docker/` provide Debian package and container image workflows.
* `tools/integration_tests/` contains Python-based integration tests that drive a real Edge instance via Modbus and REST/Websocket.

## 5. Runtime architecture

OpenEMS runs as an OSGi application inside Apache Felix:

1. **Boot** — a fat JAR starts the Felix framework.
2. **Core start-level bundles** — SCR, Config Admin, Event Admin, File Install, Pax Logging.
3. **Application bundle** — `io.openems.edge.application` or `io.openems.backend.application` activates.
4. **Components** — OSGi Declarative Services components are activated from `.config` files in `felix.cm.dir`.
5. **Cycle** — Edge executes a fixed control cycle (`CycleWorker`) that emits events such as `TOPIC_CYCLE_BEFORE_PROCESS_IMAGE` and `TOPIC_CYCLE_EXECUTE_WRITE`. Bridges and controllers react to these events.

### 5.1 Running locally

Edge:

```bash
./gradlew buildEdge
java -Dfelix.cm.dir=config -Dopenems.data.dir=data -jar build/openems-edge.jar
```

Backend:

```bash
./gradlew buildBackend
java -Dfelix.cm.dir=c:/openems-backend/config -Dopenems.data.dir=c:/openems-backend/data -jar build/openems-backend.jar
```

Default web consoles (default credentials are typically `admin`/`admin`):

* Edge: `http://localhost:8080/system/console`
* Backend: `http://localhost:8079/system/console`

### 5.2 Configuration

Runtime configuration is stored under `config/` (Edge) or `c:/openems-backend/config` (Backend) as OSGi Config Admin `.config` files. The `.gitignore` ignores `config/`, `data/`, and `build/` anywhere in the tree, so runtime state is not committed.

Example EDMI component config (see `EDMI_TESTING_GUIDE.md`):

```json
{
  "class": "Bridge.EDMI",
  "id": "bridge0",
  "alias": "EDMI Bridge",
  "enabled": true,
  "portName": "COM3",
  "baudRate": 9600,
  "databits": 8,
  "stopbits": "ONE",
  "parity": "NONE",
  "url": "http://localhost:8086",
  "org": "my-org",
  "apiKey": "YOUR_INFLUXDB_TOKEN",
  "bucket": "edmi",
  "queryLanguage": "FLUX",
  "interval": 30,
  "survey": 805
}
```

## 6. Module conventions

### 6.1 Bundle naming

Bundles follow the OSGi/Java package convention:

* `io.openems.edge.<domain>.<name>` for Edge bundles.
* `io.openems.backend.<domain>.<name>` for Backend bundles.
* `io.openems.common.*` for shared code.
* `io.openems.wrapper.*` for third-party library wrappers.

Domain examples: `bridge`, `controller`, `ess`, `meter`, `evcs`, `timedata`, `scheduler`, `api`.

### 6.2 Bundle layout

Each bundle has a standard Eclipse/Bndtools layout:

```
io.openems.edge.example/
├── bnd.bnd              # Bundle manifest + buildpath/testpath
├── src/                 # Main Java sources
│   └── io/openems/edge/example/
├── test/                # Test sources (must contain a .gitignore; may be empty)
│   └── io/openems/edge/example/
├── generated/           # Build outputs (gitignored)
├── bin/                 # Compiled classes (gitignored)
├── bin_test/            # Compiled test classes (gitignored)
├── .classpath           # Eclipse classpath
├── .project             # Eclipse project
├── .settings/           # Eclipse preferences
└── readme.adoc          # Optional bundle documentation
```

### 6.3 `bnd.bnd` essentials

Typical content:

```bnd
Bundle-Name: OpenEMS Edge Example
Bundle-Vendor: FENECON GmbH
Bundle-License: https://opensource.org/licenses/EPL-2.0
Bundle-Version: 1.0.0.${tstamp}

-buildpath: \
    ${buildpath},\
    io.openems.common,\
    io.openems.edge.common

-testpath: \
    ${testpath}
```

`${buildpath}` and `${testpath}` are inherited from `cnf/build.bnd`. The project-specific `bnd.bnd` only adds the bundles this module depends on.

### 6.4 Component patterns

* Components extend `AbstractOpenemsComponent` or a domain-specific abstract class (e.g., `AbstractOpenemsModbusComponent`, `AbstractOpenemsIec104Component`, `AbstractOpenemsEdmiComponent`).
* They use `@Component` / `@Activate` / `@Deactivate` / `@Reference` annotations for OSGi DS lifecycle and service injection.
* Channels are defined via inner `ChannelId` enums and created in the constructor; see `CONSTRUCTOR_CHANNEL_INITIALIZATION_EXPLANATION.md`.
* Bridges implement `EventHandler` to listen to cycle events; see `BRIDGE_CYCLE_EVENTS_EXPLANATION.md`.

## 7. Code style guidelines

The project uses Checkstyle via `cnf/checkstyle.xml`. Key rules include:

* UTF-8 source encoding.
* No wildcard imports.
* One top-level class per file.
* Braces required for all control-flow blocks.
* Whitespace around operators and keywords.
* Method names: `^[a-z_][a-zA-Z0-9_]*$`.
* Member names: `^[a-z_][a-zA-Z0-9]*$`.
* Public parameters: `^[a-z]([a-zA-Z0-9]*)?$`.
* Javadoc on public/protected members with `@param`, `@return`, `@throws`, `@deprecated` ordering.
* Line length rule exists but is currently set to `ignore`.

> **Important:** In `build.gradle` the `Checkstyle` task is explicitly `enabled = false` and the aggregate checkstyle tasks (`checkstyleEdge`, `checkstyleBackend`, `checkstyleAll`) are also `enabled = false`. Checkstyle is therefore configured but not enforced by Gradle at the moment.

The project uses 2-space indentation in the Checkstyle config, although many files use tabs. Follow the existing style of the file you are editing.

## 8. Testing instructions

### 8.1 Unit tests

* Test sources live in each bundle's `test/` folder.
* The build supports both JUnit 4 and JUnit 5; existing tests include JUnit 4 (`@Test`, `@Before`) and JUnit 5.
* Mockito and Byte Buddy are on the `testpath`.
* Run a single bundle's tests:

```bash
./gradlew :io.openems.edge.bridge.edmi:test
```

* In CI, Gradle runs tests in parallel forks (`maxParallelForks = Runtime.runtime.availableProcessors() * 0.66`).

### 8.2 EDMI-specific testing

The EDMI bundles include a CSV profile injector for offline testing:

```bash
./gradlew buildEdge
java -Dfelix.cm.dir=config -Dopenems.data.dir=data -jar build/openems-edge.jar
```

Configure `Bridge.EDMI` and `Bridge.EDMI.CsvInjector` components, then verify in InfluxDB:

```flux
from(bucket: "edmi")
  |> range(start: 2025-01-01T00:00:00Z)
  |> filter(fn: (r) => r._measurement == "edmi_separated_energy_interval")
```

See `EDMI_TESTING_GUIDE.md` and `EDMI_EnergySeparation_Testing_Guide.md` for exact steps.

### 8.3 IEC 104 testing

`io.openems.edge.bridge.iec104/test/` contains a Python simulator (`bess_iec104_simulator.py`) that can be used to exercise the IEC 104 client against a local server.

### 8.4 Integration tests

`tools/integration_tests/` provides Python/pytest-based tests that start OpenEMS Edge and exercise it over Modbus, REST, and WebSocket. They require a built Edge artifact.

## 9. Security considerations

* **Default web console credentials** are typically `admin`/`admin` in development configs; change them for production.
* **Config files** under `config/` may contain API keys, database passwords, meter credentials, and serial numbers. They are gitignored but may exist locally; never commit them.
* **OSGi runtime** exposes a web console at `/system/console` and JSON-RPC/REST endpoints. Restrict network access in production.
* **EDMI meter credentials** (`username`, `password`, `serial_number`) are stored in Config Admin and should be treated as secrets.
* **InfluxDB tokens** used by EDMI and time-series bundles must be protected; rotate them regularly.
* **IEC 104 commands** currently return success immediately without waiting for server confirmation. See `IEC104_COMMAND_RESPONSE_FLOW.md` for the known limitation and recommended future improvement.

## 10. License

* OpenEMS Edge and Backend code is licensed under the **Eclipse Public License 2.0** (`LICENSE-EPL-2.0`).
* The OpenEMS UI (when present) is licensed under the **GNU Affero General Public License 3** (`LICENSE-AGPL-3.0`).

## 11. Quick reference for agents

| I want to... | Command / file |
|--------------|----------------|
| Build the Edge fat JAR | `./gradlew buildEdge` |
| Build the Backend fat JAR | `./gradlew buildBackend` |
| Run all Edge tests | `./gradlew testEdge` |
| Run one bundle's tests | `./gradlew :io.openems.edge.<bundle>:test` |
| Resolve Edge runtime | `./gradlew :io.openems.edge.application:resolve.EdgeApp` |
| Prepare a clean commit | `tools/prepare-commit.sh` |
| Checkstyle config | `cnf/checkstyle.xml` (currently not enforced) |
| Dependency manifest | `cnf/pom.xml` |
| Workspace defaults | `cnf/build.bnd` |
| Root build script | `build.gradle` |
| Bundle discovery | `settings.gradle` |
| Runtime config dir | `config/` (Edge), `c:/openems-backend/config` (Backend) |
| Runtime data dir | `data/` (Edge), `c:/openems-backend/data` (Backend) |
| Edge web console | `http://localhost:8080/system/console` |
| Backend web console | `http://localhost:8079/system/console` |

When making changes, prefer **minimal, focused edits**, keep the existing bundle structure, update or add tests in the bundle's `test/` folder, and run the relevant `./gradlew :<bundle>:test` or `./gradlew buildEdge` before finishing.
