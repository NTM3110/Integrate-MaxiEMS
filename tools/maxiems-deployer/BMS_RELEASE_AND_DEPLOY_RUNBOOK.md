# Tạo release và deploy OpenEMS BMS

Quy trình này chạy từ máy build/control Linux x64 tới Ubuntu 24.04 appliance.
Deployer trong thư mục chứa file này là source of truth. Không dùng pipeline
OpenMUC cũ trong `deploy/`, `releases/` hoặc `scripts/create-deploy-release.sh`.

Ví dụ dùng target hiện tại: Ubuntu 24.04 ARM64 tại `192.168.4.127`.

## 1. Khai báo thông tin

Chạy trên máy build/control:

```bash
export WORKSPACE='/home/shou/Documents/ATEnergy/MergeOpenems'
export DEPLOYER="$WORKSPACE/Integrate-MaxiEMS/tools/maxiems-deployer"
export UI_DIST="$WORKSPACE/maxicom-frontend/build"
export OFFLINE_CACHE="$DEPLOYER/artifacts/offline-ubuntu-24.04"

export TARGET_HOST='192.168.4.127'
export TARGET_PORT='22'
export BOOTSTRAP_USER='<linux-ssh-user>'
export DEPLOY_KEY="$HOME/.local/share/maxiems-deployer/keys/bms-deploy-key"
```

Thay `<linux-ssh-user>` bằng tài khoản Linux thật trên appliance. Kiểm tra tài
khoản đó trên appliance:

```bash
id
sudo -v
sudo /usr/bin/true
```

Ba loại credential khác nhau:

| Credential | Mục đích |
| --- | --- |
| `BOOTSTRAP_USER` | Linux account có sẵn, SSH được và có `sudo` |
| OpenEMS management password | CLI dùng để quản trị OpenEMS; bắt buộc và không được để trống |
| BMS `admin/admin` | Tài khoản BMS được tạo trên fresh install |

`maxiems-deploy` là restricted Linux account do deployer tự tạo. Operator không
cần tạo account này trước.

Máy build cần Java 21, Node.js/npm, Python 3, `dpkg-deb`, `jq`, `unzip`, SSH
client và .NET SDK theo `Integrate-MaxiEMS/global.json`. Docker chỉ cần khi tạo
lại offline package cache.

## 2. Build frontend BMS

`maxicom-frontend` là Git repository riêng:

```bash
cd "$WORKSPACE/maxicom-frontend"
npm ci
npm run check
npm run build

test -f "$UI_DIST/index.html"
test -f "$UI_DIST/config/device-config.bms.example.json"
```

Release builder dùng BMS example làm active `ui/config/device-config.json`.
Profile này chỉ hiện các trang BMS, có `NTP Server`, `Serial & TCP` và bật TCP.

## 3. Kiểm tra hoặc tạo offline package cache

Cache có dạng:

```text
artifacts/offline-ubuntu-24.04/
└── arm64/
    ├── *.deb
    └── SHA256SUMS
```

Cache là input riêng vì nó chứa dependency Ubuntu đúng architecture. Có thể
dùng lại cùng cache cho nhiều backend/frontend release và target không cần
Internet.

Kiểm tra cache hiện có:

```bash
cd "$OFFLINE_CACHE/arm64"
sha256sum -c SHA256SUMS
```

Chỉ tiếp tục khi tất cả checksum đều `OK`.

Nếu cần cache mới, dùng output root mới vì collector từ chối trộn package:

```bash
cd "$DEPLOYER"

./collect-offline-packages.sh \
  arm64 \
  ./artifacts/offline-ubuntu-24.04-new
```

Collector chạy Ubuntu 24.04 container và chỉ tải `.deb`. Docker chạy trên máy
build, không chạy trên appliance. Dùng `amd64` thay `arm64` cho target Intel/AMD.

Nếu dùng cache mới:

```bash
export OFFLINE_CACHE="$DEPLOYER/artifacts/offline-ubuntu-24.04-new"
```

## 4. Tạo release

> Nếu thay đổi deploy helper, `maxiems-bms-setup`, sudoers hoặc protocol
> `--deploy-option`, bắt buộc tạo **full release** bằng bước này. Không dùng
> `publish-linux.sh` rồi ghép với `payload.zip` cũ: bootstrap assets được đọc từ
> chính payload, không nằm trong CLI binary.

