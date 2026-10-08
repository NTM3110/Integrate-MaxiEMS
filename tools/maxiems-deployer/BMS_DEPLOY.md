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
├── maxiems-deployer       # CLI + .NET runtime
├── payload.zip           # BMSApp, static UI, helper, unit, nginx, .deb
├── payload.zip.sha256
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

## Cài lên thiết bị

Ubuntu 24.04 cần SSH và tài khoản bootstrap có sudo. Chuẩn bị một SSH key để
installer tạo user deploy giới hạn; private key nằm trên máy điều khiển.

```bash
ssh-keygen -t ed25519 -f ./bms-deploy-key
cd /path/to/bms-release
sha256sum -c SHA256SUMS
./maxiems-deployer deploy --host 192.168.4.127 --user admin \
  --payload ./payload.zip --identity /path/to/bms-deploy-key
```

CLI xác nhận fingerprint, hỏi password SSH/sudo và passphrase bằng input ẩn.
Password quản trị **OpenEMS nội bộ** là bắt buộc và không được để trống; giữ
lại để update. Nó độc lập với tài khoản BMS mới `admin/admin`.

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
/usr/local/libexec/maxiems-{time,system}-helper
/usr/local/sbin/maxiems-{wifi,netcfg}
/etc/sudoers.d/maxiems-deployer
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
