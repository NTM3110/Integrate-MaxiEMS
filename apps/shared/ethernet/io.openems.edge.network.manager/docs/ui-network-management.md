# Nghiệp vụ UI quản lý cấu hình mạng

## 1. Mục đích và phạm vi

Tài liệu này định nghĩa contract mục tiêu giữa OpenEMS UI và component
`_networkManager` sau khi luồng quản lý mạng dạng transaction được triển khai.
Contract này **thay thế** hành vi cũ, trong đó `setNetworkManagerConfig` trả về
`EmptyObject` trong khi Edge vẫn áp dụng cấu hình bất đồng bộ.

UI chịu trách nhiệm:

- Hiển thị cấu hình mong muốn và trạng thái mạng quan sát được.
- Gửi toàn bộ cấu hình của một interface trong một request.
- Theo dõi operation qua WebSocket; dùng API snapshot để phục hồi khi reconnect.
- Kết nối lại qua địa chỉ mới hoặc cũ khi thay đổi IP làm gián đoạn WebSocket.
- Xác nhận cấu hình mới trước thời hạn rollback.
- Hiển thị đúng trạng thái thành công, chờ link, thất bại hoặc đã rollback.

UI không chạy hoặc tạo câu lệnh hệ điều hành. Edge chịu trách nhiệm quản lý
NetworkManager connection profile, kiểm tra kết quả và rollback.

## 2. Nguyên tắc tích hợp

1. `setNetworkManagerConfig` chỉ xác nhận Edge đã tiếp nhận transaction. Response
   `ACCEPTED` không có nghĩa cấu hình mạng đã thành công.
2. WebSocket/channel subscription là đường cập nhật trạng thái chính.
3. `getNetworkManagerOperation` trả snapshot đầy đủ và là đường phục hồi bắt buộc
   sau reload, reconnect hoặc khi UI nghi ngờ bỏ lỡ event.
4. WebSocket channels chỉ thông báo `operationId` và `sequence` đã thay đổi; UI
   đọc toàn bộ `NetworkOperationSnapshot` bằng API snapshot. Cả hai phải tham chiếu
   cùng một operation store tại Edge.
5. UI chỉ kết luận thành công khi operation ở trạng thái `APPLIED` hoặc
   `CONFIGURED_WAITING_FOR_LINK` theo định nghĩa tại mục 6.
6. UI phải lưu `operationId`, địa chỉ cũ và các địa chỉ dự kiến mới trong bộ nhớ
   phiên trước khi WebSocket có thể bị ngắt.
7. Mỗi request thay đổi cấu hình phải chứa `expectedRevision` để ngăn hai phiên UI
   ghi đè cấu hình của nhau.

## 3. Mô hình cấu hình interface

UI gửi toàn bộ cấu hình của một interface, không gửi patch từng trường.

```json
{
  "interfaceName": "eth0",
  "mode": "STATIC",
  "ip": "192.168.10.20",
  "prefixLength": 24,
  "gateway": "192.168.10.1",
  "dns": ["8.8.8.8", "1.1.1.1"],
  "expectedRevision": 11
}
```

Quy tắc UI:

- `interfaceName`: chỉ chọn từ danh sách Edge trả về; không cho nhập tự do.
- `mode`: `DHCP` hoặc `STATIC`. `NONE` không còn là hành động cấu hình từ UI.
- Với `STATIC`, `ip` và `prefixLength` là bắt buộc.
- Với `DHCP`, UI gửi `ip = null`, `prefixLength = null`, `gateway = null`; `dns`
  có thể rỗng để dùng DNS từ DHCP.
- `gateway` có thể null nếu mạng tĩnh không cần default route.
- `dns` là mảng có thứ tự, tối đa hai địa chỉ IPv4 trong phạm vi hiện tại.
- UI validate để phản hồi nhanh; Edge luôn validate lại và là nguồn quyết định cuối.

## 4. API contract

### 4.1. Đọc cấu hình và trạng thái hiện tại

`getNetworkManagerConfig` trả về ba lớp dữ liệu:

