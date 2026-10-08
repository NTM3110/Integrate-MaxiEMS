# MAXiEMS Deployer product roadmap

## Purpose of this document

This document is the durable product context for work that follows the original single-device deployer. It records the
three agreed delivery phases, the exact Phase 1 scope, and the product input required from AT Energy. It is a roadmap,
not a claim that every capability below already exists.

The executable implementation sequence for Phase 1 is maintained in
`.omo/plans/maxiems-deployer-phase1-manufacturing.md`; it was finalized on 2026-07-22 and starts at Todo 1.

The current application is a safe, local, single-device Windows deployer. Its published EXE is self-contained and embeds
one verified Edge/UI payload, so a deployment workstation does not need this repository, Java, Node.js, Gradle, or the
.NET runtime. It already provides guarded SSH access, preflight, atomic deployment/update, rollback, component
reconciliation, health checks, and local deployment reports.

The capabilities below extend that foundation into a production-floor product for operators, engineers, and QA.

## Product decisions that must remain stable

1. **Source and deployment are separate activities.** Engineers and CI build releases. Operators deploy immutable,
   approved release artifacts; operator workstations never clone and build production source code.
2. **There are two independent versions.** `DeployerVersion` identifies the Windows application, while
   `EdgeReleaseVersion` identifies the Edge/UI payload installed on a device. Every report records both.
3. **A Git tag is not sufficient evidence.** A production release is the exact artifact that passed build, automated
   tests, review, and approval. Promotion must not rebuild it.
4. **Serial number is the primary physical-device identity.** Network addresses can change. Serial uniqueness is
   enforced within the agreed company scope.
5. **Deployment has explicit gates.** Pre-deployment hardware/OS checks gate `Deploy/Update`; post-deployment automated
   health tests gate `Complete/Ready for warehouse`. Manual FAT is deliberately deferred and can later add a separate
   `Finalize/Handover` gate without rewriting deployment history.
6. **Updates are transactions.** The application detects the installed release, shows the intended difference, preserves
   shared configuration, stops conflicting managed/legacy services inside the transaction, and rolls back on failure.
7. **Secrets are not inventory data.** Passwords, private-key material, GitLab tokens, Basic authorization values, and
   Tailscale login data never appear in manufacturing profiles, device records, checklists, history, or reports.
8. **Engineer efficiency is a product constraint.** Phase 1 is a single-role engineering tool on dedicated workstations.
   It shows approved profiles/releases, device identity, checklist work, one recommended action, progress, and the result.
   Rare or risky controls stay in clearly labeled Advanced sections instead of requiring authentication or role switching.

## Terminology

- **Manufacturing profile:** Versioned, non-secret hardware/BOM and deployment defaults for one product variant. A
  customer project/site assignment is optional and is not required for semi-finished internal warehouse stock.
- **Device record:** The durable identity and lifecycle of one physical Edge device, anchored by serial number.
- **Release artifact:** Immutable Edge/UI/bootstrap content with version, compatibility metadata, hashes, and later a
  digital signature.
- **Deployment run:** One immutable attempt to install or update one device.
- **Pre-deployment checklist:** Manual and automated facts that must pass before target mutation.
- **Automated acceptance tests:** Service, UI, API, component, port, persistence, and release checks after deployment.
- **FAT:** Manual Factory Acceptance Test performed against the running device.
- **Finalize/Handover:** Locks the accepted result and produces the final traceable record.

## Three-phase roadmap

| Phase | Goal | Release source | Data ownership | Primary users |
| --- | --- | --- | --- | --- |
| Phase 1 | Semi-finished production and warehouse intake on one workstation | Embedded release and optionally a verified local release package | Local, per Windows workstation | Engineer on a dedicated workstation |
| Phase 2 | Controlled release delivery from GitLab | Approved immutable CI artifacts, verified download, offline cache/import | Local device data plus GitLab release catalog | Engineer/QA publish; operator selects approved releases |
| Phase 3 | Central multi-user and fleet operations | Same signed release catalog with rollout policy | Central service/database and synchronized clients | Operator, engineer, QA, administrator |

The phases are additive. A later phase must not weaken the deployment, rollback, host-trust, or secret-handling guarantees
already present.

## Phase 1 - Local production-floor workflow

### Outcome

One Windows workstation can manage manufacturing profiles, identify physical devices by serial number, inventory the
expected hardware, perform a gated deploy/update, run automated checks, and export a traceable warehouse record. It
remains usable without GitLab, a customer project, and the source repository.

