# Hướng dẫn kiến trúc và quy trình triển khai OpenEMS BMS

> Cập nhật: 2026-10-02
> Source of truth: `Integrate-MaxiEMS/tools/maxiems-deployer`
> Trellis task triển khai: `.trellis/tasks/10-02-bms-offline-deployment`
> Task NTP API/service/helper contract: `.trellis/tasks/09-30-bms-time-synchronization`

Tài liệu này dành cho người phát triển và vận hành, tổng hợp các quyết định,
cấu trúc code, quy trình build/deploy, trạng thái appliance và các điểm còn mở
được trao đổi trong hai ngày 2026-10-01 và 2026-10-02.

Handoff triển khai dành riêng cho AI/session tiếp theo nằm tại:

```text
.trellis/tasks/10-02-bms-offline-deployment/SESSION_HANDOFF.md
```

## 1. Trạng thái hiện tại

Thiết bị đã deploy:

```text
IP:            192.168.4.127
OS:            Ubuntu 24.04
Architecture:  arm64
Release:       20260930033422-339306857776
Final state:   succeeded
Duration:      00:02:25.3549194
```

Report thành công:

```text
.trellis/.device-deploy/state/maxiems-deployer/reports/
  deployment-090a3f8d90d244b3b7ac8b5a64bc22ab.json
  deployment-090a3f8d90d244b3b7ac8b5a64bc22ab.html
```

Các check đã đạt:

```text
service-active-enabled  true
nginx-ui                true
bms-ready               true
openems-authenticated   true
binding-policy          true
release-integrity       true
```

Đã xác nhận thực tế qua browser và API:

- `maxiems-edge`, nginx và PostgreSQL active.
- UI truy cập tại `http://192.168.4.127`.
- BMS WebSocket đi qua nginx `/websocket`.
- BMS setting hiển thị `127.0.0.1` nhưng kết nối tới backend trên thiết bị phục
  vụ trang web.
- BMS login `admin/admin` thành công với role `PLATFORM_ADMIN`.
- Database `bms` có schema version 1 đến 8.
- `bms.strings.list` trả danh sách rỗng; không copy demo string/data.
- NTP status trả `synchronized=true`, `error=null`.

Chưa thử trên appliance:

- gọi API configure NTP tới một server cụ thể;
- thay đổi WAN/Wi-Fi thật;
- reboot acceptance và kiểm tra persistence sau reboot;
- tạo string bằng UI `Create`, reboot và xác nhận string vẫn tồn tại.

## 2. Phạm vi và source of truth

Pipeline hiện tại dùng OpenEMS BMS:

```text
Integrate-MaxiEMS/tools/maxiems-deployer/
```

Không dùng pipeline OpenMUC deprecated:

```text
MergeOpenems/deploy/
MergeOpenems/releases/
scripts/create-deploy-release.sh
```

Backend BMS được export từ:

```text
Integrate-MaxiEMS/platform/applications/
  io.openems.edge.application/BMSApp.bndrun
```

Frontend production lấy từ repository riêng:

```text
maxicom-frontend/build/
```

Không đưa development runtime config, Bridge Modbus, Huasu TA/TC, calculator,
string, asset, calibration event hoặc demo data vào default release.

## 3. Kiến trúc tổng thể

```text
Backend source + Frontend source
                 |
                 v
       Linux build/control host
       - Java 21 / Gradle
       - Node.js / npm
       - Python 3
       - Docker (chỉ để tải .deb)
       - .NET SDK
                 |
                 v
       Offline release directory
       - maxiems-deployer
       - payload.zip
       - SHA256SUMS
                 |
                 | SSH
                 v
        Ubuntu 24.04 appliance
       - OpenEMS BMS (systemd)
       - nginx
       - PostgreSQL
       - root-owned helpers
       - persistent ConfigAdmin/data
```

Docker chỉ chạy trên build host. Target không tải Docker image, không cần Docker
và không chạy container. Target nhận `.deb` offline và cài trực tiếp bằng APT.

## 4. Cấu trúc thư mục deployer

```text
tools/maxiems-deployer/
├── build-bms-release.sh
├── build-bms-payload.py
├── collect-offline-packages.sh
├── publish-linux.sh
├── verify-linux-release.sh
├── BMS_DEPLOY.md
├── BMS_DEPLOYMENT_ARCHITECTURE_AND_HANDOFF.md
├── LINUX.md
│
├── src/
│   ├── Maxiems.Deployer.Core/
│   ├── Maxiems.Deployer.Infrastructure/
│   ├── Maxiems.Deployer.Cli/
│   └── Maxiems.Deployer.App/
│
├── payload/bootstrap/
│
├── remote/
│   ├── etc/
│   │   ├── nginx/sites-available/maxiems
│   │   ├── sudoers.d/maxiems-deployer
│   │   └── systemd/system/maxiems-edge.service
│   └── usr/local/
│       ├── libexec/
│       └── sbin/
│
└── artifacts/
    ├── offline-ubuntu-24.04/
    └── bms-ubuntu24.04-arm64-20261001-loopback/
```

`bin/` và `obj/` bên trong các .NET project là generated output; không sửa tay
và không coi là source.

## 5. Cấu trúc code C# trong `src/`

### 5.1 Dependency direction

```text
Maxiems.Deployer.Cli ───────────────┐
                                    v
Maxiems.Deployer.App ──> Maxiems.Deployer.Infrastructure
                                    |
                                    v
                         Maxiems.Deployer.Core
```

Linux deployment hiện tại dùng:

```text
Cli -> Infrastructure -> Core
```

`Maxiems.Deployer.App` là Windows WPF manufacturing UI, không phải entrypoint
đã dùng để deploy `192.168.4.127`.

### 5.2 `Maxiems.Deployer.Core`

Pure domain layer, target `net10.0`, không phụ thuộc SSH/filesystem/UI.

```text
Maxiems.Deployer.Core/
├── Contracts/
├── Lifecycle/
├── Preflight/
├── Reporting/
├── Security/
├── Transport/
└── Manufacturing/
```

Vai trò:

- `Contracts/`: `ReleaseId`, `DeploymentPhase`, manifest, target/preflight,
  SSH fingerprint, rollback checkpoint và reconciliation contracts.
- `Lifecycle/`: state/transition policy, journal và deployment run record.
- `Preflight/UbuntuPreflightEvaluator.cs`: quyết định target có hợp lệ không.
- `Security/`: giữ secret trong mutable buffers và redact log/report.
- `Reporting/`: model/serializer/HTML renderer/report integrity.
- `Transport/`: structured remote command contracts.
- `Manufacturing/`: device identity, profile, inspection, lifecycle, acceptance.

### 5.3 `Maxiems.Deployer.Infrastructure`

Real implementation, target `net10.0`, dùng:

```text
SSH.NET
Microsoft.Data.Sqlite
SQLitePCLRaw.bundle_e_sqlite3
Maxiems.Deployer.Core
```

```text
Maxiems.Deployer.Infrastructure/
├── Deployment/
├── Payload/
├── Preflight/
├── Ssh/
├── OpenEms/
├── Tailscale/
└── Manufacturing/
```

Các class deployment quan trọng:

| Class | Trách nhiệm |
|---|---|
| `DeploymentEngine` | Orchestrator chính từ validate đến commit/rollback |
| `DeploymentExecution` | Request/event/result contracts của engine |
| `DeploymentRunBuilder` | Thu phase timing, outcome, checks và logs |
| `DeploymentResultWriter` | Ghi JSON/HTML report |
| `SshBootstrapSession` | Kết nối bootstrap user và cài restricted identity |
| `BootstrapPayloadAssets` | Đọc bootstrap assets từ payload |
| `DeploymentRemoteOperations` | Gọi allowlisted helper action trên target |
| `SshReleaseTransactionClient` | `begin/stage/activate/commit/rollback` |
| `DeploymentFailureRecovery` | Khôi phục release/config/component khi lỗi |
| `BmsReadinessProbe` | Test BMS WebSocket và fresh `admin/admin` |
| `DeploymentApiSession` | Authenticated OpenEMS management session |

Các nhóm khác:

- `Payload/`: verify manifest, path, size, SHA-256 trước mutation.
- `Preflight/`: thu Ubuntu facts, validate SSH key và restricted identity.
- `Ssh/`: host-key pinning, SSH session/upload/forward, safe argument encoding.
- `OpenEms/`: bootstrap shared ConfigAdmin, JSON-RPC, component reconciliation,
  readiness và rollback checkpoint.
- `Tailscale/`: optional; skip nếu request không yêu cầu.
- `Manufacturing/`: hardware inventory, inspection, release catalog và local
  SQLite factory history. SQLite này không phải PostgreSQL `bms` trên target.

### 5.4 `Maxiems.Deployer.Cli`

Linux entrypoint, target `net10.0`, build thành executable `maxiems-deployer`.

```text
Maxiems.Deployer.Cli/
├── Program.cs
├── CliOptions.cs
├── SecretInput.cs
└── Maxiems.Deployer.Cli.csproj
```

