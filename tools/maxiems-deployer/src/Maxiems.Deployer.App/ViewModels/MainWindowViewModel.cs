using System.Collections.ObjectModel;
using System.IO;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.App.ViewModels.Operations;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels;

public sealed partial class MainWindowViewModel : ObservableObject, IDisposable
{
    private readonly IWizardDeploymentOrchestrator orchestrator;
    private readonly IWizardTrustEvaluator trustEvaluator;
    private readonly TimeProvider timeProvider;
    private readonly SynchronizationContext context;
    private CancellationTokenSource? deploymentCancellation;
    private ITimer? elapsedTimer;
    private WizardPage currentPage;
    private string host = string.Empty;
    private int port = 22;
    private string username = "admin";
    private string privateKeyPath = string.Empty;
    private string manifestPath = string.Empty;
    private string validationMessage = string.Empty;
    private bool isBusy;
    private bool replacementRiskAcknowledged;
    private HostTrustDecision? trustDecision;
    private DateTimeOffset? deploymentStartedAt;
    private TimeSpan elapsed;
    private string currentPhase = "Not started";
    private string? tailscaleLoginUrl;
    private bool enableTailscale;
    private bool useAdvancedAccess;
    private bool enableModbusProfile;

    public MainWindowViewModel(
        IWizardDeploymentOrchestrator orchestrator,
        IWizardHistory historyStore,
        IWizardTrustEvaluator trustEvaluator,
        IWizardReportLauncher reportLauncher,
        TimeProvider? timeProvider = null,
        SynchronizationContext? synchronizationContext = null,
        Func<string, bool>? fileExists = null,
        IWizardSshIdentityProvider? identityProvider = null,
        IWizardComponentProfileProvider? profileProvider = null,
        IManufacturingOperationsRepository? operationsRepository = null,
        IDeploymentRunRepository? deploymentRunRepository = null,
        IManufacturingProfileRepository? manufacturingProfileRepository = null,
        IManufacturingWorkflowBackend? manufacturingWorkflowBackend = null,
        Maxiems.Deployer.Infrastructure.Manufacturing.Storage.ManufacturingBackupService? backupService = null,
        Maxiems.Deployer.Infrastructure.Manufacturing.Storage.ManufacturingExportService? exportService = null,
        IAppLocalizer? localizer = null,
        Func<CancellationToken, Task<string?>>? currentReleaseIdProvider = null)
    {
        this.orchestrator = orchestrator;
        this.historyStore = historyStore;
        this.trustEvaluator = trustEvaluator;
        this.reportLauncher = reportLauncher;
        this.timeProvider = timeProvider ?? TimeProvider.System;
        this.context = synchronizationContext ?? SynchronizationContext.Current ?? new();
        this.fileExists = fileExists ?? File.Exists;
        this.identityProvider = identityProvider;
        this.profileProvider = profileProvider;
        this.Timeline = new(Enum.GetValues<DeploymentPhase>()
            .Where(phase => phase is not DeploymentPhase.Complete)
            .Select(phase => new TimelineRowViewModel(phase)));
        this.NextCommand = new(this.GoNext, this.CanGoNext);
        this.BackCommand = new(this.GoBack, () => !this.IsBusy && this.CurrentPage is not WizardPage.Target);
        this.CancelCommand = new(this.Cancel, () => this.IsBusy);
        this.HistoryCommand = new(() => this.CurrentPage = WizardPage.History, () => !this.IsBusy);
        this.OpenHistoryReportCommand = new(this.OpenSelectedHistory, () => this.SelectedHistory is not null);
        var empty = new EmptyManufacturingRepositories();
        this.Shell = new(operationsRepository ?? empty, deploymentRunRepository ?? empty, () => this.IsBusy,
            manufacturingProfileRepository, manufacturingWorkflowBackend, trustEvaluator, this.timeProvider,
            backupService, exportService, localizer, currentReleaseIdProvider);
    }

