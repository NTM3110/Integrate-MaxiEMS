using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Windows;

namespace Maxiems.Deployer.App.Services;

public sealed record LanguageOption(string Code, string DisplayName);

public interface IAppLocalizer
{
    string LanguageCode
    {
        get;
    }
    IReadOnlyList<LanguageOption> Languages
    {
        get;
    }
    string Text(string key, params object?[] arguments);
    void SetLanguage(string code);
}

public sealed class AppLocalizationService : IAppLocalizer
{
    private static readonly IReadOnlyList<LanguageOption> SupportedLanguages = [
        new("en", "English"), new("vi", "Tiếng Việt"),
    ];
    private static readonly IReadOnlyDictionary<string, string> English = StatusText.English;
    private static readonly IReadOnlyDictionary<string, string> Vietnamese = StatusText.Vietnamese;
    private readonly string? settingsPath;
    private string languageCode;

    public AppLocalizationService(string? settingsPath = null)
    {
        this.settingsPath = settingsPath;
        this.languageCode = LoadLanguage(settingsPath);
    }

    public string LanguageCode => this.languageCode;
    public IReadOnlyList<LanguageOption> Languages => SupportedLanguages;

    public string Text(string key, params object?[] arguments)
    {
        var source = this.languageCode == "vi" ? Vietnamese : English;
        var format = source.TryGetValue(key, out var value) ? value
            : English.TryGetValue(key, out value) ? value : key;
        return arguments.Length == 0 ? format : string.Format(CultureInfo.CurrentCulture, format, arguments);
    }

    public void SetLanguage(string code)
    {
        var normalized = SupportedLanguages.Any(item => item.Code == code) ? code : "en";
        CultureInfo.CurrentUICulture = normalized == "vi" ? CultureInfo.GetCultureInfo("vi-VN")
            : CultureInfo.GetCultureInfo("en-US");
        if (this.languageCode == normalized)
        {
            this.ApplyResources();
            return;
        }
        this.languageCode = normalized;
        this.ApplyResources();
        this.Save();
    }

    public void ApplyResources()
    {
        if (Application.Current is null)
            return;
        var dictionaries = Application.Current.Resources.MergedDictionaries;
        var previous = dictionaries.FirstOrDefault(dictionary => dictionary.Source?.OriginalString
            .Contains("Localization/Strings.", StringComparison.OrdinalIgnoreCase) == true);
        if (previous is not null)
            dictionaries.Remove(previous);
        dictionaries.Add(new()
        {
            Source = new(
            $"/Maxiems.Deployer;component/Localization/Strings.{this.languageCode}.xaml", UriKind.Relative)
        });
    }