Phase 1 is local-first, single-workstation and engineer-only. Different engineers use separate authorized workstations, so
the application has no login screen, Operator/Engineer mode switch, Windows-group authorization or local RBAC. It records
the current Windows/workstation identity for traceability. Enterprise identity/RBAC remains a Phase 3 concern.

### 1. Application navigation and usability

Add stable top-level destinations:

- **Dashboard:** work waiting for preparation, deployment, FAT, or rework; recent failures and completions.
- **Manufacturing profiles:** create, edit, validate, approve, clone, archive, import, and export product/BOM profiles.
- **Devices:** register/search/filter a device, scan or type a serial number, inspect status, and start Deploy/Update.
- **Deployments:** the existing deployment wizard enhanced with project, device, release, and checklist context.
- **Checklist:** outstanding hardware/OS checks and deployment readiness. Manual FAT is added later.
- **History & Reports:** immutable deployment attempts and finalized FAT/handover reports.
- **Settings:** local storage, release import, report location, display language, and advanced engineering settings.

Provide keyboard-accessible File/Project/Device/Tools actions. Suggested shortcuts are `Ctrl+N` for a new deployment,
`Ctrl+O` for a verified local release, `Ctrl+F` for device search, and `Ctrl+,` for settings. Shortcuts must not bypass
validation, approval, trust, or deployment gates.

### 2. Manufacturing profiles

A manufacturing profile contains only non-secret defaults:

- profile code, display name, product family, and hardware/BOM variant;
- optional project, customer, site, manufacturing order, and work-order references;
- allowed hardware models/revisions;
- expected CPU architecture/model rules, minimum RAM and storage, and required/optional module identities;
- physical Ethernet port mapping used to identify the serial-source port and the second Ethernet port;
- default embedded or verified local release;
- component profile/manifest reference and its hash;
- Tailscale choice, expected services/ports, and network assumptions;
- device naming/serial rules;
- pre-deployment checklist template version;
- FAT template version;
- report header and required approval names/roles;
- profile version, author, timestamps, state, and change note.

Lifecycle: `Draft -> Validated -> Approved -> Archived`. Draft profiles can be saved at any time. Only an approved
manufacturing profile can be used by the normal deployment flow. Editing an approved profile creates a new draft version
and does not rewrite historical runs.

Validation checks required fields, expected hardware/BOM rules, compatible release, manifest schema, duplicate
identifiers, checklist completeness, and report rules. It performs no remote mutation.

### 3. Local release handling

The current embedded payload remains the default and recovery-capable release. Phase 1 may also import a local release
package produced by the approved publishing process. It must verify metadata, file hashes, payload schema, architecture,
and compatibility before adding the package to the local catalog.

Direct local-project build is Engineering/Developer mode only and is not part of an operator deployment. Production-floor
work must never depend on a working tree or developer toolchain.

Each locally known release records:

- Edge release version and release ID;
- Git revision/build identity when available;
- artifact and payload hashes;
- publication/import time;
- supported OS, architecture, hardware, and upgrade paths;
- minimum compatible Deployer version;
- local status: embedded, imported/verified, deprecated, or blocked.

Digital signing, GitLab approval state, remote withdrawal, and online release promotion are Phase 2 responsibilities.
Phase 1 must never describe an unsigned internal artifact as company-approved or signed.

### 4. Device registry

Store local device records under the current Windows user. Use a transactional local store suitable for filtering and
relationships; do not put credentials in it.

Minimum fields:

- unique serial number and optional barcode/QR value. The confirmed initial format is `MR` plus the normalized permanent
  MAC of the designated second Ethernet port: uppercase, 12 hexadecimal characters, no separators; for example permanent
  MAC `00:61:3A:7D:75:5A` produces serial `MR00613A7D755A`;
- asset ID, model, hardware revision, and manufacturing/work-order reference;
- assigned manufacturing-profile version and optional later project/customer assignment;
- customer/site and physical location when applicable;
- hostname/IP, SSH port, SSH fingerprint reference, and both wired Ethernet interfaces. For each Ethernet port record the
  physical label, observed Linux interface name, permanent MAC, current MAC, link state/speed when available, and which
  port was used as the immutable serial source;
- CPU architecture/model/cores, total RAM, storage type/model/serial/capacity, board identity when exposed, and observed
  OS image/version/kernel;
- detected 4G, LoRa, and Wi-Fi/BLE modules with bus, vendor/product identity, device node/interface, driver, and detection
  result;