    public RelayCommand NextCommand
    {
        get;
    }
    public RelayCommand BackCommand
    {
        get;
    }
    public RelayCommand CancelCommand
    {
        get;
    }
    public RelayCommand HistoryCommand
    {
        get;
    }
    public RelayCommand OpenHistoryReportCommand
    {
        get;
    }
    public OperationsShellViewModel Shell
    {
        get;
    }
    public ObservableCollection<TimelineRowViewModel> Timeline
    {
        get;
    }
    public WizardPage CurrentPage
    {
        get => this.currentPage; set
        {
            if (this.SetProperty(ref this.currentPage, value))
            {
                this.Notify(nameof(this.CurrentPageIndex));
                this.RefreshCommands();
            }
        }
    }
    public int CurrentPageIndex
    {
        get => (int)this.CurrentPage; set => this.CurrentPage = (WizardPage)value;
    }
    public string Host
    {
        get => this.host; set
        {
            if (this.SetProperty(ref this.host, value))
                this.RefreshCommands();
        }
    }
    public int Port
    {
        get => this.port; set
        {
            if (this.SetProperty(ref this.port, value))
                this.RefreshCommands();
        }
    }
    public string Username
    {
        get => this.username; set
        {
            if (this.SetProperty(ref this.username, value))
                this.RefreshCommands();
        }
    }
    public string PrivateKeyPath
    {
        get => this.privateKeyPath; set
        {
            if (this.SetProperty(ref this.privateKeyPath, value))
                this.RefreshCommands();
        }
    }
    public string ManifestPath
    {
        get => this.manifestPath;
        set
        {
            if (this.SetProperty(ref this.manifestPath, value))
                this.Notify(nameof(this.ComponentProfileSummary));
        }
    }
    public string ValidationMessage
    {
        get => this.validationMessage; private set => this.SetProperty(ref this.validationMessage, value);
    }
    public bool IsBusy
    {
        get => this.isBusy; private set
        {
            if (this.SetProperty(ref this.isBusy, value))
            {
                this.RefreshCommands();
                this.Shell?.RefreshBusyState();
            }
        }
    }
    public bool ReplacementRiskAcknowledged
    {
        get => this.replacementRiskAcknowledged; set
        {
            if (this.SetProperty(ref this.replacementRiskAcknowledged, value))
                this.RefreshCommands();
        }
    }
    public HostTrustDecision? TrustDecision
    {
        get => this.trustDecision; private set
        {
            if (this.SetProperty(ref this.trustDecision, value))
            {
                this.Notify(nameof(this.TrustWarning));
                this.RefreshCommands();
            }
        }
    }
    public string TrustWarning => this.TrustDecision?.Status == HostTrustStatus.Changed
        ? "The SSH key changed. This can mean the device was reinstalled or a man-in-the-middle attack is in progress. Compare the old and new fingerprints before replacing trust."
        : "Confirm this first-seen SSH fingerprint using an out-of-band source before continuing.";
    public DateTimeOffset? DeploymentStartedAt
    {
        get => this.deploymentStartedAt; private set => this.SetProperty(ref this.deploymentStartedAt, value);
    }
    public TimeSpan Elapsed
    {
        get => this.elapsed; private set => this.SetProperty(ref this.elapsed, value);
    }
    public string CurrentPhase
    {
        get => this.currentPhase; private set => this.SetProperty(ref this.currentPhase, value);
    }
    public string? TailscaleLoginUrl
    {
        get => this.tailscaleLoginUrl; private set => this.SetProperty(ref this.tailscaleLoginUrl, value);
    }
    public bool EnableTailscale
    {
        get => this.enableTailscale; set => this.SetProperty(ref this.enableTailscale, value);
    }
    public bool UseAdvancedAccess
    {
        get => this.useAdvancedAccess;
        set
        {
            if (!this.SetProperty(ref this.useAdvancedAccess, value))
                return;
            this.Notify(nameof(this.AccessModeSummary));
            this.Notify(nameof(this.ComponentProfileSummary));
            this.RefreshCommands();
        }
    }
    public bool EnableModbusProfile
    {
        get => this.enableModbusProfile;
        set
        {
            if (!this.SetProperty(ref this.enableModbusProfile, value))
                return;
            this.Notify(nameof(this.ComponentProfileSummary));
        }
    }
    public string AccessModeSummary => this.UseAdvancedAccess ? "Technician key" : "Automatic worker pairing";
    public string ComponentProfileSummary => this.UseAdvancedAccess
        ? string.IsNullOrWhiteSpace(this.ManifestPath) ? "Standard MAXiEMS" : "Custom manifest"
        : this.EnableModbusProfile ? "MAXiEMS + Modbus TCP" : "Standard MAXiEMS";

    public void SetTrustDecision(HostTrustDecision decision)
    {
        this.TrustDecision = decision;
        this.ReplacementRiskAcknowledged = false;
        this.CurrentPage = WizardPage.Trust;
    }

