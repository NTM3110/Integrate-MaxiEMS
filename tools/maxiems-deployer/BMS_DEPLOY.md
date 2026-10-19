# Bộ triển khai BMS trên Ubuntu 24.04

Source hiện tại: `Integrate-MaxiEMS/tools/maxiems-deployer`. Pipeline OpenMUC
ở `MergeOpenems/deploy` và `MergeOpenems/releases` đã deprecated.

## Chuẩn bị một gói phát hành

Máy build Linux x64 cần Java 21, Python 3, Docker và .NET SDK theo `global.json`.
Dependency được tải trên máy build; Ubuntu đích không cần Internet.

```bash
cd Integrate-MaxiEMS/tools/maxiems-deployer
./collect-offline-packages.sh arm64 ./artifacts/offline-ubuntu-24.04
./build-bms-release.sh ../../../maxicom-frontend/build \
  ./artifacts/offline-ubuntu-24.04 arm64 ./artifacts/bms-release
```

Đổi `arm64` thành `amd64` cho máy đích Intel/AMD. Collector chạy container amd64
và chỉ tải `.deb` cho kiến trúc yêu cầu, không cần chạy binary ARM trên máy build.
UI là thư mục đã build có `index.html`; script không sửa source frontend.
`build-bms-release.sh` resolve/export **BMSApp** bằng Gradle offline, kiểm thử và
publish CLI Linux self-contained, đóng payload và tạo SHA-256.

```text
bms-release/
├── install.sh              # entrypoint local/SSH; verifies the whole release
├── maxiems-deployer       # CLI + .NET runtime
├── maxiems-local-bootstrap-helper # root-owned only during local install
├── payload.zip           # BMSApp, static UI, helper, unit, nginx, .deb
├── payload.zip.sha256
├── payload.zip.manifest.json
├── release.txt            # release ID, build UTC, Git revision, payloadHash
├── SHA256SUMS
├── BMS_DEPLOY.md
└── LINUX.md
```

Mỗi payload có manifest/hash riêng. Builder đọc danh mục ZIP và class API trong memory để kiểm tra bundle
BMS/database/REST/NTP, không giải nén toàn bộ JAR ra filesystem. Không copy runtime `config-bms` hoặc dữ
liệu trên máy development vào release. Builder từ chối output đã tồn tại.

Release ID có dạng `<build UTC>-<12 ký tự đầu payloadHash>`. `payloadHash` là
SHA-256 canonical của inventory file đã sort và không gồm `manifest.json`, nên
manifest có thể chứa release ID mà không tự làm thay đổi ID. Xem full hash bằng:

```bash
grep '^# payloadHash:' SHA256SUMS
jq '{releaseId, payloadHash, payloadArchiveSha256}' payload.zip.manifest.json
```

`payloadArchiveSha256` và `payload.zip.sha256` là hash byte của ZIP để kiểm tra
truyền tải; chúng không tham gia release ID. Dòng `# payloadHash:` là comment,
nên `sha256sum -c SHA256SUMS` vẫn hoạt động bình thường.
`release.txt` được tạo từ manifest sau khi tính `payloadHash`, và được kiểm tra
bởi `SHA256SUMS`. Trên thiết bị, helper tạo cùng nội dung từ manifest đã xác thực
trong `/opt/maxiems/releases/<id>/release.txt`. File này không nằm trong
inventory dùng để tính release ID, tránh vòng lặp hash. Git revision chỉ là commit
gốc; nếu source có thay đổi chưa commit, `payloadHash` mới nhận diện đúng nội dung.

## Cài lên thiết bị

Mỗi release có cùng một entrypoint và luôn kiểm tra toàn bộ `SHA256SUMS` trước
khi kết nối hoặc thay đổi máy. Từ máy điều khiển, dùng mode `ssh`:

Ubuntu 24.04 cần SSH và tài khoản bootstrap có sudo. Chuẩn bị một SSH key để
installer tạo user deploy giới hạn; private key nằm trên máy điều khiển.

```bash
ssh-keygen -t ed25519 -f ./bms-deploy-key
cd /path/to/bms-release
./install.sh ssh --host 192.168.4.127 --user admin \
  --identity /path/to/bms-deploy-key --deploy-option real
```

Nếu release folder đã được copy nguyên vẹn lên appliance, cài trực tiếp bằng:

```bash
cd /path/to/bms-release
sudo ./install.sh local --deploy-option real --kiosk managed
```

Mode `local` không cần deploy key có sẵn. Nó chỉ dùng `127.0.0.1`, cài
`sudo`/OpenSSH từ package offline trong payload khi thiếu, tạo user/key/sudoers
tạm. Drop-in SSH tạm bắt buộc public key, tắt password login và giới hạn key
ở loopback; script dọn drop-in cùng identity ở mọi đường thoát. Authorized key cũ của `maxiems-deploy` được
khôi phục nguyên trạng; trên máy trắng key tạm bị xóa. Local mode không nhận
`--host`, `--user`, `--identity`, `--payload` hoặc `--require-reboot`.

