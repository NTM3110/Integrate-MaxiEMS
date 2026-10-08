using System.Collections.Immutable;
using System.Collections.ObjectModel;
using System.Security;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public sealed class ManufacturingDeploymentViewModel(
    IManufacturingWorkflowBackend backend,
    IWizardTrustEvaluator trust,
    Func<IReadOnlyList<ManufacturingProfile>> profiles,
    TimeProvider? timeProvider = null,
    IAppLocalizer? localizer = null,
    TimeSpan? inspectionTimeout = null) : ObservableObject, IDisposable
{
    private static readonly TimeSpan DefaultInspectionTimeout = TimeSpan.FromMinutes(2);
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private readonly TimeSpan inspectionTimeout = inspectionTimeout ?? DefaultInspectionTimeout;
    private SecureString? bootstrapCredential;
    private SecureString? administratorCredential;
    private ManufacturingWorkflowStep step;
    private ManufacturingProfile? selectedProfile;
    private string host = string.Empty;
    private int port = 22;
    private string username = "admin";
    private string serialSourceLinuxName = string.Empty;
    private string secondaryLinuxName = string.Empty;
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.SelectProfile");
    private string statusKey = "Status.SelectProfile";
    private object?[] statusArguments = [];
    private bool isBusy;
    private bool trustConfirmed;
    private bool trustRiskAcknowledged;
    private HostTrustDecision? trustDecision;
    private ManufacturingInspectionDraft? draft;
    private ManufacturingReviewBundle? review;
    private ManufacturingDeployResult? result;
    private CancellationTokenSource? cancellation;
    private bool cancellationRequestedByOperator;

    public ObservableCollection<InspectionCheckResult> Checklist { get; } = [];
    public ObservableCollection<WizardProgressUpdate> Progress { get; } = [];
    public IReadOnlyList<ManufacturingProfile> ApprovedProfiles => profiles()
        .Where(profile => profile.State == ManufacturingProfileState.Approved).ToArray();
    public ManufacturingWorkflowStep Step
    {
        get => this.step; private set
        {
            if (this.SetProperty(ref this.step, value))
                this.Refresh();
        }
    }
    public ManufacturingProfile? SelectedProfile
    {
        get => this.selectedProfile;
        set
        {
            if (this.SetProperty(ref this.selectedProfile, value))
                this.Refresh();
        }
    }
    public string Host
    {
        get => this.host; set
        {
            if (this.SetProperty(ref this.host, value))
                this.Refresh();
        }
    }
    public int Port
    {
        get => this.port; set
        {
            if (this.SetProperty(ref this.port, value))
                this.Refresh();
        }
    }
    public string Username
    {
        get => this.username; set
        {
            if (this.SetProperty(ref this.username, value))
                this.Refresh();
        }
    }
    public string SerialSourceLinuxName
    {
        get => this.serialSourceLinuxName; set
        {
            if (this.SetProperty(ref this.serialSourceLinuxName, value))
                this.Refresh();
        }
    }
    public string SecondaryLinuxName
    {
        get => this.secondaryLinuxName; set
        {
            if (this.SetProperty(ref this.secondaryLinuxName, value))
                this.Refresh();
        }
    }
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }
    public bool IsBusy
    {
        get => this.isBusy; private set
        {
            if (this.SetProperty(ref this.isBusy, value))
                this.Refresh();
        }
    }
    public bool TrustConfirmed
    {
        get => this.trustConfirmed; private set
        {
            if (this.SetProperty(ref this.trustConfirmed, value))
                this.Refresh();
        }
    }
    public bool TrustRiskAcknowledged
    {
        get => this.trustRiskAcknowledged; set
        {
            if (this.SetProperty(ref this.trustRiskAcknowledged, value))
                this.Refresh();
        }
    }
    public HostTrustDecision? TrustDecision
    {
        get => this.trustDecision; private set
        {
            if (this.SetProperty(ref this.trustDecision, value))
                this.Refresh();
        }
    }
    public ManufacturingReviewBundle? Review => this.review;
    public ManufacturingDeployResult? Result
    {
        get => this.result; private set => this.SetProperty(ref this.result, value);
    }
    public bool HasCredentials => this.bootstrapCredential is not null && this.administratorCredential is not null;
    public IReadOnlyList<string> BlockingReasons => this.review?.BlockingReasons ?? [];
    public IReadOnlyList<string> AvailableInterfaces => this.draft?.ReadOnlyInspection.Hardware.EthernetPorts
        .Select(port => port.LinuxName).OrderBy(name => name, StringComparer.Ordinal).ToArray() ?? [];
    public string DerivedSerial => this.review?.Inspection.DerivedSerial?.Value ?? this.localizer.Text("Value.NotDerived");
    public string ActionSummary => this.review?.ActionPlan.Action.ToString() ?? this.localizer.Text("Value.NotPlanned");
    public string ReleaseSummary => this.review?.DeploymentContext.Release.ReleaseId.Value ?? this.localizer.Text("Value.NotSelected");
    public string SerialSourceSummary => this.InterfaceSummary(this.SerialSourceLinuxName);
    public string SecondarySummary => this.InterfaceSummary(this.SecondaryLinuxName);
    public string SerialPreview => this.TryCreateSerial() is { } serial
        ? this.localizer.Text("Value.SerialPreview", serial.Value)
        : this.localizer.Text("Value.SerialUnavailable");
    public bool HasApprovedProfiles => this.ApprovedProfiles.Count > 0;
    public bool HasTrustDecision => this.TrustDecision is not null;
    public bool HasInspection => this.draft is not null;
    public bool HasReview => this.review is not null;
    public bool HasProgress => this.Progress.Count > 0;
    public int CurrentStepNumber => this.Step switch
    {
        ManufacturingWorkflowStep.Profile => 1,
        ManufacturingWorkflowStep.Target => 2,
        ManufacturingWorkflowStep.Inspect => 3,
        ManufacturingWorkflowStep.LanMapping => 4,
        _ => 5,
    };
    public string StepSummary => this.localizer.Text("Step.Summary", this.CurrentStepNumber);
    public bool CanInspect => !this.IsBusy && this.SelectedProfile is not null && this.TrustConfirmed
        && !string.IsNullOrWhiteSpace(this.Host) && !string.IsNullOrWhiteSpace(this.Username);
    public bool CanConfirmMapping => !this.IsBusy && this.draft is not null && this.SerialSourceLinuxName.Length > 0
        && this.SecondaryLinuxName.Length > 0
        && !this.SerialSourceLinuxName.Equals(this.SecondaryLinuxName, StringComparison.Ordinal)
        && this.TryCreateSerial() is not null;
    public bool CanDeploy => !this.IsBusy && this.review is { IsReady: true } review
        && this.draft is { } draft && this.HasCredentials
        && review.Inspection.SnapshotSha256 == review.DeploymentContext.InspectionSha256
        && draft.Release.PayloadSha256 == review.DeploymentContext.Release.PayloadSha256;
    public RelayCommand ResetCommand => new(this.Reset, () => !this.IsBusy);
    public bool CanCancelCurrentOperation => this.IsBusy && this.cancellation is not null;

    public async Task EvaluateTrustAsync(CancellationToken cancellationToken = default)
    {
        if (!TryEndpoint(out var endpoint))
            return;
        this.IsBusy = true;
        try
        {
            this.TrustDecision = await trust.EvaluateAsync(endpoint, this.Username, cancellationToken).ConfigureAwait(false);
            this.TrustConfirmed = this.TrustDecision.Status == HostTrustStatus.KnownMatch;
            this.Step = ManufacturingWorkflowStep.Target;
            this.SetStatus(this.TrustConfirmed ? "Status.TrustKnown" : "Status.TrustConfirm");
        }
        catch (TimeoutException)
        {
            this.TrustDecision = null;
            this.TrustConfirmed = false;
            this.Step = ManufacturingWorkflowStep.Target;
            this.SetStatus("Status.TrustTimeout");
        }
        catch (OperationCanceledException)
        {
            this.TrustDecision = null;
            this.TrustConfirmed = false;
            this.Step = ManufacturingWorkflowStep.Target;
            this.SetStatus("Status.TrustTimeout");
        }
        catch (Exception exception)
        {
            this.TrustDecision = null;
            this.TrustConfirmed = false;
            this.Step = ManufacturingWorkflowStep.Target;
            this.SetStatus("Status.TrustFailed", exception.Message);
        }
        finally
        {
            this.IsBusy = false;
        }
    }

    public async Task ConfirmTrustAsync(CancellationToken cancellationToken = default)
    {
        if (this.TrustDecision is null || !this.TrustRiskAcknowledged)
        {
            this.SetStatus("Status.TrustAcknowledge");
            return;
        }
        try
        {
            await trust.ConfirmAsync(this.TrustDecision, cancellationToken).ConfigureAwait(false);
            this.TrustConfirmed = true;
            this.SetStatus("Status.TrustConfirmed");
        }
        catch (TimeoutException)
        {
            this.TrustConfirmed = false;
            this.SetStatus("Status.TrustTimeout");
        }
        catch (OperationCanceledException)
        {
            this.TrustConfirmed = false;
            this.SetStatus("Status.TrustTimeout");
        }
        catch (Exception exception)
        {
            this.TrustConfirmed = false;
            this.SetStatus("Status.TrustConfirmFailed", exception.Message);
        }
    }

    public async Task InspectAsync(SecureString bootstrapPassword, SecureString administratorPassword,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(bootstrapPassword);
        ArgumentNullException.ThrowIfNull(administratorPassword);
        if (!this.CanInspect || !TryEndpoint(out var endpoint))
            return;
        if (administratorPassword.Length == 0)
        {
            this.SetStatus("Status.PasswordRequired");
            bootstrapPassword.Dispose();
            administratorPassword.Dispose();
            return;
        }
        this.ReplaceCredentials(bootstrapPassword, administratorPassword);
        this.cancellationRequestedByOperator = false;
        this.cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        this.cancellation.CancelAfter(this.inspectionTimeout);
        this.IsBusy = true;
        this.Step = ManufacturingWorkflowStep.Inspect;
        this.SetStatus("Status.Inspecting");
        try
        {
            using var inspectionCredential = this.bootstrapCredential!.Copy();
            this.draft = await backend.InspectAsync(new(this.SelectedProfile!, endpoint, this.Username,
                inspectionCredential), this.cancellation.Token).ConfigureAwait(false);
            this.SelectDefaultWanMapping();
            this.Notify(nameof(this.AvailableInterfaces));
            this.Step = ManufacturingWorkflowStep.LanMapping;
            this.SetStatus("Status.InspectComplete");
        }
        catch (OperationCanceledException)
        {
            this.SetStatus(cancellationToken.IsCancellationRequested || this.cancellationRequestedByOperator
                ? "Status.InspectCancelled" : "Status.InspectTimeout");
            this.ClearCredentials();
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            this.SetStatus("Status.InspectFailed", exception.Message);
            this.ClearCredentials();
        }
        finally
        {
            this.cancellation.Dispose();
            this.cancellation = null;
            this.cancellationRequestedByOperator = false;
            this.IsBusy = false;
        }
    }

    public void CancelCurrentOperation()
    {
        this.cancellationRequestedByOperator = true;
        this.cancellation?.Cancel();
    }

    public async Task ConfirmMappingAsync(CancellationToken cancellationToken = default)
    {
        if (!this.CanConfirmMapping)
            return;
        this.IsBusy = true;
        try
        {
            var engineer = new EngineeringIdentity(Environment.UserName, Environment.MachineName);
            var mapping = this.CreateMapping(engineer, this.clock.GetUtcNow());
            this.review = await backend.ReviewAsync(this.draft!, mapping, cancellationToken).ConfigureAwait(false);
            this.Checklist.Clear();
            foreach (var check in this.review.Inspection.Checklist.Results)
                this.Checklist.Add(check);
            this.Notify(nameof(this.Review));
            this.Notify(nameof(this.BlockingReasons));
            this.Step = this.review.IsReady ? ManufacturingWorkflowStep.Review : ManufacturingWorkflowStep.Checklist;
            this.SetStatus(this.review.IsReady ? "Status.ReviewReady" : "Status.ReviewBlocked");
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            this.SetStatus("Status.ReviewFailed", exception.Message);
            this.review = null;
            this.Refresh();
        }
        finally
        {
            this.IsBusy = false;
        }
    }

    public async Task DeployAsync(CancellationToken cancellationToken = default)
    {
        if (!this.CanDeploy)
            return;
        this.cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        this.IsBusy = true;
        this.Step = ManufacturingWorkflowStep.Deploying;
        try
        {
            using var bootstrap = this.bootstrapCredential!.Copy();
            using var administrator = this.administratorCredential!.Copy();
            this.Result = await backend.DeployAsync(this.review!, bootstrap,
                administrator, update => { this.Progress.Add(update); this.Notify(nameof(this.HasProgress)); }, this.cancellation.Token)
                .ConfigureAwait(false);
            this.SetStatus(this.review!.ActionPlan.Action == DeploymentActionKind.NoChange
                ? "Status.DeployNoChange" : "Status.DeployComplete");
        }
        catch (OperationCanceledException)
        {
            this.SetStatus("Status.Cancelled");
        }
        catch (Exception exception)
        {
            this.SetStatus("Status.DeployFailed", exception.Message);
        }
        finally
        {
            this.ClearCredentials();
            this.cancellation.Dispose();
            this.cancellation = null;
            this.Step = ManufacturingWorkflowStep.Result;
            this.IsBusy = false;
        }
    }

    public void Dispose()
    {
        this.cancellation?.Cancel();
        this.cancellation?.Dispose();
        this.ClearCredentials();
    }

    private void ReplaceCredentials(SecureString bootstrap, SecureString administrator)
    {
        this.ClearCredentials();
        this.bootstrapCredential = bootstrap.Copy();
        this.administratorCredential = administrator.Copy();
        this.Notify(nameof(this.HasCredentials));
    }
    private void ClearCredentials()
    {
        this.bootstrapCredential?.Dispose();
        this.administratorCredential?.Dispose();
        this.bootstrapCredential = null;
        this.administratorCredential = null;
        this.Notify(nameof(this.HasCredentials));
    }
    private void Reset()
    {
        this.ClearCredentials();
        this.draft = null;
        this.review = null;
        this.Result = null;
        this.Checklist.Clear();
        this.Progress.Clear();
        this.TrustConfirmed = false;
        this.TrustRiskAcknowledged = false;
        this.Step = ManufacturingWorkflowStep.Profile;
        this.SetStatus("Status.SelectProfile");
        this.Refresh();
    }
    private bool TryEndpoint(out HostEndpoint endpoint)
    {
        try
        {
            endpoint = new(this.Host, this.Port);
            return true;
        }
        catch (ArgumentException)
        {
            this.SetStatus("Status.EndpointInvalid");
            endpoint = null!;
            return false;
        }
    }
    private void Refresh()
    {
        this.Notify(nameof(this.ApprovedProfiles));
        this.Notify(nameof(this.CanInspect));
        this.Notify(nameof(this.CanConfirmMapping));
        this.Notify(nameof(this.CanDeploy));
        this.Notify(nameof(this.CanCancelCurrentOperation));
        this.Notify(nameof(this.BlockingReasons));
        this.Notify(nameof(this.AvailableInterfaces));
        this.Notify(nameof(this.DerivedSerial));
        this.Notify(nameof(this.ActionSummary));
        this.Notify(nameof(this.ReleaseSummary));
        this.Notify(nameof(this.SerialSourceSummary));
        this.Notify(nameof(this.SecondarySummary));
        this.Notify(nameof(this.SerialPreview));
        this.Notify(nameof(this.HasApprovedProfiles));
        this.Notify(nameof(this.HasTrustDecision));
        this.Notify(nameof(this.HasInspection));
        this.Notify(nameof(this.HasReview));
        this.Notify(nameof(this.HasProgress));
        this.Notify(nameof(this.CurrentStepNumber));
        this.Notify(nameof(this.StepSummary));
    }

    public void RefreshLanguage()
    {
        this.Status = this.localizer.Text(this.statusKey, this.statusArguments);
        this.Refresh();
    }

    private void SetStatus(string key, params object?[] arguments)
    {
        this.statusKey = key;
        this.statusArguments = arguments;
        this.Status = this.localizer.Text(key, arguments);
    }

    public void NotifyProfilesChanged()
    {
        var approved = this.ApprovedProfiles;
        if (this.SelectedProfile is null && approved.Count > 0)
            this.SelectedProfile = approved[0];
        this.Refresh();
    }

    private void SelectDefaultWanMapping()
    {
        var ports = this.draft!.ReadOnlyInspection.Hardware.EthernetPorts;
        var valid = ports.Where(port => port is
        {
            PermanentMac.IsLocallyAdministered: false,
            Evidence.Availability: EvidenceAvailability.Observed
        }).ToArray();
        var serial = valid.FirstOrDefault(port => port.LinuxName.Equals("eth1", StringComparison.Ordinal))
            ?? (ports.ElementAtOrDefault(1) is { } second && valid.Contains(second) ? second : null)
            ?? valid.FirstOrDefault()
            ?? ports.FirstOrDefault(port => port.LinuxName.Equals("eth1", StringComparison.Ordinal))
            ?? ports.ElementAtOrDefault(1);
        this.SerialSourceLinuxName = serial?.LinuxName ?? string.Empty;
        this.SecondaryLinuxName = ports.FirstOrDefault(port => port.LinuxName.Equals("eth0", StringComparison.Ordinal)
            && port.LinuxName != this.SerialSourceLinuxName)?.LinuxName
            ?? ports.FirstOrDefault(port => port.LinuxName != this.SerialSourceLinuxName)?.LinuxName ?? string.Empty;
    }

    private EthernetMappingConfirmation CreateMapping(EngineeringIdentity engineer, DateTimeOffset confirmedAt)
    {
        var source = this.draft!.Profile.EthernetPorts.Single(port => port.IsSerialSource);
        var secondary = this.draft.Profile.EthernetPorts.Single(port => !port.IsSerialSource);
        return new(new Dictionary<string, string>
        {
            [source.PhysicalLabel] = this.SerialSourceLinuxName,
            [secondary.PhysicalLabel] = this.SecondaryLinuxName,
        }.ToImmutableDictionary(StringComparer.Ordinal), engineer, confirmedAt);
    }

    private DeviceSerial? TryCreateSerial() => this.draft is null ? null : DeviceSerialFactory.TryCreate(
        this.draft.ReadOnlyInspection.Hardware, this.draft.Profile,
        this.CreateMapping(new("preview", "local"), DateTimeOffset.UnixEpoch));

    private string InterfaceSummary(string linuxName)
    {
        var port = this.draft?.ReadOnlyInspection.Hardware.EthernetPorts
            .SingleOrDefault(port => port.LinuxName == linuxName);
        return port?.PermanentMac is { } mac ? $"{linuxName} — {mac.ToColonSeparated()}" : linuxName;
    }
}
