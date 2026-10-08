# Deployer trên Linux

Build trên máy có SDK đúng version trong `global.json`:

```bash
./publish-linux.sh /path/to/new/linux-release
./verify-linux-release.sh /path/to/new/linux-release
```

Artifact `linux-x64` có runtime đi kèm; máy điều khiển không cần cài .NET.
Script build chỉ chạy project CLI và test CLI, không build WPF. Payload BMS
được tạo riêng bởi pipeline đóng gói, rồi đưa vào cùng bộ phát hành.

## Deploy sang Ubuntu 24.04

Chuẩn bị private key và public key cùng tên có hậu tố `.pub`. Giữ private key
chỉ đọc được bởi chủ sở hữu. Tài khoản bootstrap trên thiết bị cần quyền sudo.

```bash
./maxiems-deployer deploy --host 192.168.1.50 --port 22 --user operator \
  --payload /path/to/payload.zip --identity "$HOME/.ssh/id_ed25519"
```

Lần kết nối đầu, đối chiếu fingerprint SSH với thiết bị rồi trả lời `y` hoặc
`yes` tại prompt `Trust this host key? [y/N]:`. CLI pin đúng fingerprint vừa
probe. Key thay đổi bị từ chối; không tự động thay key tin cậy.
Sau đó CLI hỏi mật khẩu SSH/sudo, passphrase của key và mật khẩu quản trị
OpenEMS bằng input ẩn. Mật khẩu quản trị OpenEMS là bắt buộc và không được để
trống; dùng lại khi cập nhật. Đây là tài khoản quản trị nội bộ, tách khỏi BMS
`admin/admin`.
Không truyền mật khẩu qua argument hoặc file JSON. CLI yêu cầu terminal tương tác.

`--manifest <components.json>` cung cấp cấu hình component tùy chọn.
`--require-reboot` yêu cầu reboot và kiểm tra sau reboot trong transaction.
Không thêm tùy chọn này nếu chưa muốn reboot thiết bị.

CLI kiểm tra local input trước khi probe SSH. Nếu file thiếu, error nêu đúng
`Payload ZIP`, `SSH private key`, `SSH public key sidecar` hoặc
`Optional component manifest`. Path payload/manifest được hiển thị để sửa lệnh;
path private key và sidecar được redact trong output.

Trust store và report nằm ở `$XDG_DATA_HOME/maxiems-deployer`, mặc định
`~/.local/share/maxiems-deployer`; thư mục chỉ cho phép user hiện tại truy cập.
Report dùng cơ chế redaction của deployment engine. Ctrl+C yêu cầu hủy và
cho engine thực hiện rollback; không kill cưỡng bức tiến trình trong lúc rollback.

Exit code: `0` thành công, `1` lỗi deployment, `2` lỗi input/trust,
`3` đã rollback, `130` đã hủy. Không coi rollback là cài đặt thành công.

Xem `BMS_DEPLOY.md` để tạo gói đầy đủ. Kết quả appliance được ghi riêng trong
report deployment; không suy ra thành công từ build hoặc `--help`.