- `revision`: revision cấu hình đã commit gần nhất.
- `desired`: cấu hình last-known-good của từng interface.
- `observed`: trạng thái thực tế đọc từ NetworkManager/kernel.

Ví dụ response:

```json
{
  "revision": 11,
  "interfaces": [
    {
      "interfaceName": "eth0",
      "desired": {
        "mode": "DHCP",
        "ip": null,
        "prefixLength": null,
        "gateway": null,
        "dns": []
      },
      "observed": {
        "deviceState": "ACTIVATED",
        "carrier": true,
        "addresses": ["192.168.1.20/24"],
        "gateway": "192.168.1.1",
        "dns": ["192.168.1.1"],
        "activeProfile": "openems-eth0"
      }
    }
  ],
  "activeOperation": null
}
```

UI không được dùng `observed` để tự suy ra `desired`. Ví dụ, interface chưa cắm
cáp vẫn có thể có static profile hợp lệ nhưng `observed.addresses` đang rỗng.
`deviceState` là tên trạng thái NetworkManager đã được Edge chuẩn hóa, ví dụ
`ACTIVATED`, `DISCONNECTED`, `UNAVAILABLE`, `FAILED` hoặc `UNKNOWN`; UI không parse
chuỗi trạng thái dạng số do `nmcli` trả về.

### 4.2. Tạo operation cấu hình

Method: `setNetworkManagerConfig`

Yêu cầu quyền: `ADMIN`.

Response thành công khi request hợp lệ và đã được lưu làm pending operation:

```json
{
  "operationId": "network-42",
  "sequence": 1,
  "revision": 12,
  "state": "ACCEPTED",
  "interfaceName": "eth0",
  "createdAt": "2026-08-07T10:28:30Z",
  "updatedAt": "2026-08-07T10:28:30Z",
  "confirmTimeoutSeconds": 60,
  "confirmationDeadline": null,
  "previousAddresses": ["192.168.1.20"],
  "candidateAddresses": ["192.168.10.20"],
  "profileVerified": false,
  "runtimeVerified": false,
  "linkAvailable": true,
  "error": null
}
```

Ngay sau response này, UI phải:

1. Lưu snapshot và `operationId`.
2. Mở màn hình tiến trình, không hiển thị “Thành công”.
3. Theo dõi WebSocket operation channels.
4. Chuẩn bị reconnect qua `candidateAddresses` nếu connection hiện tại bị đóng.

Nếu Edge đã có operation chưa terminal, request mới bị từ chối bằng
`OPERATION_IN_PROGRESS`; UI phải đưa người dùng về operation đang chạy.

### 4.3. Đọc operation snapshot

Method: `getNetworkManagerOperation`

Request:

```json
{
  "operationId": "network-42"
}
```

Response là toàn bộ `NetworkOperationSnapshot`, cùng schema với response của
`setNetworkManagerConfig`. WebSocket chỉ phát tín hiệu để UI biết snapshot đã đổi,
không phát một bản sao object riêng.

UI gọi API này khi:

- Vừa reload và còn `operationId` chưa terminal.
- WebSocket vừa reconnect.
- WebSocket đang connected nhưng operation không có update trong 5 giây.
- UI kết nối lại được qua IP cũ sau thời hạn confirmation để biết operation đã
  rollback hay chưa.

Không cần poll khi WebSocket hoạt động và event vẫn được nhận bình thường.

### 4.4. Xác nhận cấu hình

Method: `confirmNetworkManagerConfig`

Request:

```json
{
  "operationId": "network-42",
  "expectedSequence": 5
}
```

UI chỉ gọi khi snapshot hiện tại là `AWAITING_CONFIRMATION` và UI đã kết nối được
tới Edge qua một địa chỉ thuộc cấu hình mới. Edge commit cấu hình last-known-good,
hủy checkpoint và trả snapshot `APPLIED`.

API phải idempotent:

- Gọi lại cho operation đã `APPLIED` trả snapshot `APPLIED` hiện tại.
- Gọi cho operation đã `ROLLED_BACK` không áp dụng lại cấu hình và trả lỗi
  `OPERATION_ALREADY_ROLLED_BACK`.
