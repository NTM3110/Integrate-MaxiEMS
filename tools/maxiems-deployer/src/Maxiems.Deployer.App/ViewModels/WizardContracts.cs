using System.Security;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App.ViewModels;

public enum WizardPage
{
    Target,
    Trust,
    Credentials,
    Manifest,
    Review,
    Progress,
    Result,
    History,
}

public enum HistorySort
{
    Newest,
    Target,
    Status,
}

public sealed record WizardDeploymentRequest(
    HostEndpoint Endpoint,
    string Username,
    string PrivateKeyPath,
    string? ManifestPath,
    SecureString BootstrapPassword,
    SecureString AdministratorPassword,
    SecureString PrivateKeyPassphrase,
    bool EnableTailscale = false);

public sealed record WizardProgressUpdate(
    DeploymentPhase Phase,
    DeploymentPhaseOutcome Outcome,
    DateTimeOffset Timestamp,
    string? OperatorMessage = null,
    string? TailscaleLoginUrl = null,
    DeploymentHistorySummary? CompletedHistory = null);

public interface IWizardDeploymentOrchestrator
{
    IAsyncEnumerable<WizardProgressUpdate> RunAsync(
        WizardDeploymentRequest request,
        CancellationToken cancellationToken);
}

public interface IWizardTrustEvaluator
{
    Task<HostTrustDecision> EvaluateAsync(
        HostEndpoint endpoint,
        string username,
        CancellationToken cancellationToken);

    Task ConfirmAsync(HostTrustDecision decision, CancellationToken cancellationToken);
}

public interface IWizardHistory
{
    Task<IReadOnlyList<DeploymentHistorySummary>> LoadAsync(CancellationToken cancellationToken);

    Task AppendAsync(DeploymentHistorySummary summary, CancellationToken cancellationToken);
}

public interface IWizardReportLauncher
{
    void Open(string path);
}

public interface IWizardSshIdentityProvider
{
    WizardSshIdentity GetOrCreate();
}

public interface IWizardComponentProfileProvider
{
    string? ResolveManifest(bool enableModbusProfile);
}

public sealed class WizardSshIdentity(string privateKeyPath, SecureString privateKeyPassphrase) : IDisposable
{
    public string PrivateKeyPath { get; } = privateKeyPath;
    public SecureString PrivateKeyPassphrase { get; } = privateKeyPassphrase;

    public void Dispose() => this.PrivateKeyPassphrase.Dispose();
}

public sealed class TimelineRowViewModel(DeploymentPhase phase) : ObservableObject
{
    private DeploymentPhaseOutcome outcome = DeploymentPhaseOutcome.Pending;
    private DateTimeOffset? startedAt;
    private DateTimeOffset? completedAt;
    private string? message;

    public DeploymentPhase Phase { get; } = phase;
    public DeploymentPhaseOutcome Outcome
    {
        get => this.outcome; set => this.SetProperty(ref this.outcome, value);
    }
    public DateTimeOffset? StartedAt
    {
        get => this.startedAt; set => this.SetProperty(ref this.startedAt, value);
    }
    public DateTimeOffset? CompletedAt
    {
        get => this.completedAt; set => this.SetProperty(ref this.completedAt, value);
    }
    public string? Message
    {
        get => this.message; set => this.SetProperty(ref this.message, value);
    }
    public TimeSpan? Duration => this.StartedAt is { } start && this.CompletedAt is { } end ? end - start : null;

    public void NotifyDuration() => this.Notify(nameof(this.Duration));
}