Builder nhận đúng bốn argument:

```text
UI_DIST OFFLINE_PACKAGES_ROOT amd64|arm64 NEW_RELEASE_DIRECTORY
```

Argument thứ hai là cache root chứa `arm64/` hoặc `amd64/`. Argument thứ tư
phải là path mới, chưa tồn tại.

```bash
cd "$DEPLOYER"

export RELEASE_DIR="$DEPLOYER/artifacts/bms-ubuntu24.04-arm64-$(date -u +%Y%m%d%H%M%S)"

./build-bms-release.sh \
  "$UI_DIST" \
  "$OFFLINE_CACHE" \
  arm64 \
  "$RELEASE_DIR"
```

Builder build deployer, BMS service/API và BMSApp, rồi tạo `payload.zip` và
checksum. Nó không giải nén toàn bộ `BMSApp.jar`.

## 5. Kiểm tra release

```bash
cd "$RELEASE_DIR"

sha256sum -c SHA256SUMS
grep '^# payloadHash:' SHA256SUMS
./maxiems-deployer --help

jq '{releaseId, buildTimestampUtc, sourceTimestampUtc, gitRevision,
     payloadHash, payloadArchiveSha256}' payload.zip.manifest.json

sha256sum -c payload.zip.sha256

unzip -p payload.zip ui/config/device-config.json | \
  jq '{product: .device.product, navigation: .navigation.enabledItems, tcp: .connectivity.tcp}'
```

Kết quả cần có:

- checksum đều `OK`;
- `releaseId` có dạng `<build UTC>-<12 ký tự đầu payloadHash>`;
- dòng `# payloadHash: ...` trong `SHA256SUMS` khớp `payloadHash` ở sidecar;
- `payloadArchiveSha256` khớp trực tiếp hash trong `payload.zip.sha256`;
- target là Ubuntu `24.04`, architecture `arm64`;
- product là `bms`;
- navigation có `ntp-server` và `serial-tcp`;
- `connectivity.tcp` là `true`.

Release directory phải có executable `install.sh`, `maxiems-deployer`,
`maxiems-local-bootstrap-helper`, `payload.zip`, `payload.zip.sha256`,
`SHA256SUMS`, `release.txt`, `BMS_DEPLOY.md` và `LINUX.md`. Tất cả các file này
được liệt kê trong `SHA256SUMS`.

### Hai cách chạy cùng một release

Từ máy điều khiển qua SSH:

```bash
cd "$RELEASE_DIR"
./install.sh ssh \
  --host "$TARGET_HOST" \
  --port "$TARGET_PORT" \
  --user "$BOOTSTRAP_USER" \
  --identity "$DEPLOY_KEY" \
  --deploy-option real \
  --kiosk managed
```

Nếu đã copy nguyên release folder lên appliance, không cần tạo deploy key trước:

```bash
cd /path/to/copied-release
sudo ./install.sh local --deploy-option real --kiosk managed
```

`install.sh` resolve mọi artifact theo chính thư mục chứa script, nên có thể gọi
từ current working directory khác. Nó kiểm tra toàn bộ `SHA256SUMS` trước khi
SSH hoặc mutate target và từ chối `--payload`/`--payload=...`; operator không
thể ghép script/CLI mới với payload khác.

Mode `local` cài phần tối thiểu `sudo`/OpenSSH từ repository `.deb` đã verify
trong payload nếu image chưa có, sau đó tạo bootstrap user, khóa key bằng
`from="127.0.0.1,::1",restrict`, và dùng sudoers ngắn hạn. Sudoers chỉ cho
`/usr/bin/true` và helper root-owned cố định tại
`/run/maxiems-local-bootstrap-helper`; source staging vẫn bị giới hạn bởi format
`/tmp/maxiems-bootstrap-<32 hex>`. Trap dọn user, private/public key, helper và
sudoers khi thành công, lỗi hoặc nhận signal. Drop-in OpenSSH tạm bắt buộc
public-key authentication và tắt password/keyboard-interactive cho user này;
key chỉ được dùng từ loopback. Trap cũng xóa drop-in rồi reload SSH. Nếu
`maxiems-deploy` đã có
`authorized_keys`, script backup và restore file đó; máy trắng không giữ public
key tạm. `--local-bootstrap` là option nội bộ của CLI và bị từ chối nếu host
không phải loopback IP literal.

