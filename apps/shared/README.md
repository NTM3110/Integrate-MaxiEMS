# Thành phần dùng chung

| Thư mục | Mã nguồn hiện có |
|---|---|
| `serial/` | `io.openems.edge.shared.serial`: repository cấu hình serial được tách khỏi BMS Core |
| `tcp/` | Vị trí dành cho dịch vụ cấu hình TCP dùng chung; chưa có dịch vụ riêng để chuyển |
| `ethernet/` | `io.openems.edge.shared.network` (NetworkManager của BMS) và `io.openems.edge.network.manager` (backend EMS hiện có) |
| `lte/` | `io.openems.edge.modem.4g` |
| `system/` | `io.openems.edge.shared.system` (host reboot) và `io.openems.edge.config.manager` (import/export OSGi config hiện có) |

Ba bundle mới `shared.serial`, `shared.network`, `shared.system` không phụ thuộc
vào bundle BMS. Java package cũ `io.openems.edge.bms.core.*` vẫn được export từ
chính các bundle mới để tương thích. BMS API khai báo phụ thuộc trực tiếp đến
chúng; app khác có thể dùng cùng các service qua OSGi `@Reference`.

Không đổi PID `BMS.Network`, `BMS.System`, API JSON-RPC hay phân quyền hiện có.
Hai backend Ethernet vẫn tách biệt; việc chuyển thư mục không tự chọn backend,
không kích hoạt lại cấu hình mạng và không hợp nhất cấu hình NetworkManager với
systemd. Chỉ cấu hình backend phù hợp với thiết bị triển khai.

Repository serial tiếp tục đọc bridge `Bridge.Modbus.Serial` đã cấu hình và chỉ
cập nhật thuộc tính giao tiếp; không đổi `portName` hay tạo thêm cổng. Không tự
suy đoán đường dẫn thiết bị cho ba cổng vật lý. Mã hiện tại không áp đặt số lượng
ba cổng; topology ba cổng vẫn phải đến từ cấu hình triển khai.

Import/export, maintenance và system info phải có phần chung và phần riêng theo
app. Phần riêng nằm dưới `apps/<app>/services` hoặc `endpoints`, không nằm trong
`shared/system/apps`. Maintenance Huasu và string lifecycle vẫn ở BMS. Trong mã
Java hiện tại, `config.manager` cung cấp `exportConfigs`/`importConfigs` với quyền
ADMIN, chưa phân vùng theo app. Import có thể thay thế cấu hình hiện có: không
dùng nó như thao tác import riêng một app cho tới khi có bộ lọc/schema theo app.
Lần tái cấu trúc này giữ nguyên hành vi đó, không chạy import và không bổ sung
system-info tổng hợp mới. Các API cấu hình/host nền tảng của OpenEMS vẫn ở
`platform/edge/io.openems.edge.core`.
