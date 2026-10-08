# Khởi tạo BMS trên Ubuntu 24.04

Release chứa dependencies `.deb` trong
`prerequisites/ubuntu-24.04/<amd64|arm64>/`. Bootstrap kiểm tra SHA-256 của từng
package sau khi upload qua SSH, rồi cài bằng `apt-get --no-download`.
Không cần Internet tại thiết bị; package thiếu dependency làm deployment thất
bại. Không cài Node.js vì UI đã build thành tài nguyên tĩnh. Tailscale chỉ dùng
khi đã có package tương ứng và operator yêu cầu cấu hình.

## Thứ tự và dữ liệu

1. Tạo identity `maxiems-deploy`, service account `openems`, cài root-owned
   helper và sudoers đã qua `visudo`.
2. Cài Java 21, nginx, PostgreSQL, NetworkManager và các công cụ cần thiết từ
   dependencies offline.
3. Activate release, kiểm tra unit/nginx; tạo management ConfigAdmin lần đầu.
4. `maxiems-bms-setup setup` tạo database `bms` tại `127.0.0.1:5432`, owner/role
   `maxiems_bms` với mật khẩu ngẫu nhiên. Secret giữ tại
   `/var/lib/maxiems/state/bms/database.json` (root, `0600`). BMS connection config
   được tạo tại `/var/lib/maxiems/shared/config` (openems, `0640`).
5. Tạo core/scheduler và bootstrap BMS `admin/admin`. Không tạo bridge,
   battery string, cell, calculator hoặc calibration event.
6. Backend khởi động và chạy `BmsSchema`/`FeatureSchemaMigration`; installer
   không chứa bản sao SQL tạo bảng. Backend tạo salted PBKDF2 hash cho admin.
7. `bms-ready` chờ schema versions 1–8 và một `PLATFORM_ADMIN`; lần đầu kiểm tra
   PBKDF2 hash của `admin/admin`. Management REST và BMS API có gate riêng trong
   deployment engine.

## Cài lại và rollback

ConfigAdmin đã tồn tại không bị ghi đè; password BMS đã đổi được giữ lại.
Database/role chỉ được quản lý khi có credential state của installer. Nếu tìm
thấy database/role cùng tên hoặc BMS database config trước lần provisioning đầu,
installer dừng để tránh chiếm quyền hoặc reset dữ liệu có sẵn. Việc chuyển một
cài đặt thủ công sang pipeline này cần migration rõ ràng.

Rollback release không xóa database và không hạ cấp schema. Chỉ quay về phiên
bản Backend tương thích với schema đã migrate. Không dùng helper này để reset
password hoặc xóa dữ liệu.

## Quyền hệ điều hành

Java chạy bằng `openems`, có group `dialout`. Các action Wi-Fi, WAN, NTP, reboot
đi qua root-owned helper và command allowlist trong sudoers. Unit không bật
`NoNewPrivileges`/seccomp hoặc capability bounding làm mất khả năng gọi `sudo`.
Giữ `ProtectHome`, `PrivateTmp`, `ProtectKernelTunables`, `ProtectControlGroups`
và release read-only. Cần kiểm tra thực tế các helper dưới service unit trên
appliance; test mock không xác nhận quyền trong systemd namespace.
