# Hướng dẫn triển khai UI quản lý cấu hình mạng

Tài liệu này là checklist triển khai dành cho team UI. Contract đầy đủ và các
quy tắc nghiệp vụ chi tiết nằm tại
[`ui-network-management.md`](./ui-network-management.md).

Edge thử nghiệm hiện tại:

- Host: `100.72.109.93`
- Component ID: `_network`
- API mới sử dụng transaction bất đồng bộ; response `ACCEPTED` không có nghĩa là
  cấu hình đã áp dụng thành công.

## 1. Đọc cấu hình ban đầu

Gọi `getNetworkManagerConfig` trên component `_network` và lưu:

- `revision`
- `interfaces[].interfaceName`
- `interfaces[].desired`
- `interfaces[].observed`
- `activeOperation`

UI phải hiển thị riêng:

- **Configured**: lấy từ `desired`.
- **Observed**: lấy từ `observed`.

Không dùng địa chỉ runtime trong `observed` để ghi đè giá trị form.

## 2. Form cấu hình

UI gửi toàn bộ cấu hình của một interface, không gửi patch từng trường.

### Static IP

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

### DHCP

```json
{
  "interfaceName": "eth0",
  "mode": "DHCP",
  "ip": null,
  "prefixLength": null,
  "gateway": null,
  "dns": [],
  "expectedRevision": 11
}
```

Quy tắc form:

- Chỉ cho chọn interface do Edge trả về, hiện tại là `eth0` hoặc `eth1`.
- Chỉ hỗ trợ `DHCP` và `STATIC`.
- Static bắt buộc `ip` và `prefixLength`.
- `gateway` có thể là `null`.
- DNS tối đa hai địa chỉ IPv4.
- Disable nút Apply khi `activeOperation != null`.

## 3. Tạo operation

Gọi method `setNetworkManagerConfig`. Response là một
`NetworkOperationSnapshot`, ví dụ:

```json
{
  "operationId": "network-42",
  "sequence": 1,
  "revision": 12,
  "state": "ACCEPTED",
  "interfaceName": "eth0",
  "createdAt": "2026-08-09T14:30:00Z",
  "updatedAt": "2026-08-09T14:30:00Z",
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

Ngay khi nhận response, UI phải:

1. Lưu snapshot vào session storage.
2. Lưu `operationId`, địa chỉ cũ và các địa chỉ mới dự kiến.
3. Mở dialog tiến trình.
4. Subscribe các operation channel.
5. Không hiển thị thành công khi state mới là `ACCEPTED`.

## 4. Theo dõi operation qua WebSocket

Subscribe các channel của `_network`:

| Channel | Mục đích |
| --- | --- |
| `CurrentOperationId` | Operation vừa được cập nhật |
| `OperationSequence` | Phiên bản snapshot trong operation |
| `OperationState` | Trạng thái hiện tại |
| `OperationProgress` | Tiến trình tham khảo |
| `OperationInterface` | Interface đang thay đổi |
| `OperationErrorCode` | Mã lỗi hiện tại |
| `ConfirmationDeadline` | Thời hạn xác nhận |

Channel chỉ là trigger; snapshot API mới là nguồn dữ liệu canonical. Khi
`operationId` thay đổi hoặc `sequence` tăng, gọi
`getNetworkManagerOperation`:

```json
{
  "operationId": "network-42"
}
```

Quy tắc nhận update:

```text
Nếu CurrentOperationId khác operationId đang lưu:
    fetch snapshot

Nếu operationId giống nhưng OperationSequence lớn hơn sequence đang lưu:
    fetch snapshot

Nếu sequence bằng hoặc nhỏ hơn:
    bỏ qua event trùng hoặc cũ