- `expectedSequence` cũ trả `STALE_OPERATION_SNAPSHOT`; UI phải lấy snapshot mới.

### 4.5. Hủy operation

Method: `cancelNetworkManagerConfig`

UI cho phép hủy khi operation chưa terminal. Edge rollback nếu đã bắt đầu thay đổi
mạng và trả snapshot `ROLLED_BACK`; nếu chưa apply, Edge trả `CANCELLED`.

Request:

```json
{
  "operationId": "network-42"
}
```

## 5. WebSocket/channel subscription

UI sử dụng WebSocket channel subscription hiện có của OpenEMS. Component
`_networkManager` công bố tối thiểu các channel sau:

| Channel | Kiểu | Ý nghĩa |
| --- | --- | --- |
| `CurrentOperationId` | string/null | Operation vừa phát update gần nhất, kể cả update terminal |
| `OperationSequence` | integer/null | Số thứ tự tăng đơn điệu trong operation |
| `OperationState` | string/null | Trạng thái operation |
| `OperationProgress` | integer/null | Tiến trình tham khảo từ 0 đến 100 |
| `OperationInterface` | string/null | Interface đang được cấu hình |
| `OperationErrorCode` | string/null | Mã lỗi ổn định để UI dịch |
| `ConfirmationDeadline` | string/null | Thời điểm hết hạn xác nhận theo ISO-8601 UTC |

Channel chỉ dùng làm tín hiệu thay đổi nhẹ. Khi `OperationSequence` tăng hoặc UI
không có snapshot đầy đủ cho `CurrentOperationId`, UI gọi
`getNetworkManagerOperation` một lần để lấy canonical snapshot. Cách này tránh
phải mã hóa toàn bộ object/error details vào nhiều channel và tránh đọc một tập
channel ở các cycle khác nhau thành một snapshot không nhất quán.

Quy tắc nhận update:

```text
Nếu operationId khác operation hiện tại:
    lấy snapshot bằng getNetworkManagerOperation
Nếu operationId giống và sequence lớn hơn lastKnownSequence:
    lấy snapshot mới và cập nhật UI
Nếu sequence bằng hoặc nhỏ hơn:
    bỏ qua update trùng/cũ
```

WebSocket không được coi là hàng đợi đảm bảo delivery. Snapshot API mới là cơ chế
phục hồi event bị mất.

Edge persist snapshot terminal gần nhất qua restart, nhưng không cung cấp lịch sử
operation hoặc cam kết retention theo thời gian. Việc chuyển sang terminal state
vẫn tăng `OperationSequence` trước khi operation được đánh dấu không còn active.
UI phải lưu `operationId` trong session và xử lý terminal snapshot ngay khi reconnect;
`activeOperation` trong `getNetworkManagerConfig` là null khi không còn operation
chưa terminal. Một operation cũ hơn snapshot terminal gần nhất có thể trả lỗi khi
Edge đã restart hoặc operation mới đã thay thế nó.

## 6. State machine và hành vi UI

| State | Terminal | UI hiển thị và hành động |
| --- | --- | --- |
| `ACCEPTED` | Không | “Edge đã nhận cấu hình”; chờ apply |
| `APPLYING` | Không | “Đang cập nhật cấu hình mạng”; khóa form |
| `VERIFYING` | Không | “Đang xác minh cấu hình”; khóa form |
| `AWAITING_CONFIRMATION` | Không | Hiện countdown và nút xác nhận; tự xác nhận chỉ sau khi reconnect qua IP mới thành công |
| `CONFIGURED_WAITING_FOR_LINK` | Có | Profile đã lưu đúng nhưng chưa có cáp/carrier; hiển thị cảnh báo, không coi là lỗi |
| `APPLIED` | Có | Hiển thị thành công và refresh cấu hình/status |
| `FAILED` | Có | Hiển thị lỗi và rollback state đi kèm |
| `ROLLING_BACK` | Không | “Đang khôi phục cấu hình trước đó”; thử reconnect IP cũ |
| `ROLLED_BACK` | Có | Thông báo cấu hình mới không được giữ; refresh từ Edge |
| `CANCELLED` | Có | Người dùng đã hủy trước khi commit |