Hai mode đều tự dùng `payload.zip` cạnh script và gọi cùng `DeploymentEngine`.
Fresh install, update, migration, health check và rollback vì vậy có cùng
semantics; Bash không có pipeline migration riêng.

CLI xác nhận fingerprint, hỏi password SSH/sudo và passphrase bằng input ẩn.
Password quản trị **OpenEMS nội bộ** là bắt buộc và không được để trống; giữ
lại để update. Nó độc lập với tài khoản BMS mới `admin/admin`.
Nếu đồng hồ target lệch máy build quá 5 phút, preflight in cảnh báo và ghi vào
report nhưng vẫn tiếp tục nếu các điều kiện khác đạt. Sau deploy hãy kiểm tra
NTP/timezone trên appliance để timestamp của dữ liệu mới chính xác.

Luồng tự động: preflight → identity/helpers → dependency offline → release
transaction → ConfigAdmin → PostgreSQL → backend migration → start services
→ readiness schema/WebSocket/login → commit. Database mặc định `bms`, role
`maxiems_bms`, host `127.0.0.1:5432`; password role sinh riêng trên thiết bị.
Backend tạo toàn bộ bảng bằng `BmsSchema` và `FeatureSchemaMigration`.

Fresh install tạo BMS `admin/admin`, role `PLATFORM_ADMIN`, dùng PBKDF2 hash/salt.
Không seed string, Modbus bridge, cell, asset profile hoặc calibration event.
Update giữ cấu hình và tài khoản đã đổi password. Database/role có sẵn nhưng
không thuộc installer được từ chối, không tự ý nhận quản lý hoặc reset.

## Layout trên thiết bị

```text
/opt/maxiems/releases/<id>/           # artifact immutable
/opt/maxiems/current                 # symlink release đang chạy
/var/lib/maxiems-deploy/incoming/     # upload riêng, mode 0700
/var/lib/maxiems/shared/config/       # ConfigAdmin dùng chung qua các release
/var/lib/maxiems/state/bms/           # credential DB riêng, root-only
/var/lib/openems/{data,osgi-cache}/
/usr/local/sbin/maxiems-{time,system,network}-helper
/usr/local/sbin/maxiems-{wifi,netcfg}
/etc/sudoers.d/maxiems-deployer
```

Trên thiết bị, xem release đang chạy và cấu trúc file bằng:

```bash
readlink -f /opt/maxiems/current
cat /opt/maxiems/current/release.txt
find "$(readlink -f /opt/maxiems/current)" -maxdepth 2 -type f | sort
ls -lah /opt/maxiems/releases
```

Thư mục release chứa `manifest.json`, `release.txt`, `edge/openems.jar`, `ui/`,
`bootstrap/`, `components/`, `remote/` và `prerequisites/`. Cấu hình, database
và cache runtime nằm ngoài release như sơ đồ trên. Release cũ chưa có
`release.txt` thì đọc `manifest.json` thay thế:

```bash
jq '{releaseId, buildTimestampUtc, gitRevision, payloadHash}' /opt/maxiems/current/manifest.json
```

Máy cũ có `openems` home `/var/lib/maxiems` được chấp nhận; installer giữ nguyên
home đó và không mở rộng quyền thư mục để upload. Dữ liệu được giữ khi rollback
release; schema migration chỉ đi tiến, không tự downgrade/xóa database.

## Kiểm tra và truy cập

```bash
systemctl status maxiems-edge nginx postgresql
journalctl -u maxiems-edge -n 100 --no-pager
sudo -u openems sudo -n /usr/local/sbin/maxiems-time-helper status
```

Static UI qua `http://<device-ip>/`. Felix Web Console nghe
`0.0.0.0:8080`; BMS WebSocket nghe `0.0.0.0:8085` và nginx vẫn proxy tại
`/websocket`. Management REST/JSON-RPC `8084` chỉ nghe loopback.
Giá trị BMS `127.0.0.1` trong UI chọn backend trên thiết bị phục vụ web.
Browser gọi same-origin `/websocket`, nginx nối `127.0.0.1:8085` trên thiết bị;
không cần SSH tunnel. Ví dụ mở `http://192.168.4.127` sẽ dùng
`ws://192.168.4.127/websocket`, dù settings vẫn hiển thị `127.0.0.1`.
Development dùng Vite proxy cùng path, target cấu hình bằng `BMS_EDGE_TARGET`.
Địa chỉ explicit khác loopback giữ kết nối trực tiếp tới cổng backend.
Chức năng REST legacy cần kiểm chứng riêng theo endpoint backend thực tế.

## NTP API

- `bms.tools.time.status`: `{ "token": "..." }`, session hợp lệ.
- `bms.tools.time.configure`: `{ "token": "...", "serverIp": "192.168.127.8" }`,
  role `PLATFORM_ADMIN`; chỉ chấp nhận IPv4/IPv6 literal.
- Response: `configuredServer`, `serverName`, `serverAddress`, `synchronized`,
  `ntpServiceActive`, `timezone`, `serviceState`, `error`; thông tin chưa có là null.