Không dùng `--require-reboot` với mode local vì reboot sẽ giết chính tiến trình
chịu trách nhiệm cleanup. Hãy hoàn tất local install, reboot riêng, rồi chạy
health/status theo mục kiểm tra. Cả local và SSH vẫn gọi cùng
`DeploymentEngine`; phân biệt fresh/update, database migration, transaction,
health check và rollback không nằm trong Bash.

## 6. Tạo deploy key và hiểu first deploy flow

Tạo key trên máy build/control, ngoài repository:

```bash
install -d -m 700 -- "$(dirname -- "$DEPLOY_KEY")"

ssh-keygen \
  -t ed25519 \
  -f "$DEPLOY_KEY" \
  -C 'maxiems-deployer'

chmod 600 -- "$DEPLOY_KEY"
chmod 644 -- "$DEPLOY_KEY.pub"
```

Lệnh `ssh-keygen` **chỉ tạo local key pair**:

```text
$DEPLOY_KEY       private key, luôn ở máy build
$DEPLOY_KEY.pub   public key, chưa được cài lên appliance
```

Nó không tạo user trên appliance và không copy `.pub` lên target.

Password nhập trong lúc chạy `ssh-keygen` là **private-key passphrase**. Nó chỉ
mã hóa `$DEPLOY_KEY` trên máy build; nó không phải password SSH của appliance
và không làm target tự chấp nhận key.

`--identity "$DEPLOY_KEY"` chỉ cho deployer biết key nào sẽ dùng. Ở first
deploy, deployer vẫn cần password Linux hiện có của `BOOTSTRAP_USER` để đăng
nhập target, sau đó nó tự cài `$DEPLOY_KEY.pub`. Từ lần sau mới có thể đăng nhập
bằng key đã cài.

First deploy flow thực tế:

```text
1. Tạo local key pair.
2. Truyền private-key path qua --identity.
3. Nhập password SSH/sudo hiện có của BOOTSTRAP_USER một lần.
4. Deployer thêm DEPLOY_KEY.pub vào authorized_keys.
5. Deployer tạo restricted user maxiems-deploy và cài public key cho user này.
6. Deployer đóng bootstrap connection rồi reconnect bằng private key.
```

Vì vậy first deploy vẫn cần cả `$DEPLOY_KEY` và `$DEPLOY_KEY.pub`, nhưng operator
không phải chạy `ssh-copy-id` trước.

Kiểm tra local key:

```bash
test -f "$DEPLOY_KEY"
test -f "$DEPLOY_KEY.pub"
test "$(stat -c '%a' "$DEPLOY_KEY")" = '600'
```

## 7. Bootstrap authentication lần đầu và các lần sau

### Lần đầu: SSH password và sudo cùng password

Đây là flow đơn giản nhất. Test trước:

```bash
ssh \
  -o IdentitiesOnly=yes \
  -o PubkeyAuthentication=no \
  -o PasswordAuthentication=yes \
  -o KbdInteractiveAuthentication=no \
  -p "$TARGET_PORT" \
  "$BOOTSTRAP_USER@$TARGET_HOST"
```

Trong phiên SSH:

```bash
sudo -v
sudo /usr/bin/true
```

Khi deployer hỏi bootstrap password, nhập password này. CLI dùng cùng password
cho SSH và sudo.

### Các lần sau: key và passwordless sudo

Có thể để bootstrap password trống chỉ khi cả hai lệnh sau thành công:

```bash
ssh \
  -o IdentitiesOnly=yes \
  -i "$DEPLOY_KEY" \
  -p "$TARGET_PORT" \
  "$BOOTSTRAP_USER@$TARGET_HOST"

sudo -n /usr/bin/true
```

Nếu key login thành công nhưng `sudo -n` thất bại, tiếp tục nhập bootstrap
password như first deploy. CLI hiện không hỗ trợ tổ hợp SSH bằng key nhưng sudo
vẫn hỏi password.

