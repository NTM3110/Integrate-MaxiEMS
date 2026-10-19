# Kiểm thử BMSApp sau tích hợp

## Quan hệ bundle và kiểm tra lúc chạy

| Provider | Consumer | Ràng buộc cần kiểm tra |
| --- | --- | --- |
| `io.openems.edge.shared.postgresql` (`PostgresConnectionFactory`) | `io.openems.edge.bms.service` (`BmsDatabaseImpl`) | BMS PID lịch sử tạo pool cho DB thử nghiệm, chạy migration rồi báo available |
| `io.openems.edge.bms.service` (`BmsDatabase`, `PlatformAuthenticationService`, `StringManagementOperations`, các model) | `io.openems.edge.bms.api` | API JSON-RPC bind đúng service; đăng nhập và `bms.strings.list` hoạt động |
| `io.openems.edge.bms.bridge.modbus` (`Bridge.BmsModbus.Serial`) | TA/TC drivers và BMS serial service | `modbus0` dùng PTY demo; `bms.tools.serial.list` thấy bridge |
| `io.openems.edge.bms.huasu.ta` và `.tc` | `io.openems.edge.controller.bms` | Sau `bms.strings.create`, 110 TA unit 1–110 và TC unit 206 bind `modbus0` |
| `io.openems.edge.bms.service` (`BmsStringLifecycle`) | `io.openems.edge.bms.api` (`StringJsonApi`) | Lệnh Create sinh TA, TC, calculator, monitoring qua ConfigAdmin; trước Create không có các instance đó |
| `io.openems.edge.scheduler.allalphabetically` | calculator, monitoring và WebSocket controller | Scheduler lấy cả controller mới được bật sau khi Create; calculator chạy trước monitoring theo ID |
| `io.openems.edge.controller.api.websocket` | FE `maxicom-frontend` | WebSocket `127.0.0.1:8085`, tách cổng HTTP `8081` |

`BMSApp.bndrun` liệt kê các provider trên. `resolve.BMSApp` kiểm tra import/export OSGi; nó không chứng minh DS `@Reference` đã bind, nên cần đọc log/runtime và thử API.

Sau khi sửa bundle BMS batch, build module rồi chạy `resolve.BMSApp` và `export.BMSApp --rerun` ở hai lượt Gradle riêng. Kiểm tra JAR nhúng có `BridgeBmsModbusSerialImpl.class` và `OSGI-INF/Bridge.BmsModbus.Serial.xml`; chỉ thấy tên bundle hoặc package `.api` là chưa đủ. Với factory PID `Bridge.BmsModbus.Serial`, tên file cấu hình dưới `felix.cm.dir` phải khớp suffix của `service.pid` (ví dụ `Bridge/BmsModbus/Serial/bms.config` cho `Bridge.BmsModbus.Serial.bms`). Luôn có file PostgreSQL trỏ rõ vào database demo/disposable trước khi khởi động; nếu thiếu, `BmsDatabaseImpl` có thể dùng database mặc định `maxiems`.

Khi khởi động, `AppValidateWorker` có thể báo một lần `NullPointerException` tại `AppConfigValidator.validate` nếu danh sách app thay đổi giữa lúc tạo bản đồ cấu hình và lúc kiểm tra. Trong lần thử với 11 cấu hình, lỗi chỉ xuất hiện một lần ngay sau startup; auth và `bms.strings.list` vẫn hoạt động, không lặp lại trong phút theo dõi. Nếu lỗi lặp lại, ghi thành blocker và kiểm tra cấu hình `Core.AppManager` cùng các app đã cài.

## Khởi động thử nghiệm

Profile `smoke` chỉ dùng PostgreSQL disposable. Bộ tạo cấu hình từ chối database vận hành `maxiems` và từ chối ghi đè `config-bms`. Nó tạo 11 cấu hình hạ tầng, gồm đủ sáu Core (`_appManager`, `_componentManager`, `_cycle`, `_meta`, `_predictorManager`, `_sum`), không tạo string, TA, TC, calculator hay monitoring. Mật khẩu đi qua biến môi trường và cấu hình sinh ra được Git bỏ qua; không commit cấu hình này.

Khi cần đăng nhập mặc định `admin/admin` trên máy cục bộ, chọn rõ `BMS_TEST_PROFILE=local-demo`. Profile này sinh `config-bms-demo`, chỉ chấp nhận PostgreSQL `localhost:5432/demo`, vẫn không tạo string. Backend tự sinh salt và hash PBKDF2 khi tạo tài khoản `admin` lần đầu trong database rỗng; generator không tạo hash hoặc ghi trực tiếp vào bảng người dùng. Nếu database đã có bất kỳ tài khoản nào, bootstrap sẽ không tạo hoặc đổi mật khẩu `admin`. Chỉ sử dụng `admin/admin` trong môi trường demo cục bộ; đổi mật khẩu sau lần đăng nhập đầu tiên nếu giữ lại database.

Tại thư mục gốc `Integrate-MaxiEMS`:

```bash
export BMS_TEST_PG_DATABASE=bms_app_test
export BMS_TEST_PG_HOST=127.0.0.1
export BMS_TEST_PG_PORT=55432
export BMS_TEST_PG_USER=shou
export BMS_TEST_PG_PASSWORD=''
read -rs -p 'Mật khẩu admin thử nghiệm (>=12 ký tự): ' BMS_TEST_ADMIN_PASSWORD; echo
export BMS_TEST_ADMIN_PASSWORD
python3 apps/bms/create-smoke-config.py
mkdir -p data-bms
timeout 420s bash ./gradlew resolve.BMSApp
timeout 420s bash ./gradlew export.BMSApp
java -Dfelix.cm.dir="$(pwd)/config-bms" -Dopenems.data.dir="$(pwd)/data-bms" \
  -jar platform/applications/io.openems.edge.application/generated/distributions/executable/BMSApp.jar
```

Tạo database bằng quyền PostgreSQL cục bộ trước khi chạy; xác nhận `bms_app_test` là database disposable. Chạy demo String 1 trước backend: `democtl start` rồi `democtl status` tại dự án `BMS/demo`. Nếu không có quyền tạo `/dev/ttyBMS1`, chạy simulator bằng cặp PTY trong `/tmp` và đặt `BMS_TEST_MODBUS_PORT` thành PTY mà backend mở **trước khi** sinh cấu hình. Cấu hình mặc định là `/dev/ttyBMS1`, 9600/8N1. Không sửa `/dev` hoặc dừng demo của người khác.

Để dùng database `demo` mới trên PostgreSQL cục bộ, thay phần export/generator ở trên bằng:

```bash
export BMS_TEST_PROFILE=local-demo
export BMS_TEST_PG_DATABASE=demo
export BMS_TEST_PG_HOST=127.0.0.1
export BMS_TEST_PG_PORT=5432
export BMS_TEST_PG_USER=bms_demo
export BMS_TEST_PG_PASSWORD="$(cat data-bms-demo/pg-password)"
export BMS_TEST_MODBUS_PORT="$(pwd)/data-bms-demo/serial/bridge"
python3 apps/bms/create-smoke-config.py
mkdir -p data-bms-demo
java -Dfelix.cm.dir="$(pwd)/config-bms-demo" -Dopenems.data.dir="$(pwd)/data-bms-demo" \
  -jar platform/applications/io.openems.edge.application/generated/distributions/executable/BMSApp.jar
```

Trên máy kiểm thử hiện tại, database `demo` thuộc role riêng `bms_demo`; mật khẩu PostgreSQL được lưu trong file local Git bỏ qua `data-bms-demo/pg-password` (mode `0600`). Tạo database và role tương ứng nếu chạy trên máy khác; đặt mật khẩu PostgreSQL riêng, không dùng `admin/admin` cho role DB. Không dùng `DROP SCHEMA bms CASCADE` hoặc fixture kiểm thử trên `demo`. FE chạy bằng `cd ../maxicom-frontend && VITE_BMS_DEFAULT_HOST=127.0.0.1 VITE_BMS_WS_PORT=8085 npm run dev:bms -- --host 127.0.0.1`; đăng nhập BMS bằng `admin/admin` sau khi backend hoàn tất migration và bootstrap.

Ở terminal khác, chạy `maxicom-frontend` với `VITE_BMS_WS_PORT=8085`. Đăng nhập BMS bằng tài khoản `admin` và mật khẩu thử nghiệm. Kiểm tra `bms.strings.list` rỗng và `bms.tools.serial.list` có `modbus0`; nhấn **Add string** với 110 cell và chọn cổng `modbus0`. FE gửi `serialPortId=modbus0` và `startTaUnitId=1`; backend lưu cổng này thành `bridgeId`. Xác nhận API trả kết quả Create, reload vẫn có String 1, ConfigAdmin có 110 TA, một TC, calculator và monitoring; sau đó kiểm tra dữ liệu live. FE preview khi chưa đăng nhập không phải bằng chứng API thành công.

Sau thử nghiệm, dừng backend trước, rồi dừng demo. Xóa riêng thư mục cấu hình/data của profile đã dùng và database disposable chỉ khi đã xác nhận chúng thuộc lần chạy này. Không dùng schema fixture trên database vận hành.

## Kết quả chạy local-demo ngày 2026-09-28

- PostgreSQL `127.0.0.1:5432/demo` kết nối bằng role `bms_demo`; migration hoàn tất. Backend tự tạo `admin`; `bms.auth_user` có hash 32 byte và salt 16 byte, không lưu `admin` dạng plaintext.
- FE tại `http://127.0.0.1:5173/` đăng nhập `admin/admin`, hiển thị BMS backend connected. Danh sách string ban đầu rỗng; form Add string thấy `modbus0`.
- Nhấn Save string tạo `Demo Huasu String` với 110 cell, brand Huasu, model Demo, capacity 100 Ah và `modbus0`. Sau reload, string vẫn có trong danh sách; ConfigAdmin có 110 TA, một TC, một calculator và một monitoring.
- Live view báo TA 110/110, TC SUCCESS, cell 1 là 2,298 V và 1.800 µΩ; Health Monitor báo `ACTIVE snapshot` với 110 cell đã xử lý.
- Backend, FE và simulator được giữ chạy cho kiểm thử tiếp. JAR đã được export ở bước trước, không giải nén.