`Program.cs`:

1. chỉ cho chạy trên Linux;
2. parse options và kiểm tra input file;
3. tạo XDG state/report directory mode 0700;
4. probe/pin SSH host fingerprint;
5. nhập SSH password, key passphrase và OpenEMS management password bằng hidden
   prompt;
6. tạo `DeploymentExecutionRequest`;
7. gọi `DeploymentEngine.RunAsync()`;
8. stream redacted progress;
9. trả exit code và report paths;
10. clear secret buffers.

CLI:

```bash
maxiems-deployer deploy \
  --host <host> \
  [--port 22] \
  --user <bootstrap-user> \
  --payload <payload.zip> \
  --identity <private-key> \
  [--manifest <components.json>] \
  [--require-reboot]
```

Exit codes:

```text
0    succeeded
1    failed
2    invalid input/trust
3    rolled back
130  cancelled
```

### 5.5 `Maxiems.Deployer.App`

Windows WPF manufacturing application, target `net10.0-windows`, `win-x64`,
self-contained. Nó dùng cùng `Core` và `Infrastructure`, bổ sung dashboard,
profiles, device inspection, history, settings và manufacturing workflow.

Current Ubuntu deployment không dùng WPF/DPAPI path này.

## 6. Vì sao dùng C# cho deployment

Code C# có sẵn các boundary quan trọng:

- SSH connection và host-key verification;
- typed lifecycle/state transitions;
- payload verification;
- atomic release transaction;
- rollback;
- OpenEMS JSON-RPC reconciliation/readiness;
- structured redacted reports;
- manufacturing workflow.

Viết lại toàn bộ bằng Bash sẽ duplicate các boundary này và tăng rủi ro về
secret handling, rollback và structured error behavior.

Phân chia hiện tại:

```text
C# trên control host
  SSH, trust, orchestration, transaction, API checks, report

Bash/Python trên appliance
  APT, files, permissions, PostgreSQL provisioning, systemd, nginx,
  Wi-Fi, WAN, NTP và reboot
```

## 7. Cấu trúc một release directory

Release hiện tại trên control host:

```text
artifacts/bms-ubuntu24.04-arm64-20261001-loopback/
├── maxiems-deployer                     # self-contained Linux CLI
├── maxiems-deployer.pdb                 # debug symbols
├── Maxiems.Deployer.Core.pdb
├── Maxiems.Deployer.Infrastructure.pdb
├── payload.zip                          # toàn bộ payload target
├── SHA256SUMS
├── BMS_DEPLOY.md
└── LINUX.md
```

Kích thước hiện tại:

```text
maxiems-deployer  81,980,786 bytes
payload.zip       206,866,061 bytes
```

### 7.1 `payload.zip`

```text
payload.zip
├── manifest.json
├── edge/
│   └── openems.jar
├── ui/
│   ├── index.html
│   ├── _app/
│   ├── brand/
│   └── config/
├── bootstrap/
├── remote/
│   ├── etc/
│   │   ├── nginx/sites-available/maxiems
│   │   ├── sudoers.d/maxiems-deployer
│   │   └── systemd/system/maxiems-edge.service
│   └── usr/local/
│       ├── libexec/
│       └── sbin/
└── prerequisites/
    └── ubuntu-24.04/
        └── arm64/
            ├── SHA256SUMS
            └── *.deb
```

`manifest.json` format 2 chứa Git revision, source/build timestamp,
target OS/architecture, path, length và SHA-256 của mọi payload file.
`payload.zip.manifest.json` nằm cạnh archive và chứa release ID cùng full
`payloadArchiveSha256`. Release ID dùng build timestamp UTC và 12 ký tự đầu của
canonical `payloadHash` tính từ inventory đã sort theo path; `manifest.json`
không nằm trong inventory nên contract này không tạo vòng self-reference.
`payloadArchiveSha256` tiếp tục kiểm tra nguyên vẹn byte của file ZIP. Release
`SHA256SUMS` ghi thêm dòng comment `# payloadHash: <64 hex>` để tra cứu nhanh;
`sha256sum -c SHA256SUMS` bỏ qua dòng comment này và vẫn kiểm tra các file.
Full release còn có `release.txt` cạnh `payload.zip`, được bao phủ bởi
`SHA256SUMS`. File gồm release ID, build UTC, Git revision và canonical
`payloadHash`. Sau khi xác thực ZIP, target helper tạo cùng nội dung trong
`/opt/maxiems/releases/<release-id>/release.txt`; `/opt/maxiems/current/release.txt`
luôn theo bản active. `release.txt` không thuộc canonical payload inventory vì
nó chứa chính release ID/hash. Release cũ chưa có file thì đọc `manifest.json`.

## 8. Build release

### 8.1 Build frontend

```bash
cd /home/shou/Documents/ATEnergy/MergeOpenems/maxicom-frontend
npm ci
npm run check
npm run build
```

Output:

```text
maxicom-frontend/build/
```

### 8.2 Thu offline dependencies

ARM64:

```bash
cd /home/shou/Documents/ATEnergy/MergeOpenems/Integrate-MaxiEMS/tools/maxiems-deployer
./collect-offline-packages.sh arm64 ./artifacts/offline-ubuntu-24.04
```

AMD64:

```bash
./collect-offline-packages.sh amd64 ./artifacts/offline-ubuntu-24.04
```

Collector chạy temporary `ubuntu:24.04` Docker container trên build host để tải
complete `.deb` dependency closure. `docker run --rm` xóa container sau khi tải.
Không có Docker trên target.

### 8.3 Build full release

```bash
./build-bms-release.sh \
  ../../../maxicom-frontend/build \
  ./artifacts/offline-ubuntu-24.04 \
  arm64 \
  ./artifacts/bms-release
```

Build script:

1. test/publish self-contained Linux CLI;
2. build BMS service/API bundles;
3. chạy `resolve.BMSApp`;
4. chạy `export.BMSApp --rerun`;
5. kiểm tra required embedded bundles/class;
6. đóng gói JAR, UI, helpers, configs và `.deb`;
7. tạo manifest/hash;
8. verify final release.

Không unzip toàn bộ executable JAR. Builder chỉ đọc ZIP central directory và
một embedded API bundle nhỏ trong memory để guard stale export.

## 9. SSH deployment key

`/path/to/bms-deploy-key` chỉ là placeholder cho private SSH key.

Tạo key pair:

```bash
ssh-keygen -t ed25519 -f ./bms-deploy-key
```

```text
bms-deploy-key       private key, chỉ ở control host
bms-deploy-key.pub   public key, được cài vào target
```

Flow:

1. CLI kết nối bootstrap account (`admin`) bằng password/key.
2. Bootstrap tạo restricted account `maxiems-deploy`.
3. Public key được cài tại
   `/var/lib/maxiems-deploy/.ssh/authorized_keys`.
4. CLI đóng bootstrap connection.
5. CLI reconnect bằng `maxiems-deploy` + private key.

Private key không upload, không nằm trong payload và không ghi vào report.

Test-session key hiện tại nằm trong `.trellis/.device-deploy/`; không coi nó là
production manufacturing key và không commit.

## 10. Helper source và install flow

Helper source:

```text
remote/usr/local/
├── libexec/
│   ├── maxiems-bootstrap-helper
│   ├── maxiems-deploy-helper
│   ├── maxiems-deploy-ops
│   ├── maxiems-prerequisite-ops
│   ├── maxiems-runtime-config-ops
│   ├── maxiems-bms-setup
│   ├── maxiems-edge-healthcheck
│   ├── maxiems-helper-common
│   └── maxiems-network-ops
└── sbin/
    ├── maxiems-network-helper
    ├── maxiems-time-helper
    ├── maxiems-system-helper
    ├── maxiems-netcfg
    └── maxiems-wifi
```

`BootstrapPayloadAssets` lấy bootstrap files từ `payload.zip`.
`SshBootstrapSession` upload vào secure temporary directory dạng:

```text
/tmp/maxiems-bootstrap-<32-hex>/
```

Sau đó chạy `maxiems-bootstrap-helper install-layout <source>` bằng sudo.

Installed paths:

```text
remote/usr/local/libexec/*  -> /usr/local/libexec/*
remote/usr/local/sbin/*     -> /usr/local/sbin/*
remote/etc/sudoers.d/*      -> /etc/sudoers.d/*
remote/etc/systemd/*        -> /etc/systemd/*
remote/etc/nginx/*          -> /etc/nginx/*
```

Sudoers được ghi qua temp file, validate bằng `visudo -cf`, rồi atomic replace.
Không có `NOPASSWD: ALL`. `maxiems-deploy` và `openems` chỉ được gọi helper/action
allowlist.

## 11. Offline package behavior

Target package cache:

```text
/var/lib/maxiems/state/offline-packages/
```

Installer tạo local-only APT repository có `Packages` index và SHA-256. APT
sources khác bị tắt cho operation này. Cài bằng package name với:

```text
--no-upgrade
--force-confold
```

Behavior:

| Target state | Kết quả |
|---|---|
| Same version đã có | Skip/reuse |
| Newer compatible version đã có | Giữ version mới |
| Required package thiếu | Cài từ offline cache |
| Dependency thiếu | Resolve từ offline cache |
| Broken/incompatible | Deploy fail trước commit |
| Existing package config | Giữ config cũ bằng `--force-confold` |

Không chạy general OS upgrade và không truyền toàn bộ `.deb` trực tiếp vào APT.

## 12. Full deployment lifecycle

```text
ValidateInput
  -> ConnectBootstrap
  -> Preflight
  -> ProvisionIdentity
  -> ReconnectRestricted
  -> InstallPrerequisites
  -> StageRelease
  -> VerifyRemotePayload
  -> ActivateRelease
  -> StartServices
  -> ConfigureTailscale
  -> ReconcileComponents
  -> HealthCheck
  -> Complete
```

### 12.1 Validate và trust

- verify local payload manifest/hash;
- validate key pair, options và management password;
- probe SSH host key;
- first-seen fingerprint được hiển thị để operator đối chiếu; `y`/`yes` pin
  đúng fingerprint vừa probe, mọi input khác hủy trước mutation;
- changed fingerprint dừng trước mutation.

### 12.2 Bootstrap và restricted identity

- kết nối bootstrap user có sudo;
- collect preflight Ubuntu/version/arch/disk/memory/time/ports/accounts;
- cài `maxiems-deploy` và public key;
- cài root-owned helpers/sudoers/offline package cache;
- disconnect bootstrap user;
- reconnect restricted identity và verify boundary.

### 12.3 Transaction, stage và activate

- đọc current release;
- begin transaction, snapshot previous release/config/service state;
- nếu cần, quiesce legacy `openems.service`;
- upload payload vào `/var/lib/maxiems-deploy/incoming`;
- verify transferred SHA-256;
- extract với path/symlink/size/hash guards;
- cài immutable release vào `/opt/maxiems/releases/<release-id>`;
- atomically đổi `/opt/maxiems/current` symlink;
- validate/install systemd và nginx runtime config.

### 12.4 Database/config và service

- tạo shared OpenEMS bootstrap ConfigAdmin nếu chưa có;
- chạy `maxiems-bms-setup setup`;
- restart `maxiems-edge`;
- reload/restart nginx;
- backend tự chạy schema migrations khi OSGi database component activate.

### 12.5 Readiness và commit

- verify systemd/nginx active+enabled;
- verify local UI HTTP response;
- verify every installed file hash;
- verify Felix `8080` và BMS WebSocket `8085` bind `0.0.0.0`; management
  `8084` chỉ bind loopback; Modbus Server `502` dùng bind IP đã cấu hình;
- verify schema versions 1–8 và admin role;
- call BMS `platformReady`;
- fresh install: login `admin/admin`, token role `PLATFORM_ADMIN`, logout;
- verify authenticated OpenEMS management readiness;
- optional component reconciliation và optional reboot acceptance;
- commit transaction và mark `.maxiems-known-good`.

Nếu lỗi sau mutation, `DeploymentFailureRecovery` restore release link,
runtime configuration và managed-component checkpoint rồi ghi rollback audit.
Database migrations là forward-only; rollback không drop/downgrade DB.

## 13. PostgreSQL, schema và default account

Ba trách nhiệm tách riêng:

```text
maxiems-prerequisite-ops
  cài và enable PostgreSQL software

maxiems-bms-setup setup()
  tạo PostgreSQL role/database và ConfigAdmin

Java backend startup
  tạo/migrate table và bootstrap first admin
```

### 13.1 PostgreSQL software

`maxiems-prerequisite-ops` cài `postgresql` từ offline APT repository và chạy:

```bash
systemctl enable --now postgresql.service
```

### 13.2 Database và role

`maxiems-bms-setup setup()` tạo idempotently:

```text
database: bms
owner:    maxiems_bms
host:     127.0.0.1
port:     5432
```

Random DB password được lưu root-only:

```text
/var/lib/maxiems/state/bms/database.json
```

Database/role/config cùng tên đã tồn tại nhưng không do installer quản lý sẽ làm
deploy fail; installer không tự nhận ownership hoặc reset credential.

### 13.3 ConfigAdmin

`setup()` viết DB ConfigAdmin và auth ConfigAdmin nếu file chưa tồn tại:

```text
/var/lib/maxiems/shared/config/
  io/openems/edge/bms/platform/persistence/BmsDatabaseImpl.config

/var/lib/maxiems/shared/config/
  io/openems/edge/bms/platform/auth/PlatformAuthenticationServiceImpl.config
```

Auth values:

```properties
bootstrapAdminUsername="admin"
bootstrapAdminName="Administrator"
bootstrapAdminPassword="admin"
importLegacyOpenmucAdministrator=B"false"
```

Systemd truyền ConfigAdmin root:

```text
-Dfelix.cm.dir=/var/lib/maxiems/shared/config/
```

Felix ConfigAdmin match `service.pid` với:

```java
@Component(
  name = "io.openems.edge.bms.platform.auth.PlatformAuthenticationServiceImpl",
  immediate = true
)
@Designate(ocd = Config.class)
```

Felix map properties thành `Config` interface và gọi:

```java
@Activate
@Modified
activate(Config config)
```

### 13.4 Tables

`BmsDatabaseImpl.activate()` chạy:

```java
BmsSchema.migrate(this);
FeatureSchemaMigration.migrate(this);
```

`BmsSchema` tạo base schema/tables như:

```text
bms.schema_version
bms.auth_user
bms.auth_user_role
bms.auth_session
bms.auth_audit
bms.bms_string
```

`FeatureSchemaMigration` tạo/upgrade asset, calibration, monitoring, alarms,
history, publication, SoC checkpoint, report và reference tables; cuối cùng ghi
schema versions 2–8.

DDL nằm trong backend JAR để schema luôn đi cùng backend version; deployer không
duplicate một bộ SQL schema độc lập.

### 13.5 Default account creation

Authentication component có mandatory `@Reference BmsDatabase`, nên chỉ activate
sau khi database service và migrations sẵn sàng.

`activate(Config)` gọi bootstrap khi password config không blank. Backend chạy:

```sql
SELECT count(*) FROM bms.auth_user
```

Chỉ khi count bằng 0, backend:

1. sinh UUID;
2. sinh 16-byte random salt;
3. hash `admin` bằng PBKDF2-HMAC-SHA256, 600,000 iterations, 32-byte hash;
4. insert `bms.auth_user`;
5. insert `PLATFORM_ADMIN` vào `bms.auth_user_role`.

Role không nằm trong ConfigAdmin; Java code cố định first bootstrap account là
`PLATFORM_ADMIN`. Nếu đã có bất kỳ user nào, bootstrap không tạo/reset account.

`maxiems-bms-setup ready()` chỉ verify schema/admin/hash; không tạo table/user.
`BmsReadinessProbe` sau đó test login/token/role/logout thật qua WebSocket.

### 13.6 Điểm bảo mật còn mở

Plaintext `admin` không lưu trong PostgreSQL, nhưng hiện vẫn còn trong auth
ConfigAdmin `bootstrapAdminPassword="admin"` mode 0640. Java config mô tả phải
clear sau first startup, nhưng deployment hiện chưa tự blank/remove property.

Backend không reset account vì user count khác 0, nhưng next task nên thiết kế
atomic clear sau successful bootstrap/readiness và thêm regression cho update.

## 14. Runtime layout trên appliance

```text
/opt/maxiems/
├── current -> releases/<active-id>
├── previous -> releases/<previous-id>
└── releases/
    └── <release-id>/
        ├── edge/openems.jar
        ├── ui/
        ├── remote/
        ├── prerequisites/
        ├── bootstrap/
        ├── manifest.json
        └── .maxiems-known-good

/var/lib/maxiems-deploy/
├── .ssh/authorized_keys
└── incoming/

/var/lib/maxiems/
├── shared/config/
└── state/
    ├── bms/database.json
    ├── deployments/
    └── offline-packages/

/var/lib/openems/
├── data/
└── osgi-cache/

/usr/local/libexec/maxiems-*
/usr/local/sbin/maxiems-{wifi,netcfg}
/etc/systemd/system/maxiems-edge.service
/etc/nginx/sites-available/maxiems
/etc/nginx/sites-enabled/maxiems
/etc/sudoers.d/maxiems-deployer
```

Persistent config/data nằm ngoài immutable release, nên update/rollback release
không xóa database, shared ConfigAdmin hoặc OpenEMS data.

## 15. Browser/backend routing

```text
Browser --HTTP :80--> nginx --static--> /opt/maxiems/current/ui
Browser --/websocket--> nginx --> 127.0.0.1:8085 OpenEMS BMS
Browser --/rest/-----> nginx --> 127.0.0.1:8084/rest/
Browser --/jsonrpc---> nginx --> 127.0.0.1:8084/jsonrpc
Operator --HTTP :8080-----------------> Felix Web Console
Direct BMS client --WS :8085----------> OpenEMS BMS
OpenEMS -----------------------> 127.0.0.1:5432/bms
```