    private void Save()
    {
        if (this.settingsPath is null)
            return;
        var directory = Path.GetDirectoryName(this.settingsPath)!;
        Directory.CreateDirectory(directory);
        var temporary = this.settingsPath + $".{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(new LocalizationSettings(this.languageCode)));
            File.Move(temporary, this.settingsPath, true);
        }
        catch (IOException)
        {
            // Language persistence must never prevent an engineer from using the deployer.
        }
        catch (UnauthorizedAccessException)
        {
            // Keep the in-memory selection when the local settings directory is read-only.
        }
        finally
        {
            try
            {
                if (File.Exists(temporary))
                    File.Delete(temporary);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    private static string LoadLanguage(string? path)
    {
        if (path is null || !File.Exists(path))
            return "en";
        try
        {
            var value = JsonSerializer.Deserialize<LocalizationSettings>(File.ReadAllText(path))?.Language;
            return SupportedLanguages.Any(item => item.Code == value) ? value! : "en";
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
        {
            return "en";
        }
    }

    private sealed record LocalizationSettings(string Language);

    private static class StatusText
    {
        internal static IReadOnlyDictionary<string, string> English
        {
            get;
        } = new Dictionary<string, string>
        {
            ["Status.LoadingDashboard"] = "Loading manufacturing records...",
            ["Status.LoadingDevices"] = "Loading devices...",
            ["Status.LoadingHistory"] = "Loading deployment history...",
            ["Status.LoadingProfiles"] = "Loading profiles...",
            ["Status.SelectProfile"] = "Select an approved profile to begin.",
            ["Status.TrustKnown"] = "SSH host identity is trusted. Enter credentials and inspect.",
            ["Status.TrustConfirm"] = "Compare and confirm the displayed SSH fingerprint before inspection.",
            ["Status.TrustAcknowledge"] = "Acknowledge that you compared the SSH fingerprint before confirming trust.",
            ["Status.TrustConfirmed"] = "SSH host identity confirmed. Read-only inspection is ready.",
            ["Status.TrustTimeout"] = "SSH identity verification timed out. Check the IP address, port 22 and network connection, then retry.",
            ["Status.TrustFailed"] = "SSH identity verification failed: {0}",
            ["Status.TrustConfirmFailed"] = "SSH fingerprint confirmation failed: {0}",
            ["Status.PasswordRequired"] = "OpenEMS administrator password is required.",
            ["Status.Inspecting"] = "Reading hardware, operating system and installed services. This can take up to 2 minutes.",
            ["Status.InspectComplete"] = "Inspection complete. Confirm the physical LAN1/LAN2 mapping.",
            ["Status.InspectTimeout"] = "Read-only inspection timed out after 2 minutes. Check the SSH connection and retry; no device changes were made.",
            ["Status.InspectCancelled"] = "Read-only inspection was cancelled safely. No device changes were made.",
            ["Status.InspectFailed"] = "Inspection failed: {0}",
            ["Status.ReviewReady"] = "All required checks passed. Review the action before deployment.",
            ["Status.ReviewBlocked"] = "Inspection is blocked. Resolve every listed issue and inspect again.",
            ["Status.ReviewFailed"] = "Review failed: {0}",
            ["Status.Cancelled"] = "Deployment cancelled safely.",
            ["Status.DeployFailed"] = "Deployment failed: {0}",
            ["Status.NoDevices"] = "No devices registered. Start with New deployment.",
            ["Status.DeviceCount"] = "{0} device(s) tracked locally.",
            ["Status.NoMatches"] = "No matching devices.",
            ["Status.Matches"] = "{0} device(s).",
            ["Status.NoRuns"] = "No deployment records.",
            ["Status.Runs"] = "{0} run(s).",
            ["Status.StoreUnavailable"] = "Manufacturing store unavailable: {0}",
            ["Status.DeviceStoreUnavailable"] = "Device store unavailable: {0}",
            ["Status.HistoryUnavailable"] = "History unavailable: {0}",
            ["Status.ProfileStoreUnavailable"] = "Profile store unavailable: {0}",
            ["Status.NoProfiles"] = "No manufacturing profiles. Open Advanced to create a draft.",
            ["Status.ProfileCount"] = "{0} profile revision(s).",
            ["Status.ProfileEditingUnavailable"] = "Profile editing is unavailable.",
            ["Status.ProfileConfirmAdvanced"] = "Confirm Advanced profile maintenance before saving a revision.",
            ["Status.ProfileRevisionRule"] = "A profile revision must keep the profile ID and increment the version by one.",
            ["Status.ProfileStateRule"] = "The revision state must be {0}.",
            ["Status.ProfileSaved"] = "Saved immutable {0} revision v{1}.",
            ["Status.ProfileChooseMode"] = "Choose New profile or New revision first.",
            ["Status.ProfileIdExists"] = "This profile ID already exists. Select it and create a new revision instead.",
            ["Status.ProfileReleaseUnavailable"] = "The verified embedded release ID is unavailable. Use a published final-qa build.",
            ["Status.NavigationBlocked"] = "Finish or safely cancel the active deployment before leaving this screen.",
            ["Status.SettingsInitial"] = "Configure an explicit backup destination before production use.",
            ["Status.BackupUnavailable"] = "Backup service is unavailable.",
            ["Status.BackupCreated"] = "Verified backup created: {0}",
            ["Status.BackupFailed"] = "Backup failed: {0}",
            ["Status.ExportUnavailable"] = "Export service is unavailable.",
            ["Status.ExportComplete"] = "Exported {0} device(s), {1} run(s) and {2} profile(s).",
            ["Status.ExportFailed"] = "Export failed: {0}",
            ["Status.RestoreUnavailable"] = "Restore service is unavailable.",
            ["Status.RestorePreviewPassed"] = "Restore preview passed: schema {0}, created {1:u}.",
            ["Status.RestorePreviewRejected"] = "Restore preview failed hash, integrity or schema validation.",
            ["Status.RestorePreviewFailed"] = "Restore preview failed: {0}",
            ["Status.RestoreConfirmation"] = "Open Advanced maintenance and confirm recovery risk before restore.",
            ["Status.RestoreComplete"] = "Restore completed. Pre-restore database retained at {0}",
            ["Status.RestoreFailed"] = "Restore failed safely: {0}",
            ["Status.EndpointInvalid"] = "Enter a valid host or IP and SSH port.",
            ["Status.DeployComplete"] = "Deployment completed successfully.",
            ["Status.DeployNoChange"] = "The device already matches this verified release. No change was required.",
            ["Value.NotDerived"] = "Not derived",
            ["Value.NotPlanned"] = "Not planned",
            ["Value.NotSelected"] = "Not selected",
            ["Value.SerialPreview"] = "Serial preview: {0} (from WAN0)",
            ["Value.SerialUnavailable"] = "WAN0 must use eth1 with a proven permanent MAC before continuing.",
            ["Step.Summary"] = "Step {0} of 5",
        };
        internal static IReadOnlyDictionary<string, string> Vietnamese
        {
            get;
        } = new Dictionary<string, string>
        {
            ["Status.LoadingDashboard"] = "Đang tải dữ liệu sản xuất...",
            ["Status.LoadingDevices"] = "Đang tải danh sách thiết bị...",
            ["Status.LoadingHistory"] = "Đang tải lịch sử triển khai...",
            ["Status.LoadingProfiles"] = "Đang tải cấu hình...",
            ["Status.SelectProfile"] = "Chọn cấu hình sản xuất đã phê duyệt để bắt đầu.",
            ["Status.TrustKnown"] = "Danh tính SSH đã được tin cậy. Nhập thông tin truy cập và kiểm tra thiết bị.",
            ["Status.TrustConfirm"] = "Đối chiếu và xác nhận fingerprint SSH trước khi kiểm tra.",
            ["Status.TrustAcknowledge"] = "Hãy xác nhận bạn đã đối chiếu địa chỉ và fingerprint SSH.",
            ["Status.TrustConfirmed"] = "Đã xác nhận danh tính SSH. Có thể bắt đầu kiểm tra chỉ đọc.",
            ["Status.TrustTimeout"] = "Xác minh SSH đã hết thời gian. Kiểm tra địa chỉ IP, cổng 22 và kết nối mạng rồi thử lại.",
            ["Status.TrustFailed"] = "Xác minh danh tính SSH thất bại: {0}",
            ["Status.TrustConfirmFailed"] = "Xác nhận fingerprint SSH thất bại: {0}",
            ["Status.PasswordRequired"] = "Bắt buộc nhập mật khẩu quản trị OpenEMS.",
            ["Status.Inspecting"] = "Đang đọc phần cứng, hệ điều hành và các dịch vụ đã cài. Quá trình có thể mất tối đa 2 phút.",
            ["Status.InspectComplete"] = "Đã kiểm tra xong. Hãy xác nhận ánh xạ cổng vật lý LAN1/LAN2.",
            ["Status.InspectTimeout"] = "Kiểm tra chỉ đọc đã hết thời gian sau 2 phút. Kiểm tra kết nối SSH rồi thử lại; thiết bị chưa bị thay đổi.",
            ["Status.InspectCancelled"] = "Đã hủy kiểm tra chỉ đọc an toàn. Thiết bị chưa bị thay đổi.",
            ["Status.InspectFailed"] = "Kiểm tra thất bại: {0}",
            ["Status.ReviewReady"] = "Tất cả kiểm tra bắt buộc đều đạt. Hãy xem lại hành động trước khi triển khai.",
            ["Status.ReviewBlocked"] = "Thiết bị chưa đạt. Xử lý toàn bộ lỗi bên dưới rồi kiểm tra lại.",
            ["Status.ReviewFailed"] = "Không thể đánh giá: {0}",
            ["Status.Cancelled"] = "Đã hủy triển khai an toàn.",
            ["Status.DeployFailed"] = "Triển khai thất bại: {0}",
            ["Status.NoDevices"] = "Chưa có thiết bị. Hãy bắt đầu bằng Triển khai mới.",
            ["Status.DeviceCount"] = "Đang quản lý {0} thiết bị trên máy này.",
            ["Status.NoMatches"] = "Không tìm thấy thiết bị phù hợp.",
            ["Status.Matches"] = "Tìm thấy {0} thiết bị.",
            ["Status.NoRuns"] = "Chưa có lịch sử triển khai.",
            ["Status.Runs"] = "Có {0} lần triển khai.",
            ["Status.StoreUnavailable"] = "Không thể truy cập kho dữ liệu sản xuất: {0}",
            ["Status.DeviceStoreUnavailable"] = "Không thể truy cập dữ liệu thiết bị: {0}",
            ["Status.HistoryUnavailable"] = "Không thể truy cập lịch sử: {0}",
            ["Status.ProfileStoreUnavailable"] = "Không thể truy cập dữ liệu cấu hình: {0}",
            ["Status.NoProfiles"] = "Chưa có cấu hình sản xuất. Mở phần Nâng cao để tạo bản nháp.",
            ["Status.ProfileCount"] = "Có {0} bản sửa đổi cấu hình.",
            ["Status.ProfileEditingUnavailable"] = "Chức năng chỉnh sửa cấu hình chưa sẵn sàng.",
            ["Status.ProfileConfirmAdvanced"] = "Xác nhận bảo trì cấu hình Nâng cao trước khi lưu bản sửa đổi.",
            ["Status.ProfileRevisionRule"] = "Bản sửa đổi phải giữ nguyên ID cấu hình và tăng phiên bản đúng một đơn vị.",
            ["Status.ProfileStateRule"] = "Trạng thái bản sửa đổi phải là {0}.",
            ["Status.ProfileSaved"] = "Đã lưu bản sửa đổi {0} bất biến, phiên bản v{1}.",
            ["Status.ProfileChooseMode"] = "Hãy chọn Thêm cấu hình hoặc Tạo phiên bản mới trước.",
            ["Status.ProfileIdExists"] = "ID cấu hình này đã tồn tại. Hãy chọn cấu hình đó và tạo phiên bản mới.",
            ["Status.ProfileReleaseUnavailable"] = "Không đọc được Release ID đã xác minh. Hãy dùng bản final-qa đã publish.",
            ["Status.NavigationBlocked"] = "Hãy hoàn tất hoặc hủy an toàn lần triển khai đang chạy trước khi rời màn hình.",
            ["Status.SettingsInitial"] = "Hãy cấu hình rõ thư mục sao lưu trước khi sử dụng trong sản xuất.",
            ["Status.BackupUnavailable"] = "Dịch vụ sao lưu chưa sẵn sàng.",
            ["Status.BackupCreated"] = "Đã tạo bản sao lưu được xác minh: {0}",
            ["Status.BackupFailed"] = "Sao lưu thất bại: {0}",
            ["Status.ExportUnavailable"] = "Dịch vụ xuất dữ liệu chưa sẵn sàng.",
            ["Status.ExportComplete"] = "Đã xuất {0} thiết bị, {1} lần triển khai và {2} cấu hình.",
            ["Status.ExportFailed"] = "Xuất dữ liệu thất bại: {0}",
            ["Status.RestoreUnavailable"] = "Dịch vụ khôi phục chưa sẵn sàng.",
            ["Status.RestorePreviewPassed"] = "Kiểm tra khôi phục đạt: schema {0}, tạo lúc {1:u}.",
            ["Status.RestorePreviewRejected"] = "Kiểm tra khôi phục không đạt do hash, tính toàn vẹn hoặc schema.",
            ["Status.RestorePreviewFailed"] = "Kiểm tra khôi phục thất bại: {0}",
            ["Status.RestoreConfirmation"] = "Mở bảo trì Nâng cao và xác nhận rủi ro trước khi khôi phục.",
            ["Status.RestoreComplete"] = "Khôi phục hoàn tất. Cơ sở dữ liệu trước khôi phục được giữ tại {0}",
            ["Status.RestoreFailed"] = "Khôi phục đã dừng an toàn: {0}",
            ["Status.EndpointInvalid"] = "Nhập host hoặc IP và cổng SSH hợp lệ.",
            ["Status.DeployComplete"] = "Triển khai đã hoàn tất thành công.",
            ["Status.DeployNoChange"] = "Thiết bị đã đúng phiên bản đã xác minh. Không cần thay đổi.",
            ["Value.NotDerived"] = "Chưa sinh",
            ["Value.NotPlanned"] = "Chưa xác định",
            ["Value.NotSelected"] = "Chưa chọn",
            ["Value.SerialPreview"] = "Serial dự kiến: {0} (lấy từ WAN0)",
            ["Value.SerialUnavailable"] = "WAN0 phải dùng eth1 có MAC cố định đã xác minh trước khi tiếp tục.",
            ["Step.Summary"] = "Bước {0}/5",
        };
    }
}