- Tailscale name/address when enabled;
- installed Edge release, last known health, and last deployment time;
- lifecycle status, assigned operator/QA name, notes, and timestamps.

Lifecycle for the confirmed Phase 1 scope: `Registered -> Inspecting -> ReadyForDeployment -> Deploying ->
DeploymentVerified -> ReadyForWarehouse`, with explicit `Failed`, `ReworkRequired`, and `Archived` outcomes. Later FAT
work can extend this with `FatInProgress -> Accepted -> Delivered`. Network address changes never create a new device.
Because the initial serial derives from an Ethernet MAC, replacement of that Ethernet hardware must use an explicit
rework/re-identity operation; it must never silently rename the device or rewrite old reports.

Support manual entry, paste, and keyboard-wedge barcode/QR scanners. Duplicate or invalid serials are rejected before a
deployment run starts.

#### Hardware discovery rules

- Do not assume the kernel will always name the two Ethernet interfaces `eth0` and `eth1`; predictable names such as
  `enp1s0` may be used. The manufacturing profile maps physical port labels to stable hardware identity, and the operator
  confirms the mapping when it cannot be proven automatically.
- Prefer a NIC's permanent hardware MAC for serial derivation and record both permanent and current MAC. A locally
  overridden/spoofed current MAC must not silently change the serial.
- Collect CPU model/architecture/core count, installed RAM, and storage model/type/serial/capacity as separate structured
  facts. The UI uses **Storage** rather than the ambiguous term **ROM**.
- `lsusb` evidence is useful but not sufficient. A module may be attached through USB, PCIe, SDIO, UART, SPI, or an
  integrated bus. Detection combines the applicable system inventory, driver, modem/network/Bluetooth state, device-node,
  and board/device-tree evidence.
- 4G detection should identify the expected modem and its usable modem/network interfaces; Wi-Fi/BLE detection should
  identify both radio functions when the combined module is expected; LoRa detection requires the expected vendor/product
  ID, serial device, SPI/UART path, or a product-specific probe supplied by AT Energy.
- Phase 1 inventory proves presence and identity against the approved BOM. End-to-end radio/network behavior tests belong
  to the later FAT unless a specific check is promoted into the blocking pre-deployment template.
- Persist normalized facts and check results. Large raw command output is transient diagnostic evidence and is retained
  only when redacted and explicitly included in a diagnostic report.

### 5. Pre-deployment checklist and Deploy gate

Checklist templates are versioned. A deployment run snapshots the exact template used so later template edits do not
change historical evidence.

The baseline checklist covers:

- serial label and selected device match;
- permanent MAC of the designated serial-source Ethernet port, derived serial, duplicate check, and the identity/current
  MAC of both Ethernet ports;
- correct manufacturing profile, board/model, and hardware revision;
- CPU architecture/model/core count against profile expectations;
- installed RAM against the profile minimum/expected value;
- storage type/model/serial/capacity against the profile expectation;
- detection of required or optional 4G, LoRa, and Wi-Fi/BLE modules using the appropriate USB, PCIe, SDIO, UART, network,
  modem, Bluetooth, or device-node evidence;
- OS release/image version, kernel, and architecture;
- power and network readiness;
- expected SSH endpoint and independently confirmed host fingerprint;
- supported Ubuntu/architecture, disk space, clock, and connectivity;
- credential availability without persisting the credential;
- verified release/package and compatibility result;
- validated manufacturing profile and component manifest;
- occupied required ports classified as current MAXiEMS/recognized legacy service or an external blocker;
- engineer acknowledgement of the displayed install/update plan.

Every item declares whether it is manual or automated and whether failure is blocking. The UI shows the exact reason a
gate is closed. No normal or Advanced action can override a non-overridable blocking item. An engineering exception, if
enabled locally for profile-declared warnings, requires an identity/name, reason, timestamp, and report entry; it must
never bypass host-key, payload-integrity, platform, or secret policy checks.

`Deploy/Update` becomes available only when the manufacturing profile is approved, the derived device identity is valid
and unique, expected required hardware/OS checks pass, the release is verified and compatible, all non-overridable checks
pass, and required manual checklist items are confirmed.

### 6. New install, update, same-release, and downgrade decisions

Before mutation, inspect the target and classify the action:

- **Deploy:** no managed MAXiEMS release exists.
- **Update:** a different compatible release exists; show current, desired, config preservation, service changes, and
  rollback target.