Trong frontend, BMS setting `127.0.0.1` là alias cho **thiết bị phục vụ web**.
Khi mở `http://192.168.4.127`, frontend tạo:

```text
ws://192.168.4.127/websocket
```

nginx mới proxy nội bộ tới `127.0.0.1:8085`. Browser không kết nối loopback của
laptop. Explicit non-loopback BMS host vẫn dùng direct `:8085`.

Vite development có `/websocket` proxy tới `BMS_EDGE_TARGET`.

## 16. NTP, Wi-Fi, WAN và reboot helpers

Backend không chạy arbitrary root shell. Nó gọi fixed helpers qua sudoers:

```text
/usr/local/sbin/maxiems-time-helper status
/usr/local/sbin/maxiems-time-helper configure <literal-ip>
/usr/local/sbin/maxiems-system-helper reboot
/usr/local/sbin/maxiems-wifi status|scan|connect ...
/usr/local/sbin/maxiems-netcfg ...
```

Builder bỏ qua `__pycache__` và `.pyc`, vì vậy bytecode Python sinh trong lúc
test không trở thành một bản helper thứ hai trong payload.

NTP API:

```text
bms.tools.time.status({token})
bms.tools.time.configure({token, serverIp})
```

Configure yêu cầu `PLATFORM_ADMIN`, chỉ nhận một IPv4/IPv6 literal, ghi atomic:

```text
/etc/systemd/timesyncd.conf.d/90-maxiems.conf
```

với:

```ini
[Time]
NTP=<server-ip>
FallbackNTP=time.cloudflare.com pool.ntp.org
```

Helper set timezone `Asia/Ho_Chi_Minh`, enable NTP và restart timesyncd. Deploy
không tự thay NTP server/timezone hiện có. `timedatectl`/systemd dùng bản Ubuntu
có sẵn, không đóng gói binary riêng.

## 17. Update preservation

| Item | Fresh install | Update |
|---|---|---|
| Backend JAR | Cài | Thay bằng release mới |
| UI | Cài | Thay bằng release mới |
| nginx/systemd | Validate+cài | Validate+update |
| PostgreSQL database | Tạo | Giữ |
| DB credential | Sinh random | Giữ |
| Schema | Migrate 1–8 | Forward migrate |
| ConfigAdmin | Tạo nếu thiếu | Giữ file đã có |
| BMS account | `admin/admin` | Không reset |
| String/device/asset/event | Không seed | Giữ DB/config hiện có |
| NTP/Wi-Fi/WAN | Không tự đổi | Giữ |

## 18. Lệnh deploy chuẩn

```bash
cd /path/to/bms-release
sha256sum -c SHA256SUMS

./maxiems-deployer deploy \
  --host 192.168.4.127 \
  --user admin \
  --payload ./payload.zip \
  --identity /secure/path/bms-deploy-key
```

CLI hỏi hidden:

```text
Bootstrap SSH/sudo password
SSH private-key passphrase
OpenEMS management password (required; must not be empty)
```

OpenEMS management password độc lập với BMS `admin/admin` và PostgreSQL random
credential. Không truyền secret qua CLI args/env/input JSON.

## 19. Verification commands

Trên appliance:

```bash
systemctl status maxiems-edge nginx postgresql
journalctl -u maxiems-edge -n 100 --no-pager
readlink -f /opt/maxiems/current
sudo -u openems sudo -n /usr/local/sbin/maxiems-time-helper status
```

Kiểm tra release trên build host:

```bash
cd /path/to/bms-release
sha256sum -c SHA256SUMS
./maxiems-deployer --help
```

## 20. Validation đã chạy

Kết quả tổng hợp cuối task trước deploy loopback UI:

```text
Java time/API tests                         5 pass
Core tests                               130 pass
Infrastructure Linux-compatible tests     130 pass
Readiness UUID regression                   4 pass
CLI tests                                  10 pass
Python helper tests                        11 pass
Python packaging tests                      4 pass
Frontend loopback regression                6 pass
Frontend npm check                          0 errors
Frontend production build                  pass
Target deployment + authenticated probes   pass
```

Tám PowerShell packaging tests cũ không chạy trên Linux vì hardcode
`powershell.exe`; Linux packaging có Python builder tests. Một SSH integration
fixture bị skip vì fixture không được bật.

## 21. Các bẫy đã gặp và guard hiện tại

1. Không unzip toàn bộ executable JAR; lần trước việc này treo session lâu.
2. `export.BMSApp` có thể báo UP-TO-DATE dù embedded API bundle mới build. Build
   service/API JAR trước và export với `--rerun`.
3. OpenEMS JSON-RPC request ID phải là UUID canonical có dấu gạch ngang (`D`).
4. Java socket có thể hiện là IPv4-mapped IPv6 `[::ffff:127.0.0.1]`; vẫn là
   loopback hợp lệ.
5. APT local index cần SHA-256 cho từng `.deb`; không tắt integrity check.
6. Không `chmod 755 /var/lib/maxiems` chỉ để upload. Dùng private
   `/var/lib/maxiems-deploy/incoming`.
7. Không cài mọi `.deb` trực tiếp vì có thể ép OS upgrade; cài package name từ
   local-only repository với `--no-upgrade`.
8. Browser `127.0.0.1` không được trỏ vào laptop; loopback setting phải đi
   same-origin `/websocket` rồi nginx proxy tới device-local backend.
9. Token trong browser có thể thuộc backend dev cũ; sau đổi endpoint cần login
   lại.

## 22. Known issues / follow-up

1. Auth ConfigAdmin còn plaintext `bootstrapAdminPassword="admin"`; cần task
   riêng để clear an toàn sau successful first bootstrap/readiness.
2. Legacy REST compatibility qua nginx `/rest` cần kiểm tra theo endpoint thực
   tế; WebSocket path đã kiểm chứng.
3. Chưa chạy physical Wi-Fi/WAN/NTP configure/reboot gates.
4. Task code chưa commit/archive.
5. Root repository đang rất dirty với nhiều thay đổi không liên quan. Không
   revert/stage/commit chúng.
6. `maxicom-frontend` là nested Git repository; commit frontend phải thực hiện
   bên trong repository đó.
7. `tools/maxiems-deployer` trong `Integrate-MaxiEMS` là source of truth; không
   sửa pipeline OpenMUC root.

## 23. Worktree boundaries

Task-related current changes gồm:

- Backend time API/service và system helper integration trong
  `Integrate-MaxiEMS`.
- `BMSApp.bndrun`.
- toàn bộ `Integrate-MaxiEMS/tools/maxiems-deployer/`.
- frontend loopback routing trong `maxicom-frontend`:
  - `src/lib/components/BmsConnection.svelte`
  - `src/lib/services/backend-settings.ts`
  - `vite.config.ts`
  - `tests/backend-settings.test.mjs`
- Trellis task/spec documents liên quan.

Không stage/revert unrelated root config/RRD/demo/generated changes.

## 24. Tài liệu và evidence liên quan

```text
Integrate-MaxiEMS/tools/maxiems-deployer/BMS_DEPLOY.md
Integrate-MaxiEMS/tools/maxiems-deployer/LINUX.md
.trellis/tasks/10-02-bms-offline-deployment/prd.md
.trellis/tasks/10-02-bms-offline-deployment/design.md
.trellis/tasks/10-02-bms-offline-deployment/implement.md
.trellis/tasks/09-30-bms-time-synchronization/prd.md
.trellis/spec/integrate-maxiems/backend/bms-deployment.md
.trellis/spec/frontend/quality-guidelines.md
.trellis/.device-deploy/state/maxiems-deployer/reports/
  deployment-090a3f8d90d244b3b7ac8b5a64bc22ab.json
```

## 25. Code walkthrough theo toàn bộ deployment flow

Phần này là bản đồ từ thao tác operator tới code chạy trên control host và code
chạy trên appliance. Đây là điểm bắt đầu phù hợp khi cần debug một phase hoặc
thay đổi deployment behavior.

Khi cần tìm definition của một type, đọc thêm:

```text
tools/maxiems-deployer/CODE_NAVIGATION.md
```

Tài liệu đó phân biệt type thuộc source MaxiEMS, .NET runtime và package ngoài.

### 25.1 Hai runtime tách biệt

```text
CONTROL HOST                                      TARGET APPLIANCE
Ubuntu development/manufacturing computer         Ubuntu 24.04 amd64/arm64

maxiems-deployer (C#, linux-x64)       --SSH-->   maxiems-deploy account
DeploymentEngine                                   root-owned helpers
SSH.NET                                            OpenJDK 21 + BMSApp.jar
local payload verification                         nginx + PostgreSQL
report generation                                  persistent ConfigAdmin/data
```