## 8. Xác minh SSH host fingerprint

Trên máy build, lấy candidate fingerprint:

```bash
SCAN_FILE="$(mktemp)"
ssh-keyscan -p "$TARGET_PORT" -- "$TARGET_HOST" \
  2>/dev/null > "$SCAN_FILE"
ssh-keygen -lf "$SCAN_FILE" -E sha256
rm -f -- "$SCAN_FILE"
```

Trên console appliance hoặc kênh quản trị đã tin cậy:

```bash
for key in /etc/ssh/ssh_host_*_key.pub; do
  sudo ssh-keygen -lf "$key" -E sha256
done
```

Khi CLI in endpoint, thuật toán và `SHA256:...`, so sánh với kết quả từ
appliance. Nếu chúng khớp, trả lời prompt đơn giản:

```text
Trust this host key? [y/N]: yes
```

CLI chấp nhận `y` hoặc `yes`, không phân biệt chữ hoa/chữ thường. Enter, `n`,
`no` hoặc mọi nội dung khác đều hủy trước khi pin key hoặc mutate target. Khi
xác nhận, CLI pin chính fingerprint vừa probe; không cần gõ lại fingerprint.

CLI pin host key tại:

```text
${XDG_DATA_HOME:-$HOME/.local/share}/maxiems-deployer/known-hosts.json
```

CLI không dùng `~/.ssh/known_hosts`. Nếu key đã pin thay đổi, CLI dừng trước khi
mutate target. Xác minh nguyên nhân và key mới trước khi sửa trust record.

## 9. Deploy `real` hoặc `demo`

### Production: `real`

```bash
cd "$RELEASE_DIR"

./maxiems-deployer deploy \
  --host "$TARGET_HOST" \
  --port "$TARGET_PORT" \
  --user "$BOOTSTRAP_USER" \
  --payload ./payload.zip \
  --identity "$DEPLOY_KEY" \
  --deploy-option real
```

Fresh `real` mapping:

| Bridge | Device |
| --- | --- |
| `modbus0` | `/dev/ttyS1` |
| `modbus1` | `/dev/ttyS7` |
| `modbus2` | `/dev/ttyS9` |

### Demo: `demo`

```bash
cd "$RELEASE_DIR"

./maxiems-deployer deploy \
  --host "$TARGET_HOST" \
  --port "$TARGET_PORT" \
  --user "$BOOTSTRAP_USER" \
  --payload ./payload.zip \
  --identity "$DEPLOY_KEY" \
  --deploy-option demo
```

### Kiosk trên thiết bị đã có giao diện tự khởi động

Mặc định `--kiosk managed`: deployer cài, enable và start system service
`maxiems-kiosk.service` bằng systemd, nhưng Chromium chạy với user `admin`.
Service tự chờ Sway/Wayland rồi mở `http://localhost:80`. Operator kiểm tra bằng
`sudo systemctl status maxiems-kiosk.service`. Dùng `--kiosk preserve` nếu thiết bị
đã có kiosk riêng và bạn muốn giữ nguyên service, autostart và cấu hình Sway/GNOME
hiện tại. Chế độ này vẫn cập nhật Edge, Nginx và WebSocket của BMS.

`managed` còn bật autologin `admin` riêng cho local `tty1` và start Sway từ
`/etc/profile.d/90-maxiems-kiosk-sway.sh`. Guard của launcher loại SSH, TTY khác
`/dev/tty1`, và session đã có display. Kiểm tra boot path bằng
`sudo systemctl status getty@tty1.service` và
`sudo journalctl -u getty@tty1.service -n 40 --no-pager`.

Full release cũng phải có `helpers/maxiems-keyboard-controller` và ARM64
`helpers/wvkbd-mobintl`. Managed kiosk thêm `admin` vào group `openems`, tạo
`/var/lib/maxiems/kbd` với `root:openems 0770` trước khi Sway start controller,
và bootstrap cài constrained helper `/usr/local/sbin/maxiems-keyboard`
với ba sudo action exact. Sau deploy/reboot, focus input text/password phải mở
normal layout; number/tel/inputmode numeric phải mở dialer.

