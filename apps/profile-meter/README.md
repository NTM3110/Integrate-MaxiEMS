# Profile meter integrations

Nhóm này chứa các driver meter có khả năng đọc current values, historical
values, billing data hoặc load profile:

- `meters/io.openems.edge.meter.elster.a1700`: Elster A1700 qua IEC 62056-21 Mode C.
- `meters/io.openems.edge.meter.edmi`: EDMI meter qua EDMI Bridge/IMR.
- `meters/io.openems.edge.meter.landis.dlms`: Landis meter qua DLMS/COSEM.
- `services/io.openems.edge.controller.maximeter`: xử lý Maximeter.
- `services/io.openems.edge.controller.energy.calculator`: tính toán năng lượng.

Các Bridge không thuộc riêng một app và nằm trong `platform/protocols/`:

- `io.openems.edge.bridge.iec6205621`
- `io.openems.edge.bridge.edmi`
- `io.openems.edge.bridge.dlms`

## Elster A1700

`Bridge.IEC62056-21.Serial` sở hữu và tuần tự hóa truy cập serial port;
`Meter.Elster.A1700` thực hiện sign-on, P0/P2 và các workflow native-register.

Với MOXA UPort RS-485 4-wire trên Linux, cấu hình thiết bị đã kiểm thử là:

```bash
sudo setserial /dev/ttyUSB0 port 3
```

Trong Felix Web Console, tạo `Bridge.IEC62056-21.Serial` với `/dev/ttyUSB0`,
9600 baud, 7 data bits, even parity và một stop bit. Sau đó tạo
`Meter.Elster.A1700` và chọn Bridge này.

Meter component cho phép cấu hình Password 1, Password 2, Password 3 và Active
Password Level. Password phải có đúng tám ký tự ASCII và được Felix hiển thị
dưới kiểu `PASSWORD`.

Các JSON-RPC method hiện có:

- `readA1700Current`
- `readA1700Instantaneous`
- `readA1700Historical`
- `readA1700Profile`, tùy chọn `{ "selector": "0100" }`

Wire logging mặc định là `REDACTED`; chế độ `RAW` chỉ dùng chẩn đoán vì frame
P0/P2 chứa dữ liệu xác thực.