`publish-linux.sh` đang publish CLI với `--runtime linux-x64` vì executable này
chạy trên control host hiện tại. Tham số `arm64` hoặc `amd64` của
`build-bms-release.sh` chọn `.deb` dành cho **target**. CLI C# không được cài hoặc
chạy trên appliance.

### 25.2 Overall call graph

```text
Operator
  |
  +-- build-bms-release.sh
  |     +-- publish-linux.sh
  |     |     +-- dotnet test
  |     |     +-- dotnet publish Maxiems.Deployer.Cli
  |     +-- build-bms-payload.py --build-backend
  |     |     +-- Gradle service/API JAR
  |     |     +-- resolve.BMSApp
  |     |     +-- export.BMSApp --rerun
  |     |     +-- payload.zip + manifest.json
  |     +-- SHA256SUMS
  |     +-- verify-linux-release.sh
  |
  +-- ./maxiems-deployer deploy ...
        +-- Program.Main()
        |     +-- parse CLI
        |     +-- verify/pin SSH host key
        |     +-- read secrets without echo
        |     +-- DeploymentEngine.RunAsync()
        |
        +-- DeploymentEngine.RunAsync()
              +-- verify local payload
              +-- connect bootstrap account
              +-- preflight target
              +-- install restricted identity/helpers/offline cache
              +-- reconnect as maxiems-deploy
              +-- install prerequisites
              +-- begin release transaction
              +-- upload/stage/verify payload
              +-- atomically activate release
              +-- install runtime config
              +-- bootstrap OpenEMS management config
              +-- provision BMS PostgreSQL/config
              +-- start Edge/nginx
              +-- optional component reconciliation
              +-- readiness/acceptance
              +-- commit or rollback
              +-- write JSON/HTML report
```

### 25.3 Phase-to-code map

| Phase | Control-host code | Target code/action | State/output chính |
|---|---|---|---|
| Build CLI | `publish-linux.sh` | Không có | Self-contained `maxiems-deployer` |
| Build payload | `build-bms-payload.py` | Không có | `payload.zip`, embedded `manifest.json` |
| CLI start | `Cli/Program.cs` | Không có | Parsed options, trusted fingerprint, secret buffers |
| Local validation | `PayloadVerifier.VerifyArchive()` | Không có | Release ID và verified file inventory |
| Bootstrap connect | `SshBootstrapSession.ConnectAsync()` | `sshd` | Pinned SSH/SFTP session |
| Preflight | `UbuntuPreflightCollector`, `UbuntuPreflightEvaluator` | Read-only OS commands | Ubuntu/arch/resources/service facts |
| Provision identity | `BootstrapPayloadAssets.Read()`, `SshBootstrapSession.InstallAsync()` | `maxiems-bootstrap-helper` | `maxiems-deploy`, helpers, sudoers, offline cache |
| Restricted reconnect | `SshNetTransportSession.ConnectAsync()` | `maxiems-deploy` SSH key | Restricted session |
| Prerequisites | `DeploymentRemoteOperations.RunAsync()` | `maxiems-prerequisite-ops` | Java/nginx/PostgreSQL/NetworkManager installed |
| Begin/stage | `SshReleaseTransactionClient` | `maxiems-deploy-helper` -> `maxiems-deploy-ops` | Transaction snapshot + immutable release directory |
| Activate | `SshReleaseTransactionClient.ActivateAsync()` | `activate_release()` | Atomic `/opt/maxiems/current` link |
| Runtime config | `DeploymentRemoteOperations` | `maxiems-runtime-config-ops` | Validated systemd/nginx config |
| OpenEMS bootstrap | `OpenEmsBootstrapCoordinator` | `bootstrap-config` | Four initial OpenEMS ConfigAdmin files |
| BMS bootstrap | `DeploymentRemoteOperations` | `maxiems-bms-setup setup` | DB role/database and BMS ConfigAdmin |
| Start | `DeploymentEngine` | `systemctl restart`, nginx reload | Backend/UI running |
| Reconcile | `OpenEmsComponentReconciler` | Authenticated JSON-RPC | Optional components match manifest |
| Readiness | `BmsReadinessProbe`, `OpenEmsReadinessProbe` | `status`, `bms-ready` | DB/auth/API/binding/integrity evidence |
| Commit | `SshReleaseTransactionClient.CommitAsync()` | `commit_transaction()` | Known-good marker and previous release link |
| Failure | `DeploymentFailureRecovery.RecoverAsync()` | `abort_transaction()` plus component restore | Rolled-back or failed report |
| Report | `DeploymentResultWriter.FinishAsync()` | Không có | Redacted JSON/HTML report |

### 25.4 Process A — tạo offline release trên control host

#### Entry point

```text
tools/maxiems-deployer/build-bms-release.sh
```

Input:

```text
UI_DIST                 maxicom-frontend/build
OFFLINE_PACKAGES_ROOT   directory chứa amd64/ hoặc arm64/
ARCH                    amd64 hoặc arm64 của appliance
NEW_RELEASE_DIRECTORY   output mới, không được tồn tại trước
```

Key flow trong `build-bms-release.sh`:

```bash
"$here/publish-linux.sh" "$output"
python3 "$here/build-bms-payload.py" --build-backend \
  --ui-dist "$ui" --packages "$packages" \
  --architecture "$arch" --output "$output/payload.zip"
find . -type f ! -name SHA256SUMS ... | xargs -0 sha256sum > SHA256SUMS
"$here/verify-linux-release.sh" "$output"
```

Các phần tham gia:

1. `publish-linux.sh` chạy CLI tests rồi publish single-file, self-contained
   `Maxiems.Deployer.Cli`.
2. `build-bms-payload.py` build hai bundle vừa thay đổi, rồi force resolve/export
   `BMSApp` bằng `--rerun --offline`.
3. Builder kiểm tra central directory của `BMSApp.jar` và chỉ đọc embedded
   `io.openems.edge.bms.api.jar` vào memory để tìm `BmsTimeJsonApi.class`.
4. `package_inventory()` đọc metadata thực bằng `dpkg-deb`, kiểm tra architecture
   và required package set. Filename do operator cung cấp không được tin trực tiếp.
5. `build()` ghép JAR, UI, bootstrap assets, target helpers/config và `.deb`.
6. Mỗi file có `path`, `length`, `sha256`; canonical inventory tạo
   `payloadHash` và `releaseId`.
7. ZIP được viết vào temporary file và `os.replace()` sau khi hoàn tất.
8. `verify-linux-release.sh` kiểm tra checksum và chạy CLI với `DOTNET_ROOT` giả
   để chứng minh executable không cần .NET runtime trên control host.

Output boundary:

```text
release-directory/
├── maxiems-deployer       control-host executable
├── payload.zip            dữ liệu sẽ truyền tới target
├── SHA256SUMS             integrity cho release directory
└── guides/debug symbols
```

### 25.5 Process B — CLI start, trust và secret handling

#### Entry point

```text
src/Maxiems.Deployer.Cli/Program.cs
```

Key section:

```csharp
var decision = await coordinator.EvaluateAsync(...);
...
bootstrap = Prompt("Bootstrap SSH / sudo password ...");
passphrase = Prompt("SSH private-key passphrase ...");
management = Prompt("OpenEMS management password ...");
...
var result = await new DeploymentEngine(trust).RunAsync(request, progress => ...);
```

Flow:

1. `CliOptions.Parse()` nhận host, bootstrap username, payload, SSH key và option.
2. XDG state directory và report directory được tạo mode `0700`; symlink bị từ
   chối để tránh ghi nhầm vị trí.
3. `HostTrustCoordinator` probe SSH host key.
4. First-seen host hiển thị full SHA-256 fingerprint và hỏi
   `Trust this host key? [y/N]:`; chỉ `y`/`yes` xác nhận fingerprint vừa probe.
   Fingerprint thay đổi trả exit code 2 trước mutation và không có prompt thay
   thế trust.
5. Password/passphrase được đọc bằng `SecretInput` không echo.
6. `SecretRedactor` che secret trong progress/error/report.
7. `finally` clear các `char[]` chứa secret.

Ba credential có chức năng riêng:

| Credential | Dùng ở đâu |
|---|---|
| Bootstrap SSH/sudo password | Đăng nhập account ban đầu và chạy bootstrap helper |
| SSH key/passphrase | Đăng nhập restricted `maxiems-deploy` sau bootstrap |
| OpenEMS management password | Tạo/authenticate OpenEMS `Core.User`; không phải BMS `admin/admin` |

### 25.6 Process C — local payload verification và orchestration

#### Orchestrator

```text
src/Maxiems.Deployer.Infrastructure/Deployment/DeploymentEngine.cs
```

Main method:

```csharp
public async Task<DeploymentExecutionResult> RunAsync(...)
```

Ngay đầu method:

```csharp
var verification = PayloadVerifier.VerifyArchive(request.PayloadPath);
var releaseId = new ReleaseId(verification.ReleaseId);
var payloadHash = await DeploymentResultWriter.HashAsync(...);
```