`maxiems-kiosk.service` giữ `PrivateTmp=true` nhưng không dùng
`NoNewPrivileges`; Snap Chromium cần privilege transition của launcher/user
namespace rồi browser vẫn chạy dưới `User=admin`. Supervisor ưu tiên
`/snap/bin/chromium` khi có.

```bash
cd "$RELEASE_DIR"
./maxiems-deployer deploy \
  --host "$TARGET_HOST" \
  --port "$TARGET_PORT" \
  --user "$BOOTSTRAP_USER" \
  --payload ./payload.zip \
  --identity "$DEPLOY_KEY" \
  --deploy-option demo \
  --kiosk preserve
```

Với thiết bị cần MAXiEMS quản lý Chromium, thêm `--kiosk managed` hoặc bỏ hẳn
tham số `--kiosk`. Lựa chọn kiosk độc lập với `--deploy-option real|demo`.
Report deploy ghi lựa chọn tại check `kiosk-mode`. `preserve` không kiểm tra một
kiosk tự tạo có hoạt động hay không; operator kiểm tra nó sau deploy. Nếu kiosk
riêng trỏ vào `/opt/maxiems/current`, lần tự khởi động tiếp theo của nó có thể
đọc UI từ release mới.

CLI có `--kiosk` yêu cầu payload mới chứa helper protocol tương ứng. Hãy build
full release; dùng CLI mới với `payload.zip` cũ sẽ bị chặn trước khi SSH.

Fresh `demo` mapping:

| Bridge | OpenEMS | Simulator |
| --- | --- | --- |
| `modbus0` | `/dev/ttyBMS1` | `/dev/ttyBMS2` |
| `modbus1` | `/dev/ttyBMS3` | `/dev/ttyBMS4` |
| `modbus2` | `/dev/ttyBMS5` | `/dev/ttyBMS6` |

Simulator mới có hai pair đầu. Deployer seed `/dev/ttyBMS5`, nhưng simulator
process/dataset cho `/dev/ttyBMS6` vẫn chưa triển khai. Bridge thứ ba chưa tạo
demo telemetry.

CLI hỏi ba secret theo thứ tự:

1. Bootstrap SSH/sudo password: nhập cho first deploy; có thể để trống nếu key
   login và `sudo -n` đều hoạt động.
2. Private-key passphrase: nhập passphrase của `$DEPLOY_KEY`, hoặc để trống.
3. OpenEMS management password: fresh install dùng password mới không rỗng;
   update dùng password hiện tại. Đây không phải BMS `admin/admin`.

CLI cần terminal tương tác. Không đưa secret vào command line, pipe, log hoặc
repository.

## 10. Fresh install và update

| Trạng thái | Hành vi |
| --- | --- |
| Fresh | Tạo managed database, BMS `admin/admin`, ConfigAdmin và ba bridge theo profile |
| Update đã có profile marker | Giữ database, account, BMS data, serial/TCP bridge, WAN/Wi-Fi và NTP config |
| Legacy managed, chưa có marker và cả ba bridge managed đều vắng | Seed ba bridge theo profile được chọn rồi ghi marker |
| Legacy managed, chưa có marker và có bất kỳ bridge managed nào | Dừng và yêu cầu migration; không ghi đè bridge hoặc ghi marker |
| Đổi `real` ↔ `demo` | Bị từ chối trên appliance đã managed |

Profile được khóa tại:

```text
/var/lib/maxiems/state/bms/deployment-profile
```

Không xóa marker để ép đổi profile. Việc đổi topology cần migration riêng.

CLI kiểm tra protocol trong `payload.zip` ngay tại `ValidateInput`. Payload cũ
chỉ có sudoers `bms-setup` không argument sẽ dừng local với thông báo phải build
full release, trước khi SSH hoặc thay đổi target.

Deploy cài NTP helper nhưng không đổi NTP server. NTP chỉ thay đổi qua
`bms.tools.time.configure`.

## 11. Kiểm tra sau deploy

Chỉ coi thành công khi CLI trả `Succeeded`. `RolledBack` nghĩa là release mới
không được commit. Giữ JSON và HTML report mà CLI in ở cuối. Mặc định chúng ở:

```text
${XDG_DATA_HOME:-$HOME/.local/share}/maxiems-deployer/reports/
```