Luồng trạng thái chuẩn:

```text
ACCEPTED -> APPLYING -> VERIFYING -> AWAITING_CONFIRMATION -> APPLIED
                              `-> CONFIGURED_WAITING_FOR_LINK
          `-> FAILED
VERIFYING/AWAITING_CONFIRMATION -> ROLLING_BACK -> ROLLED_BACK
ACCEPTED -> CANCELLED
```

`CONFIGURED_WAITING_FOR_LINK` có nghĩa profile bền vững đã được xác minh nhưng
không thể xác minh runtime vì interface không có carrier. UI nên hiển thị “Đã lưu
cấu hình, đang chờ kết nối cáp”, không hiển thị “Cấu hình thất bại”.

## 7. Luồng reconnect khi thay đổi IP

Ví dụ UI đang kết nối `192.168.1.20` và cấu hình mới là `192.168.10.20`:

1. UI nhận và lưu response `ACCEPTED`, gồm IP cũ, IP mới và `operationId`.
2. Khi WebSocket đóng, UI chuyển sang `RECONNECTING`; không chuyển operation thành
   `FAILED` chỉ vì transport bị mất.
3. UI thử IP mới trước, sau đó IP cũ, dùng backoff 1, 2, 3 và tối đa 5 giây cho
   mỗi vòng. Nếu đã biết `confirmationDeadline`, tiếp tục tới deadline cộng 10 giây
   grace. Nếu deadline vẫn null vì UI mất kết nối trước lúc tạo checkpoint, tiếp
   tục tối đa 120 giây tính từ `createdAt`, rồi chuyển sang chế độ “Thử lại”.
4. Khi kết nối được, UI xác thực session theo cơ chế hiện có và gọi
   `getNetworkManagerOperation(operationId)`.
5. Nếu state là `AWAITING_CONFIRMATION` và kết nối hiện tại dùng IP mới, UI gọi
   `confirmNetworkManagerConfig`.
6. Nếu state là `ROLLING_BACK`, UI tiếp tục thử IP cũ.
7. Nếu state là `ROLLED_BACK`, UI thông báo rollback và tải lại config/status.
8. Nếu không kết nối được sau grace period, UI giữ `operationId`, hiển thị hướng
   dẫn kiểm tra IP mới/IP cũ và cho phép “Thử lại”; không tự tuyên bố kết quả.

UI không tự confirm chỉ vì nhận được `AWAITING_CONFIRMATION` trên connection cũ.
Điều kiện confirm tự động là request status thành công qua candidate IP. Nếu người
dùng thao tác tại chỗ và muốn giữ cấu hình trong trường hợp đặc biệt, có thể cung
cấp nút xác nhận thủ công kèm cảnh báo rõ ràng.

## 8. Error model và thông điệp UI

```json
{
  "code": "ACTIVATION_FAILED",
  "stage": "ACTIVATE",
  "message": "NetworkManager could not activate the connection profile",
  "retryable": false,
  "rollbackState": "ROLLED_BACK"
}
```

UI dịch theo `code`; `message` dùng cho phần thông tin kỹ thuật của lỗi trong
operation snapshot. Không parse nội dung `message` để quyết định nghiệp vụ.