`PayloadVerifier.VerifyArchive()` kiểm tra:

- ZIP/manifest format;
- unique và safe relative path;
- file count và compressed/uncompressed size limit;
- length/hash của mọi entry;
- canonical `payloadHash`;
- release ID được derive từ `buildTimestampUtc` và canonical `payloadHash`.

`DeploymentRunBuilder.PhaseAsync()` bọc mỗi phase để thu start/end/outcome và
phát progress event. `DeploymentEngine` giữ các resource/state dùng cho recovery:

```text
bootstrap session
restricted session
authenticated API session
release transaction client
component checkpoint
transactionActive
previousRelease
```

### 25.7 Process D — bootstrap connection và target preflight

Control-host classes:

```text
SshBootstrapSession
UbuntuPreflightCollector
UbuntuPreflightEvaluator
TargetIdentityGuard                 manufacturing flow, nếu có
```

`SshBootstrapSession.ConnectAsync()` tạo cả SSH và SFTP connection, gắn
`HostKeyReceived` vào hai client, rồi chỉ trust khi fingerprint fixed-time match
với trust store.

Preflight đọc target trước mutation và reject nếu không thỏa contract, gồm:

- Ubuntu 24.04 và architecture hỗ trợ;
- disk/memory/time facts;
- sudo capability;
- account/service/port state;
- legacy `openems.service` state;
- Modbus requirement khi component manifest yêu cầu port 502.

Nếu manufacturing UI đã inspect permanent identity, `TargetIdentityGuard`
re-verify identity ngay trước deployment để tránh operator đổi target sau review.

### 25.8 Process E — cài restricted identity và root-owned helper

Control-host code:

```text
BootstrapPayloadAssets.Read(payloadPath, publicKey)
SshBootstrapSession.InstallAsync(assets, publicKey)
```

Target code:

```text
remote/usr/local/libexec/maxiems-bootstrap-helper
remote/etc/sudoers.d/maxiems-deployer
```

`BootstrapPayloadAssets.Read()` lấy đúng allowlist asset từ `payload.zip`, lấy
`.deb` theo `prerequisites/ubuntu-24.04/<arch>/`, tự tạo checksum cạnh mỗi package
và thêm public key.

`InstallAsync()`:

1. enroll public key vào bootstrap user's `authorized_keys` idempotently;
2. tạo `/tmp/maxiems-bootstrap-<uuid>` mode 0700;
3. SFTP upload bootstrap assets;
4. chạy `maxiems-bootstrap-helper install-layout` bằng sudo;
5. xóa temporary directory trong `finally`.

`maxiems-bootstrap-helper`:

- tạo `maxiems-deploy` và `openems` account;
- cài authorized key cho `maxiems-deploy` mode 0600;
- cài public dispatch helpers mode 0755 và internal ops mode 0700 root-only;
- thêm `openems` vào `dialout`;
- chọn `.deb` đúng `dpkg --print-architecture`, verify SHA-256;
- thay offline cache bằng atomic directory move;
- validate sudoers qua `visudo -cf` trước replace;
- tạo release/state/incoming directories với owner/mode đúng.

Sau đó bootstrap session đóng. Mọi phase còn lại reconnect bằng
`maxiems-deploy` và `RestrictedIdentityVerifier` xác nhận account không có quyền
ngoài boundary dự kiến.

### 25.9 Process F — cài prerequisites offline

Call chain:

```text
DeploymentRemoteOperations.RunAsync("install-prerequisites")
  -> sudo -n /usr/local/libexec/maxiems-deploy-helper install-prerequisites
  -> /usr/local/libexec/maxiems-deploy-ops install-prerequisites
  -> /usr/local/libexec/maxiems-prerequisite-ops
```

`maxiems-deploy-helper` là public validator: kiểm tra operation và argument count,
rồi mới `exec` internal `maxiems-deploy-ops`. C# không gửi arbitrary shell text;
`RemoteCommandEncoder` chỉ map typed `RemoteProgram` sang command đã allowlist.

`maxiems-prerequisite-ops`:

1. tạo `Packages` index có `Filename`, `Size`, `SHA256`;
2. tạo một APT source local-only;
3. chạy `apt-get update` chỉ với source đó;
4. cài bằng package name, `--no-upgrade`, `--force-confold`;
5. verify Java 21 và required executable;
6. validate nginx;
7. enable PostgreSQL;
8. enable NetworkManager nhưng chưa take over interface.

### 25.10 Process G — release transaction, upload, stage và activate

Control-host code:

```text
SshReleaseTransactionClient.BeginAsync()
SshReleaseTransactionClient.StageAsync()
SshReleaseTransactionClient.ActivateAsync()
```

Target code:

```text
maxiems-deploy-helper
maxiems-deploy-ops:
  begin_transaction()
  stage_release()
  activate_release()
  commit_transaction()
  abort_transaction()
```

#### Begin

`begin_transaction()` lấy exclusive lock và snapshot:

```text
/opt/maxiems/current release ID
systemd unit
nginx site/symlink/default site
legacy nginx link
service active/enabled states
bootstrap-config-absent marker
```

#### Stage

`SshNetTransportSession.UploadToRandomStagingAsync()` upload vào private incoming
directory và trả upload token + SHA-256. `StageAsync()` chỉ chấp nhận token dạng
`.upload-<32 hex>`.

`stage_release()` kiểm tra lại trên target:

- archive hash và size;
- entry count/uncompressed limit/free disk;
- no traversal, absolute path, backslash, special file hoặc symlink;
- manifest format 2/build timestamp và release ID khớp canonical `payloadHash`;
- per-file length/hash;
- actual file list bằng expected manifest list.

Release được extract vào `.staging-<release-id>`, chmod/owner theo policy, rồi
atomic move thành `/opt/maxiems/releases/<release-id>`.

#### Activate

`activate_release()` tạo `current.next` symlink rồi:

```bash
mv -Tf /opt/maxiems/current.next /opt/maxiems/current
```

Do đó process luôn nhìn thấy symlink cũ hoặc mới, không thấy trạng thái link bị
thiếu giữa chừng.

Khi deploy đúng release ID đang active, engine bỏ upload/stage và verify release
có sẵn. Nếu legacy `openems.service` còn active/enabled, transaction vẫn chạy để
snapshot rồi quiesce service cũ.

### 25.11 Process H — runtime systemd/nginx configuration

Call:

```text
RunAsync("runtime-config")
  -> maxiems-runtime-config-ops
  -> maxiems-kiosk-ops
```

Helper lấy candidate từ active release:

```text
/opt/maxiems/current/remote/etc/systemd/system/maxiems-edge.service
/opt/maxiems/current/remote/etc/nginx/sites-available/maxiems
```

Trước install:

- `systemd-analyze verify` candidate unit;
- tạo temporary nginx top-level config và `nginx -t` candidate site;
- snapshot existing unit/site/default link.

Sau install:

- enable `maxiems-edge.service`;
- remove nginx default/recognized legacy site;
- chạy `daemon-reload` và `nginx -t`;
- EXIT trap restore last-good nếu bất kỳ bước nào lỗi.

Kiosk BMS dùng maxiems-kiosk-ops sau cấu hình Edge/nginx. Helper cài
/etc/systemd/system/maxiems-kiosk.service và Sway include trong
/home/admin/.config/sway/config.d/maxiems-kiosk.conf. System unit enable vào
`multi-user.target` nhưng chạy bằng `User=admin`/`Group=admin`, nên operator dùng
`sudo systemctl enable|start|status maxiems-kiosk.service`. Supervisor có thể
start trước Sway; nó chờ private `/run/user/<admin-uid>` và Unix socket
`wayland-N` thật thuộc `admin`, rồi truyền `XDG_RUNTIME_DIR`/`WAYLAND_DISPLAY`
cho Chromium. Sway include không start service. Unit/include không đổi thì không
ghi lại; service active với unit không đổi thì không restart. Sway và Chromium phải được cài
sẵn vì offline cache chưa phân phối chúng. Installer chỉ thay Sway launcher
cũ khi nhận diện đúng nội dung và dọn GNOME autostart cũ do MaxiEMS quản lý.

Mode managed cài thêm drop-in `getty@tty1` để local console tự login `admin` và
launcher root-owned `/etc/profile.d/90-maxiems-kiosk-sway.sh`. Launcher xác nhận
`/dev/tty1`, loại SSH và display đang tồn tại rồi mới `exec sway`. Khi tty1 đang
có user, helper chờ logout/reboot thay vì cắt session. Transaction phục hồi cả
hai asset và trạng thái getty; mode preserve không kiểm tra hay thay đổi chúng.
Managed upgrade dừng và gỡ exact user unit cùng enable link của release cũ;
asset không nhận diện hoặc symlink không an toàn bị từ chối trước mutation.
Transaction snapshot Sway config/include, system unit/service state, legacy user
unit/link/active state và autostart cũ để rollback.