```

Không poll liên tục khi WebSocket vẫn hoạt động. Có thể fetch lại snapshot nếu
operation đang chạy nhưng không nhận update trong khoảng 5 giây.

## 5. Hiển thị state machine

| State | Terminal | UI xử lý |
| --- | --- | --- |
| `ACCEPTED` | Không | Edge đã nhận request; chưa thành công |
| `APPLYING` | Không | Khóa form, hiển thị đang áp dụng |
| `VERIFYING` | Không | Hiển thị đang xác minh |
| `AWAITING_CONFIRMATION` | Không | Hiển thị countdown và chuẩn bị confirm |
| `APPLIED` | Có | Thành công; reload config |
| `CONFIGURED_WAITING_FOR_LINK` | Có | Profile đã lưu nhưng chưa có carrier; cảnh báo, không coi là lỗi |
| `ROLLING_BACK` | Không | Thử reconnect qua IP cũ |
| `ROLLED_BACK` | Có | Cấu hình cũ đã được khôi phục |
| `FAILED` | Có | Hiển thị `error.code` và rollback state |
| `CANCELLED` | Có | Người dùng hủy trước khi áp dụng |

## 6. Reconnect khi đổi IP

Khi WebSocket bị đóng sau `setNetworkManagerConfig`, UI không được tự chuyển
operation thành `FAILED`.

1. Thử candidate IP mới.
2. Nếu thất bại, thử IP cũ.
3. Dùng backoff `1s -> 2s -> 3s`, tối đa 5 giây cho mỗi vòng.
4. Nếu đã có `confirmationDeadline`, retry tới deadline cộng khoảng 10 giây.
5. Nếu deadline vẫn `null`, retry tối đa 120 giây từ `createdAt`.
6. Khi kết nối lại được, authenticate theo cơ chế hiện tại.
7. Gọi `getNetworkManagerOperation(operationId)`.

Nếu hết thời gian retry, UI vẫn giữ operation trong session storage và cung cấp
nút **Thử lại**. Mất transport không đồng nghĩa operation thất bại.

## 7. Xác nhận cấu hình

Chỉ gọi `confirmNetworkManagerConfig` khi:

- Snapshot đang là `AWAITING_CONFIRMATION`.
- UI đã kết nối thành công qua một địa chỉ thuộc `candidateAddresses`.
- UI vừa đọc snapshot thành công qua connection mới.

Request:

```json
{
  "operationId": "network-42",
  "expectedSequence": 5
}
```

Kết quả thành công phải là snapshot `APPLIED`. Không auto-confirm chỉ vì UI nhận
`AWAITING_CONFIRMATION` trên connection cũ.

## 8. Hủy operation

Gọi `cancelNetworkManagerConfig`:

```json
{
  "operationId": "network-42"
}
```

Kết quả có thể là:

- `CANCELLED`: chưa bắt đầu thay đổi mạng.
- `ROLLED_BACK`: đã tạo checkpoint hoặc bắt đầu thay đổi mạng.
- Terminal snapshot hiện tại: operation đã kết thúc trước đó.

## 9. Xử lý lỗi

### Lỗi trước khi tạo operation

Các lỗi này nằm trong JSON-RPC error message. UI đối chiếu prefix mã đã biết:

- `INVALID_INTERFACE`
- `INVALID_IP_ADDRESS`
- `INVALID_PREFIX_LENGTH`
- `INVALID_GATEWAY`
- `INVALID_DNS`
- `REVISION_CONFLICT`
- `OPERATION_IN_PROGRESS`
- `OPERATION_NOT_FOUND`
- `STALE_OPERATION_SNAPSHOT`
- `OPERATION_ALREADY_ROLLED_BACK`

Ví dụ:

```text
INVALID_DNS: Invalid DNS address
```

`OPERATION_IN_PROGRESS` có kèm operation ID hiện tại. UI nên lấy snapshot và mở
lại dialog của operation đó.

### Lỗi sau khi đã có operationId

Đọc lỗi từ snapshot:

```json
{
  "error": {
    "code": "CONFIRMATION_TIMEOUT",
    "stage": "ROLLBACK",
    "message": "Network configuration confirmation timed out",
    "retryable": false,
    "rollbackState": "ROLLED_BACK"
  }
}
```

UI quyết định nghiệp vụ bằng `error.code`, không parse `message`.

## 10. Xử lý terminal state

Khi nhận `APPLIED`, `CONFIGURED_WAITING_FOR_LINK`, `FAILED`, `ROLLED_BACK` hoặc
`CANCELLED`:

1. Gọi lại `getNetworkManagerConfig`.
2. Cập nhật `revision`.
3. Cập nhật cả `desired` và `observed`.
4. Hiển thị kết quả cuối cho người dùng.
5. Xóa operation khỏi session storage sau khi người dùng đã nhận kết quả.

Nếu nhận `OPERATION_NOT_FOUND`, xóa operation local và reload config/status. Không
tự suy đoán operation đã thành công hay rollback.

## 11. Checklist nghiệm thu UI

- [ ] `ACCEPTED` không hiển thị thành công.
- [ ] Static IP áp dụng và confirm thành `APPLIED`.
- [ ] DHCP áp dụng thành công.
- [ ] WebSocket disconnect không làm đóng dialog.
- [ ] Reconnect qua candidate IP rồi auto-confirm.
- [ ] Candidate IP thất bại, timeout và reconnect qua IP cũ hiển thị
      `ROLLED_BACK` với `CONFIRMATION_TIMEOUT`.
- [ ] Reload browser giữa operation phục hồi dialog từ session storage.
- [ ] Event WebSocket trùng hoặc sai thứ tự không làm state quay ngược.
- [ ] Interface không có carrier hiển thị `CONFIGURED_WAITING_FOR_LINK`.
- [ ] Hai browser cùng chỉnh config: revision cũ nhận `REVISION_CONFLICT`.
- [ ] Cancel trước apply nhận `CANCELLED`.
- [ ] Cancel sau khi bắt đầu apply nhận `ROLLED_BACK`.
- [ ] `OPERATION_IN_PROGRESS` mở lại operation đang chạy.
- [ ] UI cũ hoặc response không có `operationId` hiển thị cảnh báo Edge chưa hỗ
      trợ network transaction.

## 12. Lưu ý khi test trên thiết bị

- Edge và UI mới phải được phát hành đồng thời.
- Không test đổi IP trên thiết bị production nếu không có đường truy cập dự phòng.
- Luôn lưu IP cũ, candidate IP và `operationId` trước khi gửi mutation.
- Edge `100.72.109.93` đã có API mới và component `_network` đã được đăng ký.
- Port WebSocket `8085` trên Edge hiện bind loopback; UI tiếp tục truy cập qua
  Backend/proxy theo kiến trúc hiện tại, không kết nối trực tiếp tới port này qua
  Tailscale.