- **No change/repair check:** the exact verified payload is already active; do not report this as an error.
- **Migration:** a recognized legacy OpenEMS/nginx installation owns managed ports; quiesce it transactionally and
  restore its exact state on rollback.
- **Downgrade:** available only inside Advanced actions after compatibility validation, a deliberate confirmation and a
  recorded reason.
- **Blocked conflict:** an unrelated process owns required resources or the target identity does not match the selected
  device.

The release choice is frozen once a run starts. Refreshing/importing releases cannot change an active deployment.

### 7. Deployment and automated acceptance tests

Keep the existing transactional phases, progress timeline, cancellation boundary, rollback, and redacted reporting.
Correlate every phase with manufacturing-profile ID/version, optional project ID/version, serial, device record ID,
Deployer version, Edge release, payload hash, engineer/workstation identity, and deployment run ID.

The minimum post-deployment automated acceptance set is:

- managed systemd services are active and enabled;
- recognized legacy service remains stopped/disabled after a successful migration;
- nginx/UI responds successfully;
- authenticated OpenEMS `getEdgeConfig` succeeds;
- configured components match read-back after restart;
- REST/WebSocket/optional Modbus bindings and exposure match policy;
- installed release ID and hashes match the selected artifact;
- reboot/startup persistence is tested when the selected FAT template requires it;
- rollback evidence exists for a failed update, and previous health is re-probed independently.

Failure enters rollback/rework and cannot be finalized as accepted. A retry creates a new immutable run linked to the
failed run.

### 8. Deferred manual FAT and current completion gate

Manual FAT is intentionally postponed until AT Energy defines the functional test cases. Phase 1 data contracts reserve a
versioned FAT/template/result extension point, but FAT does not block the first warehouse workflow implementation.

For the current scope, `Complete/Ready for warehouse` is enabled only after the deployment transaction and all required
automated acceptance tests pass. A failed or rolled-back run enters rework and cannot be marked ready for warehouse.

When FAT is later enabled, it begins only after automated acceptance passes and may support:

- visible serial/device identity confirmation;
- UI login and expected project/device display;
- meter, inverter, BMS, or other selected signal plausibility;
- Modbus read test when configured;
- network/Tailscale verification when configured;
- alarm/status verification;
- reboot or power-cycle verification when required;
- notes, pass/fail/not-applicable with required reason, engineer, QA/approver, and timestamps;
- optional attachment references for photos or external documents without embedding secrets.

The later `Finalize/Handover` action will require all applicable FAT items to have an accepted result. It will freeze the
FAT result and create a new report revision if a correction is required; it will never silently edit original evidence.

### 9. History, reports, import, and export

History must be searchable by serial, either Ethernet MAC, asset ID, manufacturing profile, optional project, endpoint,
release, engineer/workstation, time, and final state. Selecting a device shows its complete sequence of inspections, deployments,
retries, updates, rollbacks, and later FAT/handover attempts.

JSON and HTML reports add:

- serial/device/manufacturing-profile identities and versions, optional project identity, both Ethernet MAC records, and
  the inspected CPU/RAM/storage/OS/module inventory;
- action classification and current/desired release;
- Deployer version, Git revision when available, artifact/payload hashes;
- checklist template and signed answers;
- automated checks, phase timings, errors, rollback proof;
- FAT template/results and final approver when FAT is later enabled;
- creation/finalization timestamps and links to prior/retry runs.

Provide local backup/export and validated import for manufacturing profiles, device inventory, checklist templates, and
reports.
Import reports conflicts rather than silently overwriting records. No export contains passwords, key material, GitLab
tokens, Basic headers, or Tailscale login data.

#### Logging and credential policy

Every inspection/deployment record stores:

- serial, both Ethernet identities/MACs, manufacturing profile and hardware/OS/module snapshot;
- target endpoint and confirmed SSH host fingerprint, but no private-key path/material or credential;
- Deployer version, selected Edge release/version/ID, Git revision when available, artifact and payload hashes;
- current Windows engineer identity/display name and workstation identity for traceability;
- start/end timestamps in UTC, displayed local timezone, duration, and report creation/finalization time;
- every phase/check state, normalized non-secret observations, actionable redacted errors, service health, and rollback
  proof;
- component IDs and intended/read-back state when safe, but no secret-valued component properties.

The OpenEMS administrator password, temporary SSH/sudo password, SSH private key/passphrase, GitLab token, Basic header,
and Tailscale login data are never deployment-log fields. They remain in memory only for the operation and are cleared.
If AT Energy needs later recovery of a unique per-device OpenEMS password, an approved password vault may store it under
the serial number; the device database/report stores at most a non-secret vault reference. Credential escrow is a separate
business/security decision and must not be implemented as plaintext, reversible report data, CSV, or normal local log.