Virtual keyboard không nằm trong bundle Java. `BMS.Keyboard` chỉ gọi constrained
helper; full payload phải chứa `helpers/maxiems-keyboard-controller` và ARM64
`helpers/wvkbd-mobintl`. Sway start controller trong đúng Wayland environment;
controller sở hữu tối đa một wvkbd child và nhận action qua FIFO
`/var/lib/maxiems/kbd/kbd-command`. Bootstrap cài `/usr/local/sbin/maxiems-keyboard`
và ba exact sudo action. Managed kiosk thêm `admin` vào group `openems`, tạo
directory FIFO với `root:openems 0770`; session đã chạy cần logout/reboot để
nhận group mới. Frontend phát normal/dialer/hide từ focus toàn cục sau platform
readiness; anonymous login chỉ được gửi normal. System unit giữ `PrivateTmp`
nhưng không đặt `NoNewPrivileges`, vì launcher Snap Chromium cần privilege
transition trước khi browser tiếp tục dưới `User=admin`; supervisor ưu tiên
binary `/snap/bin/chromium`.

CLI `--kiosk managed|preserve` chọn cách xử lý kiosk, mặc định `managed`.
`runtime-config` luôn chạy `maxiems-runtime-config-ops` và migration WebSocket;
chỉ `managed` gọi `maxiems-kiosk-ops`. `preserve` không thay unit, autostart,
Sway include hoặc trạng thái service kiosk; rollback cũng bỏ qua các mục kiosk.
Mode được ghi vào report ở check `kiosk-mode`. Payload compatibility gate yêu
cầu exact helper/sudoers protocol của cả hai mode trước khi SSH.

Key systemd behavior:

```text
User=openems
-Dfelix.cm.dir=/var/lib/maxiems/shared/config/
-Dopenems.data.dir=/var/lib/openems/data
-Dorg.apache.felix.http.host=0.0.0.0
-Dorg.osgi.service.http.port=8080
-jar /opt/maxiems/current/edge/openems.jar
ExecStartPost=maxiems-edge-healthcheck 180
```

Key nginx behavior:

```text
/                  -> static /opt/maxiems/current/ui
/websocket         -> 127.0.0.1:8085
/rest/             -> 127.0.0.1:8084/rest/
/jsonrpc           -> 127.0.0.1:8084/jsonrpc
```

### 25.12 Process I — OpenEMS bootstrap config

Đây là OpenEMS management/API bootstrap, tách khỏi BMS platform account.

Call chain:

```text
OpenEmsBootstrapCoordinator.EnsureAsync(managementPassword)
  -> SshOpenEmsBootstrapStore.ConfigurationExistsAsync()
  -> nếu absent: OpenEmsBootstrapConfiguration.Create()
  -> SshOpenEmsBootstrapStore.InstallFreshAsync()
  -> deploy helper bootstrap-config <token> <sha256>
```

Bốn file được tạo trên fresh install:

```text
Core/ComponentManager.config
Core/User.config
Controller/Api/Rest/ReadWrite/maxiems.config
Controller/Api/Websocket/maxiems.config
```

Target `install_bootstrap_config()` chỉ chấp nhận đúng bốn ZIP entry, kiểm tra
property allowlist/count, cấm repository default OpenEMS credentials, bắt REST
bind `127.0.0.1` và WebSocket bind `0.0.0.0`, rồi atomic move directory vào
shared ConfigAdmin.

Trên update, `maxiems-deploy-ops` chỉ migrate
`Controller/Api/Websocket/maxiems.config` khi file vẫn đúng exact legacy shape
do deployer tạo, có `id="ctrlApiWebsocket0"`, PID
`Controller.Api.Websocket.maxiems`, port `8085` và bind `127.0.0.1`. File này
được snapshot trong transaction và restore khi rollback. WebSocket component
custom hoặc file khác không bị sửa.

Đây là pre-start compatibility migration. Sau backend start, bundled component
manifest reconcile `enabled`, `bindAddress`, `port` trên exact managed
ID/factory của REST/WebSocket. Property custom khác được merge/giữ; component
khác ID/factory không bị mutate. Contract API này supersede cách hiểu cũ rằng
mọi partial shape cùng managed ID đều được bỏ qua.

Nếu shared config đã có data, coordinator trả `PreservedExisting`; update không
ghi đè password hoặc component configuration của user.

### 25.13 Process J — PostgreSQL và BMS platform bootstrap

Call:

```text
RunAsync("bms-setup")
  -> maxiems-bms-setup setup
```

Key functions trong `maxiems-bms-setup`:

```python
sql(statement, database='postgres')
config_path(pid)
write_config(pid, values, factory=None)
setup()
ready()
```

`setup()`:

1. tạo random 64-hex database password nếu installer chưa có managed state;
2. refuse nếu phát hiện database/role/config cùng mục đích nhưng unmanaged;
3. tạo role `maxiems_bms` và database `bms` idempotently;
4. verify database owner;
5. tạo minimum Core/Scheduler ConfigAdmin khi thiếu;
6. tạo `BmsDatabaseImpl.config` trỏ `127.0.0.1:5432/bms`;
7. tạo `PlatformAuthenticationServiceImpl.config` yêu cầu bootstrap
   `admin/admin` khi auth table rỗng.

Sau service start, Java code mới sở hữu table DDL:

```text
BmsDatabaseImpl.activate()
  -> BmsSchema.migrate(this)
  -> FeatureSchemaMigration.migrate(this)

PlatformAuthenticationServiceImpl.activate(config)
  -> nếu bms.auth_user rỗng
  -> hash admin bằng PBKDF2-HMAC-SHA256
  -> insert user + PLATFORM_ADMIN role
```

Thứ tự này giải thích vì sao `setup()` không trực tiếp tạo tables hoặc hash BMS
password: schema và account semantics đi cùng backend version.

### 25.14 Process K — start services và optional component reconciliation

Engine gọi:

```csharp
await operations.RunAsync("service", ["restart"], ...);
await operations.RunAsync("nginx", ["reload"], ...);
```

`maxiems-edge.service` chạy backend. `ExecStartPost` đợi REST listener tối đa
180 giây. nginx chỉ reload sau `nginx -t` pass.

Normal release bundle component migration manifest; optional manifest của
operator được merge cho component bổ sung:

```text
ComponentManifestParser
  -> DeploymentApiSession
  -> OpenEmsComponentReconciler.CaptureAsync()
  -> ReconcileAsync()
  -> read-back VerifyAsync()
```

Reconciler dùng authenticated OpenEMS JSON-RPC, capture checkpoint trước thay
đổi, apply create/update theo manifest và đọc lại authoritative state. Bundled
`components/managed-components.v1.json` chứa REST/WebSocket deployment contract
với `updateOnly`, nên normal CLI deploy tự migrate component managed đã có nhưng
không tạo feature/demo component bị thiếu. External manifest không được claim
trùng component ID với bundled manifest; factory-only selector cũng bị từ chối
nếu overlap một exact-ID entry hoặc một factory selector khác.

Với component hiện hữu, chỉ property có tên trong manifest được migrate.
`updateComponentConfig` của OpenEMS là property-scoped, nên reconciler chỉ gửi
managed property; không gửi lại property operator/custom/secret đọc từ snapshot
vì secret có thể đã bị mask. `_lastChange*` và `service.*` cũng không được phép
đi vào mutation. Read-back xác nhận managed value mới và custom property còn
nguyên.
Checkpoint giữ full snapshot trước migration để rollback restore/verify trạng
thái cũ. Default manifest không tạo string/TA/TC/calculator/Modbus TCP, nên fresh
database/profile vẫn rỗng cho tới khi user nhấn `Create` hoặc cấu hình riêng.

### 25.15 Process L — layered readiness và acceptance

Readiness không dựa vào một HTTP 200 duy nhất.

#### Target OS/release check

```text
maxiems-deploy-ops verify_status()
```

Nó verify:

- `maxiems-edge` và nginx active+enabled;
- UI trả HTTP success;
- `/opt/maxiems/current` nằm dưới managed release root;
- hash mọi installed file khớp manifest;
- legacy OpenEMS inactive/disabled;
- `8080` và `8085` tồn tại dưới IPv4 và bind wildcard; parser chấp nhận cả
  `0.0.0.0:PORT` và cách iproute2 render tương đương `*:PORT`;
- `8084` nếu tồn tại chỉ bind loopback; Modbus Server `502` dùng bind IP đã cấu
  hình và Edge unit có `CAP_NET_BIND_SERVICE` để mở cổng đặc quyền.

#### Database/BMS check

```text
maxiems-bms-setup ready()
```

Nó poll tối đa 180 giây cho schema versions 1–8, ít nhất một
`PLATFORM_ADMIN`, rồi trả:

```text
fresh      admin vẫn dùng default password và cần fresh-login test
existing   account đã đổi/rename; giữ nguyên và không reset
```

#### BMS WebSocket check

```text
BmsReadinessProbe.WaitAsync(session, verifyDefaultLogin)
```

Probe vẫn dùng SSH local forward tới device-local port 8085 để readiness không
phụ thuộc route/firewall từ control host, rồi:

- gọi `bms.platform.ready`;
- fresh install: login `admin/admin`;
- kiểm tra token role `PLATFORM_ADMIN`;
- logout.

#### OpenEMS management check

```text
DeploymentApiSession
OpenEmsReadinessProbe.WaitAsync()
```

Probe dùng management password đã nhập để authenticate JSON-RPC và verify core
component/API readiness. Nếu manifest yêu cầu Modbus, `ProbeModbusAsync()` kiểm
tra TCP listener 502 qua SSH forward.

#### Optional reboot acceptance

Với `--require-reboot`, engine gọi reboot, đóng sessions, reconnect với pinned
host identity tối đa ba phút và chạy lại status + BMS + OpenEMS readiness. Check
`reboot-persistence=true` chỉ được ghi sau khi toàn bộ flow sau reboot pass.

### 25.16 Process M — commit, update retention và cleanup

Sau tất cả readiness:

```text
SshReleaseTransactionClient.CommitAsync()
  -> commit_transaction()
```

Commit:

- xác nhận `current` đang trỏ đúng release;
- tạo read-only `.maxiems-known-good`;
- cập nhật atomic `/opt/maxiems/previous`;
- giữ active + previous release, xóa release cũ khác;
- ghi transaction `committed` và thả lock.

Persistent state không nằm trong release directory:

```text
/var/lib/maxiems/shared/config
/var/lib/openems/data
/var/lib/maxiems/state/bms/database.json
PostgreSQL data directory
```

Vì vậy update JAR/UI không reset DB, ConfigAdmin, BMS account, string, asset,
calibration event hoặc network configuration.

### 25.17 Process N — failure recovery và report

Mọi exception đi vào `DeploymentEngine.RunAsync()` catch. Nếu SSH connection bị
mất sau mutation, engine thử reconnect restricted identity trước recovery. Khi
restricted transport còn dùng được, engine gọi exact allowlisted operation
`maxiems-deploy-helper diagnostics` trước rollback. Helper chỉ đọc bounded
systemd status, 80 journal entries và IPv4 listener state; output bị chặn ở
32 KiB và đi qua `SecretRedactor` trước khi phát `FailureDiagnostics` progress
event ra terminal. Exception khi thu diagnostics cũng được biến thành một event
ngắn và không thay thế hoặc ngăn rollback.

```text
DeploymentFailureDiagnostics.CollectAsync()
  -> maxiems-deploy-helper diagnostics
  -> redacted terminal progress
  -> always continue

DeploymentFailureRecovery.RecoverAsync()
  +-- restore managed OpenEMS components từ checkpoint nếu đã reconcile
  +-- abort release transaction nếu đang active
  +-- restore previous current link
  +-- restore systemd/nginx files và service states
  +-- remove fresh bootstrap config nếu transaction đã tạo nó
  +-- remove failed new release
```

Database migrations là forward-only và không bị drop/downgrade trong rollback.
Điều này tránh data loss; một backend cũ chỉ được coi là rollback-safe khi schema
forward-compatible theo migration contract.

Cuối cả success và failure:

```text
DeploymentResultWriter.FinishAsync()
  -> DeploymentReportSerializer
  -> DeploymentReportHtmlRenderer
  -> JSON + HTML report
```

Report chứa release ID, phase timing, final state, checks, rollback audit và
redacted error. Secret value, raw PostgreSQL error có credential và private key
không được ghi vào report.

### 25.18 Debug route: lỗi ở đâu thì đọc file nào

| Symptom | Bắt đầu đọc |
|---|---|
| Release build thiếu bundle/stale API | `build-bms-payload.py`, `BMSApp.bndrun` |
| CLI không chạy trên control host | `publish-linux.sh`, `verify-linux-release.sh`, `Cli/Program.cs` |
| SSH fingerprint/key failure | `HostTrustCoordinator`, `SshBootstrapSession`, `SshNetTransportSession` |
| Target preflight fail | `UbuntuPreflightCollector`, `UbuntuPreflightEvaluator` |
| Helper/sudo permission fail | `maxiems-bootstrap-helper`, `maxiems-deployer.sudoers`, `RemoteCommandEncoder` |
| Offline APT fail | `maxiems-prerequisite-ops`, offline cache `Packages` |
| Payload upload/hash/extract fail | `SshReleaseTransactionClient`, `stage_release()` |
| Symlink/current release sai | `activate_release()`, `commit_transaction()`, `abort_transaction()` |
| systemd/nginx config fail | `maxiems-runtime-config-ops`, target unit/site files |
| PostgreSQL/BMS tables fail | `maxiems-bms-setup`, `BmsDatabaseImpl`, schema migration classes |
| `admin/admin` login fail | `PlatformAuthenticationServiceImpl`, `maxiems-bms-setup ready()`, `BmsReadinessProbe` |
| UI mở được nhưng WebSocket fail | nginx `location = /websocket`, frontend backend-settings/BmsConnection |
| Component config lệch | `OpenEmsComponentReconciler`, component manifest |
| Deploy fail nhưng không rollback | `DeploymentFailureRecovery`, `abort_transaction()`, transaction status files |
| Report thiếu/sai/redact leak | `DeploymentRunBuilder`, `DeploymentResultWriter`, `SecretRedactor` |

### 25.19 Boundary khi mở rộng code

Khi thêm deployment function mới, đặt logic theo nơi nó chạy:

```text
Core
  domain contract, state, validation không phụ thuộc I/O

Infrastructure C#
  SSH orchestration, API client, payload verification, rollback/report adapter

remote/usr/local/libexec
  privileged fixed operation trên Ubuntu target

remote/usr/local/sbin
  operator/backend facing device helper như Wi-Fi/WAN

Backend Java
  BMS domain, schema migration, auth và authenticated BMS API

Frontend
  user interaction; không sở hữu deployment state machine
```

Không thêm arbitrary command passthrough. Mỗi privileged operation mới cần:

1. typed/validated arguments ở C# boundary;
2. allowlist trong public helper;
3. root-only internal implementation;
4. sudoers entry hẹp;
5. idempotency hoặc rollback behavior;
6. focused tests và readiness evidence;
7. cập nhật task `prd.md`, `design.md`, `implement.md` và executable spec.

### 25.20 Deploy profile real/demo và active BMS UI

CLI hiện nhận:

```bash
maxiems-deployer deploy ... --deploy-option real
maxiems-deployer deploy ... --deploy-option demo
```

### Deploy-profile bootstrap protocol

`--deploy-option` crosses four versioned payload boundaries and they must remain
aligned:

```text
CLI: bms-setup real|demo
  -> maxiems-deploy-helper validates exactly real|demo
  -> maxiems-deploy-ops forwards the selected value
  -> maxiems-bms-setup setup real|demo
```

`/etc/sudoers.d/maxiems-deployer` grants only these exact commands:

```text
/usr/local/libexec/maxiems-deploy-helper bms-setup real
/usr/local/libexec/maxiems-deploy-helper bms-setup demo
```

It must not grant argument-less `bms-setup` or `bms-setup *`. Local
`ValidateInput` reads the four matching assets from `payload.zip` and rejects an
old/incompatible payload before SSH connection. Therefore a helper/sudoers
protocol change requires `build-bms-release.sh`; `publish-linux.sh` alone cannot
repair it because the bootstrap assets come from the ZIP.

For a managed legacy appliance with database secret but no deployment-profile
marker, the setup helper seeds the selected three bridges only when all three
managed bridge config paths are absent. If any managed bridge path exists, it
preserves every bridge, writes no marker, and stops for an explicit migration.

Nếu bỏ option, profile là `real`. Ở fresh install, helper tạo ba
`Bridge.Modbus.Serial` cùng logical IDs `modbus0`, `modbus1`, `modbus2`:

| Profile | modbus0 | modbus1 | modbus2 |
| --- | --- | --- | --- |
| real | `/dev/ttyS1` | `/dev/ttyS7` | `/dev/ttyS9` |
| demo | `/dev/ttyBMS1` | `/dev/ttyBMS3` | `/dev/ttyBMS5` |

Các bridge dùng 9600, 8 data bits, parity NONE, stop bit ONE. Update không ghi
đè bridge. `/var/lib/maxiems/state/bms/deployment-profile` khóa lựa chọn để một
lần deploy sau không vô tình đổi real thành demo hoặc ngược lại. Demo pair thứ
ba cần simulator `/dev/ttyBMS6`; deployer chỉ sở hữu OpenEMS endpoint.

Payload builder không tin active config từ dist đầu vào. Nó validate
`config/device-config.bms.example.json`, yêu cầu `device.product=bms`, NTP,
Serial & TCP và `connectivity.tcp=true`, rồi ghi profile đó thành active
`ui/config/device-config.json`. Vì vậy appliance chỉ hiển thị BMS navigation và
Serial/TCP page gọi `bms.tools.serial.*` cùng `bms.tools.tcp.*`, không gọi RTS
JSON-RPC/Felix.
