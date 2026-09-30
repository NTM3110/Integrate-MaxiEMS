# Không gian mã nguồn theo ứng dụng

Các bundle giữ nguyên tên `io.openems.*`, Java package, component ID và lệnh
Gradle. Thư mục bên ngoài thể hiện ứng dụng sở hữu mã nguồn.

```text
apps/
├── bms/
│   ├── controllers/  # Bộ tính toán và controller BMS
│   ├── services/     # String lifecycle, dữ liệu, persistence, maintenance BMS
│   ├── endpoints/    # JSON/WebSocket API và xác thực BMS
│   ├── drivers/      # Huasu TA/TC
│   └── simulator/    # BMS demo
├── ems/
│   ├── controllers/
│   ├── services/
│   ├── inverters/
│   ├── meters/
│   └── loggers/
├── profile-meter/
│   ├── meters/       # Elster A1700, EDMI và Landis DLMS
│   └── services/     # Maximeter và tính toán năng lượng/profile
└── shared/
    ├── serial/
    ├── tcp/
    ├── ethernet/
    ├── lte/
    └── system/
```

Các Bridge dùng chung nằm trong `platform/protocols/`; ba application bundle
và `.bndrun` nằm trong `platform/applications/`. Một bundle chỉ có một thư mục
nguồn; không sao chép bundle dùng chung vào từng app.

Mỗi thư mục chức năng chứa các bundle hoàn chỉnh với `bnd.bnd`, `src/`, `test/`.
Thêm phụ thuộc bằng tên bundle trong `-buildpath`, không dùng đường dẫn tương đối
giữa các app. Xem [shared](shared/README.md), [profile-meter](profile-meter/README.md)
và [cấu trúc workspace](../doc/workspace-layout.md).