    public async Task EvaluateTrustAsync(CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(this.Host) || this.Port is < 1 or > 65535)
        {
            this.ValidationMessage = "Enter a valid SSH host and port before connecting.";
            return;
        }
        try
        {
            this.ValidationMessage = string.Empty;
            var decision = await this.trustEvaluator.EvaluateAsync(
                new(this.Host, this.Port), this.Username, cancellationToken);
            this.SetTrustDecision(decision);
        }
        catch (Exception exception)
        {
            this.ValidationMessage = exception.Message;
        }
    }

    public async Task ConfirmTrustAsync(CancellationToken cancellationToken = default)
    {
        if (this.TrustDecision is null || this.TrustDecision.Status != HostTrustStatus.KnownMatch
            && !this.ReplacementRiskAcknowledged)
        {
            this.ValidationMessage = "Explicit fingerprint confirmation is required.";
            return;
        }
        await this.trustEvaluator.ConfirmAsync(this.TrustDecision, cancellationToken);
        this.CurrentPage = WizardPage.Credentials;
    }

    public void RejectTrust()
    {
        this.TrustDecision = null;
        this.CurrentPage = WizardPage.Target;
    }

    public void RefreshElapsed()
    {
        if (this.DeploymentStartedAt is { } started)
            this.Elapsed = this.timeProvider.GetUtcNow() - started;
    }

    private bool CanGoNext() => !this.IsBusy && this.CurrentPage switch
    {
        WizardPage.Target => !string.IsNullOrWhiteSpace(this.Host) && this.Port is >= 1 and <= 65535,
        WizardPage.Trust => this.TrustDecision?.Status == HostTrustStatus.KnownMatch
            || this.TrustDecision is not null && this.ReplacementRiskAcknowledged,
        WizardPage.Credentials => !string.IsNullOrWhiteSpace(this.Username)
            && (!this.UseAdvancedAccess || !string.IsNullOrWhiteSpace(this.PrivateKeyPath)),
        WizardPage.Manifest or WizardPage.Review => true,
        _ => false,
    };

    private void GoNext()
    {
        this.ValidationMessage = string.Empty;
        if (this.CurrentPage == WizardPage.Credentials && this.UseAdvancedAccess && !this.ValidateKeyPair())
            return;
        this.CurrentPage = this.CurrentPage switch
        {
            WizardPage.Target => WizardPage.Trust,
            WizardPage.Trust => WizardPage.Credentials,
            WizardPage.Credentials => WizardPage.Manifest,
            WizardPage.Manifest => WizardPage.Review,
            _ => this.CurrentPage,
        };
    }

    private void GoBack() => this.CurrentPage = this.CurrentPage switch
    {
        WizardPage.Trust => WizardPage.Target,
        WizardPage.Credentials => WizardPage.Trust,
        WizardPage.Manifest => WizardPage.Credentials,
        WizardPage.Review => WizardPage.Manifest,
        WizardPage.History => WizardPage.Target,
        WizardPage.Result => WizardPage.Review,
        _ => this.CurrentPage,
    };

    private void Cancel() => this.deploymentCancellation?.Cancel();

    private async Task ApplyAsync(WizardProgressUpdate update)
    {
        this.CurrentPhase = update.Phase.ToString();
        this.CaptureResult(update);
        this.TailscaleLoginUrl = update.TailscaleLoginUrl;
        var row = this.Timeline.SingleOrDefault(item => item.Phase == update.Phase);
        if (row is not null)
        {
            row.Outcome = update.Outcome;
            row.Message = update.OperatorMessage;
            if (update.Outcome == DeploymentPhaseOutcome.Running)
                row.StartedAt = update.Timestamp;
            if (update.Outcome is DeploymentPhaseOutcome.Passed or DeploymentPhaseOutcome.Failed
                or DeploymentPhaseOutcome.RolledBack or DeploymentPhaseOutcome.Skipped)
                row.CompletedAt = update.Timestamp;
            row.NotifyDuration();
        }
        if (update.CompletedHistory is { } history)
        {
            for (var index = this.History.Count - 1; index >= 0; index--)
            {
                if (this.History[index].DeploymentId == history.DeploymentId)
                    this.History.RemoveAt(index);
            }
            this.History.Add(history);
            this.Notify(nameof(this.FilteredHistory));
            this.Notify(nameof(this.HistoryStatus));
            await this.historyStore.AppendAsync(history, CancellationToken.None);
        }
    }

    private void ResetProgress()
    {
        this.ValidationMessage = string.Empty;
        this.ResetResult();
        this.CurrentPhase = "Not started";
        this.TailscaleLoginUrl = null;
        foreach (var row in this.Timeline)
        {
            row.Outcome = DeploymentPhaseOutcome.Pending;
            row.StartedAt = null;
            row.CompletedAt = null;
            row.Message = null;
            row.NotifyDuration();
        }
    }

    private void RefreshCommands()
    {
        this.NextCommand.RaiseCanExecuteChanged();
        this.BackCommand.RaiseCanExecuteChanged();
        this.CancelCommand.RaiseCanExecuteChanged();
        this.HistoryCommand.RaiseCanExecuteChanged();
    }

    public void Dispose()
    {
        this.elapsedTimer?.Dispose();
        this.deploymentCancellation?.Dispose();
    }
}