Kiểm tra appliance:

```bash
ssh -p "$TARGET_PORT" "$BOOTSTRAP_USER@$TARGET_HOST"

sudo systemctl status maxiems-edge nginx postgresql --no-pager
sudo journalctl -u maxiems-edge -n 100 --no-pager
readlink -f /opt/maxiems/current
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/
sudo -u openems sudo -n /usr/local/sbin/maxiems-time-helper status
sudo ss -ltnp | grep -E ':(8080|8084|8085)\\b'
```

Mở `http://192.168.4.127` và xác nhận:

1. Sidebar chỉ có các trang BMS.
2. `NTP Server` load được status.
3. `Serial & TCP` không báo lỗi RTS JSON-RPC.
4. Serial list và TCP tab load được.
5. Ba bridge khớp profile đã chọn.

Frontend dùng `ws://192.168.4.127/websocket`; nginx proxy tới
`127.0.0.1:8085`.

Runtime mặc định phải có các binding sau:

```text
0.0.0.0:8080    Felix Web Console
127.0.0.1:8084  OpenEMS management REST/JSON-RPC
0.0.0.0:8085    BMS WebSocket
```

Mở Felix tại `http://192.168.4.127:8080/system/console`. Update từ release cũ
chỉ đổi `Controller/Api/Websocket/maxiems.config` khi file đó vẫn đúng shape do
deployer tạo, có `id="ctrlApiWebsocket0"` và legacy bind `127.0.0.1`. Component
WebSocket custom hoặc file khác không bị sửa.

Nếu một phase fail sau khi restricted SSH đã kết nối, CLI tự in block
`FailureDiagnostics: Running` **trước** dòng `Rollback`. Block này có tối đa
32 KiB và gồm:

- listener của đúng các cổng `8080`, `8085`, `8084`, `502` ở đầu block;
- `systemctl status maxiems-edge.service` tối đa 30 dòng;
- 80 dòng journal gần nhất của `maxiems-edge.service`;
- tối đa 100 TCP listener từ `ss`, gồm cả IPv4 và IPv6.

Output đi qua cùng secret redactor với progress/report. Nếu helper diagnostics
cũng fail, CLI in lỗi diagnostics ngắn rồi vẫn rollback; không cần mở terminal
thứ hai để lấy log trong lúc transaction còn active.

### Giữ release mới để kiểm tra khi `HealthCheck` fail

Tạo release mới chứa CLI và payload đã cập nhật. Khi gọi `deploy`, thêm
`--keep-failed-release` vào cuối command ở mục 9. Tùy chọn này chỉ giữ release
khi lỗi thuộc `HealthCheck` sau khi transaction đã bắt đầu. CLI vẫn in
`FailureDiagnostics`, kết thúc với trạng thái `Failed` và exit code 1. Release
mới tiếp tục chạy; transaction lock còn mở để giữ khả năng rollback. Những lỗi
ở phase khác vẫn rollback như thường lệ.

Ví dụ với release dùng để inspect và profile demo:

```bash
cd "$DEPLOYER/artifacts/bms-ubuntu24.04-arm64-20261005081656-healthcheck"
./maxiems-deployer deploy \
  --host 192.168.4.127 \
  --user admin \
  --payload ./payload.zip \
  --identity "$DEPLOY_KEY" \
  --deploy-option demo \
  --keep-failed-release
```

Trên target, xem release và trạng thái app:

```bash
readlink -f /opt/maxiems/current
sudo systemctl status maxiems-edge.service --no-pager
sudo journalctl -u maxiems-edge.service -n 100 --no-pager
sudo ss -4 -lntp | grep -E ':(8080|8084|8085)\\b'
```

Lấy release ID từ report của lần deploy `Failed`. Khi đã kiểm tra xong, trên
target chạy rollback transaction đó trước lần deploy kế tiếp:

```bash
cd /
sudo /usr/local/libexec/maxiems-deploy-helper transaction-status <RELEASE_ID>
sudo /usr/local/libexec/maxiems-deploy-helper rollback <RELEASE_ID>
```