Confirmed Phase 1 custody decision (2026-07-22): each device uses a unique OpenEMS administrator credential. An authorized
engineer stores it manually in AT Energy's approved password manager under the immutable device serial. The Deployer does
not save, display later, export, or recover that secret; the engineer supplies it only when the deployment workflow
requires input. A future centralized-vault integration may retrieve the credential with short-lived authorization and use
it in memory without exposing it unnecessarily. That later integration must provide server-side roles, access audit,
credential rotation/versioning, backup, and a break-glass recovery policy.

### 10. Phase 1 quality and acceptance criteria

Phase 1 is complete only when all of the following are demonstrated:

1. A clean Windows 10/11 x64 workstation with no repository/toolchain can deploy the embedded release.
2. An engineer can inspect both Ethernet ports, derive/register a unique serial, select an approved manufacturing
   profile, and see exactly one safe recommended
   action for a fresh, current, update, migration, or blocked target.
3. An unapproved/invalid manufacturing profile, invalid/duplicate derived serial, missing required module, hardware/OS
   mismatch, incompatible/unverified release, target-identity mismatch, or failed blocking pre-check prevents
   Deploy/Update with an actionable explanation.
4. A successful run passes automated acceptance before it can be marked `ReadyForWarehouse`; manual FAT remains deferred.
5. A failed update proves rollback and enters rework; retry creates a linked new run without rewriting history.
6. Device history traces serial/MACs -> manufacturing profile -> hardware/OS inventory -> release/hash -> deployment ->
   automated tests -> warehouse-ready decision.
7. Existing single-device deployment, host-key trust, redaction, restricted privilege, legacy migration, and rollback tests
   continue to pass.
8. Keyboard navigation, 200% Windows scaling, Vietnamese/English content decisions, barcode entry, corrupt local-store
   recovery, backup/import, and secret scans are verified.
9. A real fresh device and a real previously deployed device complete the agreed production/warehouse workflow with
   accepted reports.

### Explicit Phase 1 exclusions

- No automatic GitLab browsing/download/promotion or online withdrawal enforcement.
- No source clone/build in the normal production workflow.
- No central device database, cross-workstation synchronization, fleet rollout, or remote job scheduling.
- No Phase 1 login, local user database, Windows-group authorization, role switch or enterprise SSO/RBAC. Access to the
  dedicated engineering workstation is the operational boundary until Phase 3.
- No Backend deployment, non-Ubuntu target expansion, HTTPS automation, or arbitrary OpenEMS component editor.
- No bulk/parallel multi-device deployment. Phase 1 records multiple devices but executes one deliberate transaction at a
  time.

## Phase 2 - GitLab controlled release delivery

### Outcome

Engineers and QA publish one immutable artifact through CI, and operators select only releases approved for their project
and hardware. The Deployer downloads, verifies, caches, and can later use the same artifact offline.

### Scope

- Define a versioned release-package and compatibility schema.
- Build Edge/UI once in GitLab CI, run the required tests, generate SBOM/hashes, and sign the artifact.
- Publish durable artifacts to the company release/package channel; do not depend on short-lived job output.
- Protect production tags/environments and promote the exact tested artifact without rebuilding.
- Add Candidate, Production Recommended, Production Supported, Deprecated, Withdrawn, and Emergency states.
- Configure GitLab endpoint/project/channel in Engineering settings; operators do not enter arbitrary repository URLs.
- Use read-only, least-privilege credentials protected by the Windows credential store; never use an engineer's personal
  token as the factory default.
- Sync a project-filtered release catalog and show publish/approval/effective/end-of-support/withdrawal timestamps.
- Enforce Deployer/payload schema, OS, architecture, hardware, `upgradeFrom`, migration, and rollback compatibility.
- Download to a temporary path, verify signature and every hash, then atomically add to the local cache.
- Support verified cache, signed USB/offline import, last-sync age, configurable stale-catalog policy, and cache cleanup.
- Default to the recommended production release; hide candidate/downgrade choices from operators.
- Record catalog version, approval evidence, signature identity, pipeline/build identity, and exact artifact hash in reports.
- Provide a Deployer self-update notification/path that is separate from Edge/UI release selection.

### Exit criteria