Helper ghi `/etc/systemd/timesyncd.conf.d/90-maxiems.conf`, đặt timezone
`Asia/Ho_Chi_Minh`, bật và restart `systemd-timesyncd`. Deploy không tự đổi NTP
server hiện có. Timedatectl/systemd dùng bản có sẵn trên Ubuntu.

## Chromium kiosk trên màn hình thiết bị

Release BMS quản lý Sway include của user admin tại
/home/admin/.config/sway/config.d/maxiems-kiosk.conf và system service
`maxiems-kiosk.service`. Unit được enable khi boot nhưng chạy bằng user `admin`.
Supervisor chờ Nginx phục vụ static BMS SPA và một socket Wayland an toàn thuộc
`admin`, sau đó mở đúng một Chromium tại http://localhost:80. Khi trang hoặc
Sway session mất, supervisor đóng browser và chờ phục hồi.

Ở mode `managed`, deployer cũng cấu hình `getty@tty1` tự đăng nhập user `admin` và
launcher `/etc/profile.d/90-maxiems-kiosk-sway.sh`. Launcher chỉ start Sway trên
local `/dev/tty1`; SSH session và session đã có `WAYLAND_DISPLAY`/`DISPLAY` không
bị tác động. Nếu tty1 đang có người đăng nhập, cấu hình có hiệu lực sau logout
hoặc reboot. Mode `--kiosk preserve` không thay đổi getty, profile hay Sway.

Cài Sway và Chromium trên thiết bị trước khi deploy; offline cache hiện chưa
chứa chúng. Installer chỉ ghi unit/include khi nội dung đổi. Service active với
unit không đổi không bị restart. Nếu Sway chưa chạy, system service tiếp tục
chờ và tự mở Chromium khi socket Wayland xuất hiện. Launcher Sway cũ chỉ được thay khi nhận diện
đúng command cũ. GNOME entry của MaxiEMS và Firefox autostart cũ chỉ được gỡ
khi nhận diện đúng nội dung; rollback khôi phục chúng.

Kiểm tra trên appliance:

~~~bash
sudo systemctl enable maxiems-kiosk.service
sudo systemctl start maxiems-kiosk.service
sudo systemctl status maxiems-kiosk.service
sudo systemctl status getty@tty1.service
sudo journalctl -u maxiems-kiosk.service -n 80 --no-pager
sudo journalctl -u getty@tty1.service -n 40 --no-pager
cat ~/.config/sway/config.d/maxiems-kiosk.conf
grep maxiems-kiosk.conf ~/.config/sway/config
curl -I http://localhost:80/
~~~

### Bàn phím cảm ứng

Full release chứa `helpers/maxiems-keyboard-controller` và binary ARM64
`helpers/wvkbd-mobintl`. Sway tự start controller; Edge chỉ gửi `normal`,
`dialer` hoặc `hide` qua `/usr/local/sbin/maxiems-keyboard`. Kiểm tra bằng:

~~~bash
ls -l /opt/maxiems/current/helpers/{maxiems-keyboard-controller,wvkbd-mobintl}
sudo -u admin test -p /var/lib/maxiems/kbd/kbd-command
sudo -u openems sudo -n /usr/local/sbin/maxiems-keyboard normal
pgrep -a -u admin 'maxiems-keyboard-controller|wvkbd-mobintl'
~~~

Managed mode tạo `/var/lib/maxiems/kbd` với `root:openems 0770` và thêm `admin`
vào group `openems`; cần logout/reboot để session đã chạy nhận membership mới.
System kiosk giữ `PrivateTmp=true` nhưng không đặt `NoNewPrivileges`, vì Snap
Chromium cần privilege transition của launcher trước khi chạy dưới user `admin`.

Nếu vừa hotfix asset cho một Sway session đã chạy, reboot hoặc logout tty1 để
Sway đọc lại lệnh `exec` trong include. Focus input text/password mở layout
normal; input number/tel/inputmode numeric mở dialer.

## Kết quả kiểm thử

Test tự động Java/API/helper, CLI, Core/Infrastructure và builder chạy riêng.
Các test PowerShell packaging cũ cần Windows; Linux dùng test Python builder.
Readiness deployment kiểm tra schema1–8 và login/token/role/logout khi password
mặc định còn được giữ; update password đã đổi không thử/reset `admin/admin`.
Chỉ đánh dấu appliance đạt khi có report `Succeeded`; `RolledBack` không phải
cài đặt thành công. Chưa thử đổi WAN, NTP server hoặc reboot thì ghi rõ trong
biên bản thiết bị, không suy diễn từ unit test.

## Bằng chứng thử nghiệm 2026-10-01

Trên Ubuntu24.04 ARM64 `192.168.4.127`, release
`20260930033422-df55dca98fbb` đạt `Succeeded`. Backend/nginx/PostgreSQL active,
UI HTTP200, schema1–8, BMS admin login/token/logout và string list rỗng đạt.
NTP status qua nginx trả synchronized=true, error=null. Giữ server/timezone cũ;
chưa thử configure NTP/WAN/Wi-Fi hoặc reboot thiết bị. Frontend source không sửa.