Không coi release giữ lại là release đạt health check. Không chạy deployment
tiếp theo khi transaction lock còn mở.
Nếu shell đang đứng trong `/opt/maxiems/current`, rollback có thể xóa thư mục
đang làm working directory và `systemctl`/SysV in `getcwd() failed` dù rollback
vẫn tiếp tục. Sau lệnh, kiểm tra `transaction-status` là `rolled-back`, lock
`/run/maxiems-deploy-transaction/id` không còn và `current` trỏ release cũ.

Các read method cần valid BMS token:

```text
bms.tools.time.status({token})
bms.tools.serial.list({token})
bms.tools.tcp.list({token})
```

Các mutation method:

```text
bms.tools.time.configure({token, serverIp})
bms.tools.serial.update(...)
bms.tools.tcp.create(...)
bms.tools.tcp.update(...)
bms.tools.tcp.delete(...)
```

Time configure và TCP mutations yêu cầu `PLATFORM_ADMIN`. Chỉ gọi khi test plan
cho phép. Sau configure NTP, reload `bms.tools.time.status`; không tự coi
configure response là synchronized.

## 12. Troubleshooting nhanh

| Lỗi | Cách xử lý |
| --- | --- |
| Release directory đã tồn tại | Chọn `RELEASE_DIR` mới; builder không overwrite |
| Cache output không rỗng | Dùng cache root mới; không trộn package version |
| Checksum/architecture sai | Collect lại đúng `arm64` hoặc `amd64` |
| Stale BMS API bundle | Chạy lại toàn bộ `build-bms-release.sh` |
| CLI cần interactive terminal | Chạy trực tiếp; không pipe secret hoặc redirect stdin |
| SSH/sudo thất bại | Test lại first/subsequent flow ở bước 7 |
| SSH host key changed | Dừng và xác minh fingerprint mới qua kênh tin cậy |
| Deploy option mismatch | Dùng profile đã chọn lúc fresh install |
| `RolledBack`/`Failed` | Đọc block `FailureDiagnostics` ngay trước `Rollback`, rồi mở report để đối chiếu phase đầu tiên lỗi |
| `port 8080/8085 is not listening` dù `ss` hiện `*:PORT` | Payload cũ lọc `ss -4` và bỏ sót dual-stack wildcard. Build payload mới; helper đọc `ss` cả hai family rồi thử TCP IPv4 tới port đó. |
| UI thiếu NTP/Serial & TCP | Kiểm tra active config trong `payload.zip` theo bước 5 |
| NTP không đổi sau deploy | Đây là expected; cấu hình qua page/API time configure |

Quy tắc cuối:

- Không giải nén toàn bộ `BMSApp.jar`.
- Không commit key, password, report nhạy cảm, cache hoặc release artifact.
- Không sửa, stage hoặc commit file ngoài scope.
- Dùng output directory mới cho mỗi release và giữ report theo release ID.


## Kiểm tra nhanh khi chỉ sửa deployer (2026-10-05)

Chạy test đúng project và filter; script compile source hiện tại bằng incremental
build, mặc định không restore và không load WPF hoặc build Java/frontend:

```bash
cd "$DEPLOYER"
./verify-deployer-change.sh infrastructure 'FullyQualifiedName~DeploymentFailureDiagnosticsTests'
```

Khi dependency thay đổi hoặc thiếu assets, thêm `--restore`. Restore giữ locked
packages và NuGet audit; không tắt audit để che lỗi network. Test runner cần
local IPC socket; khi chạy qua agent, dùng quyền thích hợp ngay từ đầu.

Khi chỉ sửa C#/helper/systemd, có thể đóng payload mới từ BMS JAR đã build:

```bash
new_bms_release_dir
cd "$DEPLOYER"
./build-bms-release.sh "$FE_BUILD" "$OFFLINE_CACHE" arm64 "$RELEASE_DIR" --reuse-backend
```

Option này vẫn publish CLI, đóng helper hiện tại và tạo manifest/checksum mới.
Nó không build lại Java. Chỉ dùng khi JAR đã chứa mọi thay đổi backend cần thiết;
khi sửa Java hoặc `BMSApp.bndrun`, dùng lệnh mặc định không có option này.
Frontend luôn lấy từ thư mục build đầu vào; nếu sửa frontend phải build UI trước.