The artifact tested by CI/QA is byte-for-byte the artifact installed on the device; a withdrawn or incompatible release is
blocked online; a cached verified release follows the configured offline age policy; and GitLab/network/token failures do
not corrupt the local catalog or an active deployment.

## Phase 3 - Central multi-user and fleet operations

### Outcome

Multiple workstations share authoritative projects, device identity, deployment/FAT history, roles, and rollout policy
through a central service while each device deployment remains a safe one-target transaction.

### Scope

- Central API/database for projects, profiles, devices, checklist templates, release assignments, reports, and audit.
- Company identity/SSO and enforced Operator, Engineer, QA/Approver, and Administrator permissions.
- Optimistic concurrency, offline work queue, conflict resolution, synchronization, retention, and disaster recovery.
- Fleet dashboard, search, compliance, unsupported-version and failed/rework views.
- Release campaigns with device groups, maintenance windows, staged/canary rollout, pause/resume, and approval gates.
- Central withdrawal/remediation visibility and upgrade-path planning.
- Work-order assignment, operator/QA queues, FAT approval, attachment storage policy, and report retention.
- APIs or controlled imports for ERP/MES/asset systems when required.
- Central audit and metrics without collecting secrets or unredacted command output.

### Exit criteria

Two or more workstations can safely collaborate without duplicate serials or overwritten evidence; role checks are
enforced server-side; offline changes reconcile predictably; rollout status is traceable per device; and central service
loss does not weaken an already-started local deployment transaction.

## Input required from AT Energy for Phase 1

### Required before detailed implementation is frozen

1. **Manufacturing profile/BOM:** the CPU, RAM, storage, OS image/version, Ethernet-port labels, and required/optional
   4G/LoRa/Wi-Fi-BLE modules for each product variant, including vendor/product IDs or other reliable detection evidence.
2. **Ethernet identity mapping:** confirm which physical connector is the serial-source port currently called `eth1`, how
   the two ports are labeled on the enclosure/board, and whether the application must prefer permanent MAC from the NIC
   over a possibly overridden current MAC.
3. **Serial edge cases:** confirm company-wide uniqueness, barcode/QR content, and the required process if the
   serial-source NIC/mainboard is replaced during rework. The base format `MR` + uppercase 12-hex permanent MAC is already
   confirmed.
4. **Hardware acceptance values:** exact CPU/RAM/storage tolerances and whether optional modules should produce Pass,
   Warning, or NotApplicable when absent.
5. **Workflow names:** preferred Vietnamese/English labels for statuses, buttons and reports for the engineer-only UI.
6. **Phase 1 storage decision:** confirm local-only single-workstation records are acceptable until Phase 3, and state the
   required backup/export destination and retention period.
7. **Credential-manager details:** provide the approved company password-manager product, record naming/folder convention,
   authorized Engineer/Administrator roles, and backup/recovery owner. The Phase 1 policy of one unique credential per
   serial and no Deployer/log storage is already confirmed.
8. **Real acceptance targets:** one fresh Ubuntu device and one already-deployed device/update scenario. Credentials must
   be entered through the app during testing and must not be committed to this document or source control.

### Useful but can be supplied during implementation

- later FAT/checklist Word, Excel, PDF, or paper form when functional FAT is defined;
- report/header/logo requirements and an example customer handover report;
- scanner model and a sample scan string;
- known hardware/model compatibility rules;
- engineering workstation resolution, Windows version, keyboard language, and whether touch operation matters;
- desired support window, downgrade policy, and local engineering-exception policy;
- sanitized examples of a successful deployment, failed deployment, rework, and accepted FAT;
- required import/export formats such as CSV, XLSX, JSON, HTML, or PDF;
- estimate of devices per day, projects per workstation, and expected local history size.

### Decisions that are intentionally deferred to Phase 2/3

- GitLab group/project URL, runner design, tag naming, protected environments, package retention, signing infrastructure,
  and read-only credential method are Phase 2 inputs.
- Central hosting, database, SSO provider, role mapping, ERP/MES integration, backup SLA, and fleet rollout rules are
  Phase 3 inputs.

## Recommended next step

Run a short Phase 1 discovery using the required AT Energy inputs, convert the hardware/OS inspection checklist into a
versioned template, draw the guided engineering workflow, and approve the Phase 1 acceptance examples before implementation begins.
Unknown business values should remain explicit placeholders; developers must not invent Ethernet-port mapping, BOM
expectations, hardware tolerances, credential custody, approval authority, or mandatory factory evidence. Functional FAT
remains a reserved extension until AT Energy supplies its test cases.
