using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public sealed class SettingsOperationsViewModel(
    ManufacturingBackupService? backupService = null,
    ManufacturingExportService? exportService = null,
    IAppLocalizer? localizer = null) : ObservableObject
{
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private string backupDirectory = string.Empty;
    private string restoreArchivePath = string.Empty;
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.SettingsInitial");
    private string statusKey = "Status.SettingsInitial";
    private object?[] statusArguments = [];
    private bool advancedVisible;
    private bool destructiveActionConfirmed;
    public string BackupDirectory
    {
        get => this.backupDirectory; set => this.SetProperty(ref this.backupDirectory, value);
    }
    public string RestoreArchivePath
    {
        get => this.restoreArchivePath; set => this.SetProperty(ref this.restoreArchivePath, value);
    }
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }
    public bool AdvancedVisible
    {
        get => this.advancedVisible; set => this.SetProperty(ref this.advancedVisible, value);
    }
    public bool DestructiveActionConfirmed
    {
        get => this.destructiveActionConfirmed;
        set => this.SetProperty(ref this.destructiveActionConfirmed, value);
    }
    public RelayCommand CreateBackupCommand => new(async () => await this.CreateBackupAsync());
    public RelayCommand ExportCommand => new(async () => await this.ExportAsync());
    public RelayCommand PreviewRestoreCommand => new(async () => await this.PreviewRestoreAsync());
    public RelayCommand RestoreCommand => new(async () => await this.RestoreAsync());

    public async Task CreateBackupAsync(CancellationToken cancellationToken = default)
    {
        if (backupService is null)
        {
            this.SetStatus("Status.BackupUnavailable");
            return;
        }
        try
        {
            var path = await backupService.CreateAsync(this.BackupDirectory, cancellationToken).ConfigureAwait(false);
            this.SetStatus("Status.BackupCreated", path);
        }
        catch (Exception exception)
        {
            this.SetStatus("Status.BackupFailed", exception.Message);
        }
    }

    public async Task ExportAsync(CancellationToken cancellationToken = default)
    {
        if (exportService is null)
        {
            this.SetStatus("Status.ExportUnavailable");
            return;
        }
        try
        {
            var data = await exportService.ExportDeviceHistoryAsync(this.BackupDirectory, cancellationToken)
                .ConfigureAwait(false);
            var profiles = await exportService.ExportProfilesAsync(this.BackupDirectory, cancellationToken)
                .ConfigureAwait(false);
            this.SetStatus("Status.ExportComplete", data.DeviceCount, data.RunCount, profiles.Count);
        }
        catch (Exception exception)
        {
            this.SetStatus("Status.ExportFailed", exception.Message);
        }
    }

    public async Task PreviewRestoreAsync(CancellationToken cancellationToken = default)
    {
        if (backupService is null)
        {
            this.SetStatus("Status.RestoreUnavailable");
            return;
        }
        try
        {
            var preview = await backupService.PreviewAsync(this.RestoreArchivePath, cancellationToken).ConfigureAwait(false);
            if (preview.CanRestore)
            {
                this.SetStatus("Status.RestorePreviewPassed",
                    preview.Manifest.DatabaseSchemaVersion, preview.Manifest.CreatedAt);
            }
            else
            {
                this.SetStatus("Status.RestorePreviewRejected");
            }
        }
        catch (Exception exception)
        {
            this.SetStatus("Status.RestorePreviewFailed", exception.Message);
        }
    }

    public async Task RestoreAsync(CancellationToken cancellationToken = default)
    {
        if (!this.AdvancedVisible || !this.DestructiveActionConfirmed)
        {
            this.SetStatus("Status.RestoreConfirmation");
            return;
        }
        if (backupService is null)
        {
            this.SetStatus("Status.RestoreUnavailable");
            return;
        }
        try
        {
            var result = await backupService.RestoreAsync(this.RestoreArchivePath, cancellationToken).ConfigureAwait(false);
            this.SetStatus("Status.RestoreComplete", result.PreRestoreBackupPath);
        }
        catch (Exception exception)
        {
            this.SetStatus("Status.RestoreFailed", exception.Message);
        }
    }

    public void RefreshLanguage() => this.Status = this.localizer.Text(this.statusKey, this.statusArguments);

    private void SetStatus(string key, params object?[] arguments)
    {
        this.statusKey = key;
        this.statusArguments = arguments;
        this.Status = this.localizer.Text(key, arguments);
    }
}
