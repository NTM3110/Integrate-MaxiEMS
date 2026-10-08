using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public enum DeploymentTargetProfile
{
    Real,
    Demo,
}

public sealed record DeploymentExecutionRequest(
    HostEndpoint Endpoint,
    string BootstrapUsername,
    ReadOnlyMemory<char> BootstrapPassword,
    string PrivateKeyPath,
    ReadOnlyMemory<char> PrivateKeyPassphrase,
    ReadOnlyMemory<char> AdministratorPassword,
    string PayloadPath,
    string? ComponentManifestPath,
    bool ConfigureTailscale,
    string ReportsDirectory)
{
    public ManufacturingDeploymentContext? ManufacturingContext
    {
        get; init;
    }
    public TargetIdentityExpectation? InspectedIdentity
    {
        get; init;
    }
    public ManufacturingReportContext? ManufacturingReport
    {
        get; init;
    }
    public bool RequireRebootAcceptance
    {
        get; init;
    }
    public bool KeepFailedReleaseForInspection
    {
        get; init;
    }
    public DeploymentTargetProfile TargetProfile
    {
        get; init;
    } = DeploymentTargetProfile.Real;
}

public sealed record DeploymentExecutionEvent(
    DeploymentPhase Phase,
    DeploymentPhaseOutcome Outcome,
    DateTimeOffset Timestamp,
    string? OperatorMessage = null,
    Uri? TailscaleLoginUri = null);

public sealed record DeploymentExecutionResult(
    DeploymentRunRecord Record,
    string JsonReportPath,
    string HtmlReportPath)
{
    public ReportReference Reports { get; init; } = new(JsonReportPath, HtmlReportPath);
}

public sealed record DeploymentEngineOptions(
    TimeSpan TailscaleTimeout,
    TimeSpan TailscalePollInterval)
{
    public static DeploymentEngineOptions Default
    {
        get;
    } = new(
        TimeSpan.FromMinutes(5),
        TimeSpan.FromSeconds(2));

    public void Validate()
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(this.TailscaleTimeout, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(this.TailscalePollInterval, TimeSpan.Zero);
    }
}
