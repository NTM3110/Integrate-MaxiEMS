# Cấu trúc Integrate-MaxiEMS

Mã ứng dụng nằm trong [`apps/`](../apps/README.md), không còn nằm cùng một danh
sách bundle ở thư mục gốc. Các phần nền tảng còn lại:

| Thư mục | Trách nhiệm |
|---|---|
| `platform/common/` | Common, OEM, logging, shared InfluxDB |
| `platform/edge/` | Edge core, domain API, scheduler, timedata, các API nền tảng |
| `platform/backend/` | Các dịch vụ OpenEMS Backend |
| `platform/protocols/` | Bridge/transport chung: Modbus, DLMS, EDMI, MQTT, HTTP, IEC104, IEC 62056-21, v.v. |
| `platform/devices/` | Tích hợp thiết bị OpenEMS khác chưa thuộc các app |
| `platform/wrappers/` | Thư viện bên thứ ba được đóng gói OSGi |
| `platform/applications/` | Ba application bundle và `.bndrun` hiện có |
| `cnf/` | Cấu hình Bnd và kho phụ thuộc |
| `tests/workspace/` | Kiểm thử cấu trúc mã nguồn |
| `tools/`, `deploy/`, `doc/` | Công cụ, mẫu triển khai, tài liệu |

## Build và IDE

`settings.gradle` tìm `bnd.bnd` đệ quy trong `apps` và `platform`.
Tên Gradle project vẫn là tên bundle; ví dụ:

```bash
bash gradlew :io.openems.edge.bms.service:test
bash gradlew :io.openems.edge.shared.network:test
bash gradlew testEdge assembleEdge
bash gradlew buildEdge
python3 -m unittest discover -s tests/workspace -v
```

Executable vẫn xuất ra `build/openems-edge.jar`. Chạy từ thư mục gốc để giữ
đường dẫn `config/` và `data/` như trước. Không di chuyển hoặc commit runtime
config, dữ liệu, secret. Các `.bndrun` chỉ đổi vị trí; chưa tách runtime cho từng
app trong bước này.

Bnd yêu cầu tra cứu project theo workspace phẳng. Gradle tạo `.bnd-workspace/`
chứa symbolic link tới các bundle thật và `cnf`. Đây chỉ là view tương thích,
được gitignore; không phải bản sao mã nguồn. Không chỉnh sửa hoặc commit nội
dung generated này. Windows cần Developer Mode hoặc quyền tạo symbolic link.
Gradle tắt filesystem watching để tránh theo dõi trùng nguồn qua symlink.

Với IDE hỗ trợ Gradle, import root project. Với Bndtools thuần, chạy một lệnh
Gradle trước rồi dùng `.bnd-workspace` làm Bnd workspace và import các project
qua view đó; không import cả đường dẫn thật và symlink như hai project khác nhau.
Luồng Bndtools GUI chưa được kiểm thử trong môi trường này.

## Thêm bundle và tìm đường dẫn cũ

Đặt bundle vào app/chức năng phù hợp, giữ tên bundle duy nhất và thêm `bnd.bnd`.
Gradle tự phát hiện; không thêm đường dẫn mới vào danh sách thủ công. Nếu cần
chạy bundle, cập nhật `-runrequires` của application tương ứng rồi resolve.

[`tools/workspace/legacy-paths.json`](../tools/workspace/legacy-paths.json) lưu
ánh xạ 292 bundle gốc sang vị trí mới. `restructure.py` là công cụ migration
một lần, không tham gia build. Ba package `serial`, `network`, `system` tách
khỏi BMS Core nằm trong các bundle `shared.*` như mô tả ở
[`apps/shared/README.md`](../apps/shared/README.md).

## Kiểm chứng sau migration

- `bash gradlew testEdge assembleEdge buildEdge --offline --console=plain`:
  thành công, 1467 task actionable; resolver/export tạo lại Edge executable.
- Sáu kiểm thử cấu trúc workspace thành công; bao gồm kiểm tra vị trí Bridge, profile meter, application và source Java
  không bị quy tắc ignore runtime che khuất.
- Kiểm thử serial mới xác nhận giữ ba bridge đã cấu hình, giữ `portName` và
  thuộc tính ngoài phạm vi cập nhật, không tạo bridge khi không tìm thấy.
- `bash -n tools/prepare-commit.sh tools/common.sh` và `git diff --check` thành công.

Chưa chạy runtime với phần cứng thật, backend runtime, Bndtools GUI hoặc
PowerShell; lần này không thay đổi runtime config và không chạy import config.
Build còn các warning thư viện/Bnd và cảnh báo deprecated Gradle API; không
nâng cấp dependency hoặc Gradle trong phạm vi tái cấu trúc.

Tài liệu tích hợp cũ có thể còn nhắc đường dẫn phẳng; dùng bảng ánh xạ để tìm
nguồn mới. Bundle ID và lệnh Gradle trong các tài liệu đó không đổi.