| Error code | Hành vi UI |
| --- | --- |
| `INVALID_INTERFACE` | Reload danh sách interface; không retry tự động |
| `INVALID_IP_ADDRESS` | Đánh dấu trường IP |
| `INVALID_PREFIX_LENGTH` | Đánh dấu trường prefix |
| `INVALID_GATEWAY` | Đánh dấu trường gateway |
| `INVALID_DNS` | Đánh dấu trường DNS tương ứng |
| `REVISION_CONFLICT` | Reload config, thông báo cấu hình đã thay đổi ở phiên khác |
| `OPERATION_IN_PROGRESS` | Mở operation đang chạy |
| `PROFILE_CREATE_FAILED` | Hiển thị lỗi hệ thống; cho retry thủ công nếu `retryable=true` |
| `PROFILE_UPDATE_FAILED` | Hiển thị lỗi hệ thống và trạng thái rollback |
| `ACTIVATION_FAILED` | Hiển thị không thể kích hoạt interface; không đồng nghĩa validation input sai |
| `VERIFICATION_FAILED` | Hiển thị cấu hình quan sát được không khớp cấu hình yêu cầu |
| `CONFIRMATION_TIMEOUT` | Thông báo đã hết hạn và cấu hình cũ được khôi phục |
| `ROLLBACK_FAILED` | Cảnh báo nghiêm trọng; yêu cầu kiểm tra Edge tại chỗ |
| `OPERATION_NOT_FOUND` | Xóa operation local và reload config/status |
| `STALE_OPERATION_SNAPSHOT` | Fetch snapshot mới rồi cho phép thao tác lại |

Lỗi JSON-RPC trước khi tạo được operation được hiển thị ngay trên form. Trong
implementation hiện tại, mã ổn định như `REVISION_CONFLICT`, `INVALID_DNS` hoặc
`OPERATION_IN_PROGRESS` nằm trong message của JSON-RPC error; UI đối chiếu prefix
mã đã biết, không áp dụng cách này cho lỗi trong operation. Lỗi sau khi đã có
`operationId` luôn được đọc từ `error.code` trong snapshot operation.

## 9. Quy tắc màn hình UI

### Form cấu hình

- Hiển thị riêng `Configured` và `Observed`; không thay giá trị form bằng IP runtime.
- Khi chọn DHCP, ẩn/disable IP, prefix và gateway; DNS được để rỗng theo mặc định.
- Khi chọn static, validate bắt buộc IP/prefix trước khi bật nút Apply.
- Hiển thị cảnh báo rằng thay đổi interface đang phục vụ UI có thể ngắt kết nối
  trong tối đa thời gian confirmation.
- Disable Apply khi có active operation.

### Dialog tiến trình

- Không đóng chỉ vì WebSocket disconnect.
- Hiển thị state, interface, countdown, IP cũ và IP mới.
- Cho phép cancel trước terminal state, nhưng cảnh báo nếu Edge đang rollback.
- Giữ operation trong session storage để reload trang vẫn phục hồi được.
- Xóa operation khỏi session storage sau khi đã xử lý một terminal snapshot.

### Refresh dữ liệu

Sau `APPLIED`, `CONFIGURED_WAITING_FOR_LINK`, `ROLLED_BACK`, `FAILED` hoặc
`CANCELLED`, UI gọi lại `getNetworkManagerConfig`. Không giả định dữ liệu form cũ
vẫn là nguồn đúng.

## 10. Điều kiện nghiệm thu UI

1. UI không hiển thị thành công khi chỉ nhận `ACCEPTED`.
2. Khi WebSocket hoạt động bình thường, UI không poll liên tục.
3. Khi mất WebSocket, UI thử cả IP mới và IP cũ và phục hồi operation bằng
   `operationId`.
4. Event trùng hoặc sai thứ tự không làm state UI quay ngược nhờ `sequence`.
5. Reload browser trong khi operation đang chạy phục hồi đúng dialog tiến trình.
6. UI chỉ auto-confirm sau khi kết nối và đọc snapshot thành công qua candidate IP.
7. Confirmation timeout hiển thị `ROLLED_BACK`, không hiển thị lỗi mạng chung chung.
8. Interface không có carrier nhưng profile đúng hiển thị
   `CONFIGURED_WAITING_FOR_LINK`.
9. Hai phiên UI cùng sửa cấu hình: phiên có revision cũ nhận `REVISION_CONFLICT`.
10. Mọi terminal state đều dẫn tới refresh config/status từ Edge.

## 11. Lưu ý rollout

Contract mới thay thế API cũ nên Edge và UI phải được phát hành đồng thời. UI mới
không được chạy với Edge chỉ trả `EmptyObject`; trong trường hợp không nhận được
`operationId`, UI phải báo “Phiên bản Edge chưa hỗ trợ network transaction” và
không giả định cấu hình đã thành công.
