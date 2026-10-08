using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public enum OperationsSection
{
    Dashboard,
    Profiles,
    Devices,
    Deployments,
    History,
    Settings,
}

public sealed class OperationsShellViewModel : ObservableObject
{
    private readonly Func<bool> deploymentBusy;
    private readonly IAppLocalizer localizer;
    private OperationsSection currentSection = OperationsSection.Dashboard;
    private string navigationMessage = string.Empty;
    private LanguageOption selectedLanguage;

    public OperationsShellViewModel(IManufacturingOperationsRepository repository,
        IDeploymentRunRepository runRepository, Func<bool> deploymentBusy,
        IManufacturingProfileRepository? profileWriter = null,
        IManufacturingWorkflowBackend? workflowBackend = null,
        IWizardTrustEvaluator? trustEvaluator = null,
        TimeProvider? timeProvider = null,
        ManufacturingBackupService? backupService = null,
        ManufacturingExportService? exportService = null,
        IAppLocalizer? localizer = null,
        Func<CancellationToken, Task<string?>>? currentReleaseIdProvider = null)
    {
        this.deploymentBusy = deploymentBusy;
        this.localizer = localizer ?? new AppLocalizationService();
        this.localizer.SetLanguage(this.localizer.LanguageCode);
        this.selectedLanguage = this.localizer.Languages.First(item => item.Code == this.localizer.LanguageCode);
        this.Dashboard = new(repository, this.localizer);
        this.Profiles = new(repository, profileWriter, localizer: this.localizer,
            currentReleaseIdProvider: currentReleaseIdProvider);
        this.Devices = new(repository, this.localizer);
        this.History = new(runRepository, this.localizer);
        this.Settings = new(backupService, exportService, this.localizer);
        this.Deployment = new(workflowBackend ?? new UnavailableManufacturingWorkflowBackend(),
            trustEvaluator ?? new UnavailableTrustEvaluator(), () => this.Profiles.Profiles.ToArray(), timeProvider,
            this.localizer);
        this.Profiles.ProfileSaved += (_, _) => this.Deployment.NotifyProfilesChanged();
        this.NavigateCommand = new(this.Navigate, parameter => !this.IsDeploymentBusy()
            && Enum.TryParse<OperationsSection>(parameter?.ToString(), out _));
        this.NewDeploymentCommand = new(() => this.Navigate(OperationsSection.Deployments));
        this.FocusSearchCommand = new(() => this.FocusSearchRequested?.Invoke(this, EventArgs.Empty));
        this.OpenSettingsCommand = new(() => this.Navigate(OperationsSection.Settings));
    }

    public event EventHandler? FocusSearchRequested;
    public DashboardOperationsViewModel Dashboard
    {
        get;
    }
    public ProfileOperationsViewModel Profiles
    {
        get;
    }
    public DeviceOperationsViewModel Devices
    {
        get;
    }
    public HistoryOperationsViewModel History
    {
        get;
    }
    public SettingsOperationsViewModel Settings
    {
        get;
    }
    public ManufacturingDeploymentViewModel Deployment
    {
        get;
    }
    public ParameterizedRelayCommand NavigateCommand
    {
        get;
    }
    public RelayCommand NewDeploymentCommand
    {
        get;
    }
    public RelayCommand FocusSearchCommand
    {
        get;
    }
    public RelayCommand OpenSettingsCommand
    {
        get;
    }
    public OperationsSection CurrentSection
    {
        get => this.currentSection;
        private set => this.SetProperty(ref this.currentSection, value);
    }
    public string NavigationMessage
    {
        get => this.navigationMessage; private set => this.SetProperty(ref this.navigationMessage, value);
    }
    public IReadOnlyList<LanguageOption> Languages => this.localizer.Languages;
    public LanguageOption SelectedLanguage
    {
        get => this.selectedLanguage;
        set
        {
            if (value is null || !this.SetProperty(ref this.selectedLanguage, value))
                return;
            this.localizer.SetLanguage(value.Code);
            this.Dashboard.RefreshLanguage();
            this.Profiles.RefreshLanguage();
            this.Devices.RefreshLanguage();
            this.History.RefreshLanguage();
            this.Settings.RefreshLanguage();
            this.Deployment.RefreshLanguage();
            if (this.NavigationMessage.Length > 0)
                this.NavigationMessage = this.localizer.Text("Status.NavigationBlocked");
        }
    }

    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        await this.Dashboard.LoadAsync(cancellationToken).ConfigureAwait(false);
        await this.Profiles.LoadAsync(cancellationToken).ConfigureAwait(false);
        this.Deployment.NotifyProfilesChanged();
        await this.Devices.LoadAsync(cancellationToken).ConfigureAwait(false);
        await this.History.LoadAsync(cancellationToken).ConfigureAwait(false);
    }

    public void RefreshBusyState() => this.NavigateCommand.RaiseCanExecuteChanged();

    private void Navigate(object? parameter)
    {
        if (parameter is OperationsSection section)
        {
            this.Navigate(section);
        }
        else if (Enum.TryParse<OperationsSection>(parameter?.ToString(), out var parsed))
        {
            this.Navigate(parsed);
        }
    }

    private void Navigate(OperationsSection section)
    {
        if (this.IsDeploymentBusy())
        {
            this.NavigationMessage = this.localizer.Text("Status.NavigationBlocked");
            return;
        }
        this.NavigationMessage = string.Empty;
        this.CurrentSection = section;
    }

    private bool IsDeploymentBusy() => this.deploymentBusy() || this.Deployment.IsBusy;

    private sealed class UnavailableManufacturingWorkflowBackend : IManufacturingWorkflowBackend
    {
        private static InvalidOperationException Error() => new("Manufacturing deployment is not configured.");
        public Task<ManufacturingInspectionDraft> InspectAsync(ManufacturingInspectRequest request,
            CancellationToken cancellationToken = default) => Task.FromException<ManufacturingInspectionDraft>(Error());
        public Task<ManufacturingReviewBundle> ReviewAsync(ManufacturingInspectionDraft draft,
            EthernetMappingConfirmation mapping, CancellationToken cancellationToken = default) =>
            Task.FromException<ManufacturingReviewBundle>(Error());
        public Task<ManufacturingDeployResult> DeployAsync(ManufacturingReviewBundle review,
            System.Security.SecureString bootstrapPassword, System.Security.SecureString administratorPassword,
            Action<WizardProgressUpdate>? progress = null, CancellationToken cancellationToken = default) =>
            Task.FromException<ManufacturingDeployResult>(Error());
    }

    private sealed class UnavailableTrustEvaluator : IWizardTrustEvaluator
    {
        public Task<HostTrustDecision> EvaluateAsync(Maxiems.Deployer.Core.Contracts.HostEndpoint endpoint,
            string username, CancellationToken cancellationToken) => Task.FromException<HostTrustDecision>(
            new InvalidOperationException("SSH trust evaluation is not configured."));
        public Task ConfirmAsync(HostTrustDecision decision, CancellationToken cancellationToken) =>
            Task.FromException(new InvalidOperationException("SSH trust evaluation is not configured."));
    }
}
