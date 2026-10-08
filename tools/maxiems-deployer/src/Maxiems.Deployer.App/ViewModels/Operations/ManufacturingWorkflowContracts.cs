using System.Collections.Immutable;
using System.Security;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public enum ManufacturingWorkflowStep
{
    Profile,
    Target,
    Inspect,
    LanMapping,
    Checklist,
    Review,
    Deploying,
    Result,
}

public sealed record ManufacturingInspectRequest(
    ManufacturingProfile Profile,
    HostEndpoint Endpoint,
    string Username,
    SecureString BootstrapPassword);

public sealed record ManufacturingInspectionDraft(
    ReadOnlyTargetInspection ReadOnlyInspection,
    ManufacturingProfile Profile,
    LocalReleaseDescriptor Release,
    string BootstrapUsername);

public sealed record ManufacturingReviewBundle(
    TargetInspection Inspection,
    DeviceRecord Device,
    bool IsNewDevice,
    DeploymentActionPlan ActionPlan,
    ManufacturingDeploymentContext DeploymentContext,
    TargetIdentityExpectation IdentityExpectation,
    ManufacturingReportContext ReportContext,
    string BootstrapUsername,
    bool ConfigureTailscale,
    bool RequireRebootAcceptance)
{
    public ImmutableArray<string> BlockingReasons => this.Inspection.Checklist.Results
        .Where(result => result.Outcome == InspectionCheckOutcome.BlockingFailure)
        .Select(result => $"{result.Code}: {result.Observed}").Concat(this.ActionPlan.BlockingReasons)
        .Distinct(StringComparer.Ordinal).ToImmutableArray();
    public bool IsReady => this.BlockingReasons.Length == 0 && this.Inspection.DerivedSerial is not null;
}

public sealed record ManufacturingDeployResult(
    DeploymentFinalState FinalState,
    Guid RunId,
    ReportReference Reports,
    string Summary);

public interface IManufacturingWorkflowBackend
{
    Task<ManufacturingInspectionDraft> InspectAsync(ManufacturingInspectRequest request,
        CancellationToken cancellationToken = default);
    Task<ManufacturingReviewBundle> ReviewAsync(ManufacturingInspectionDraft draft,
        EthernetMappingConfirmation mapping, CancellationToken cancellationToken = default);
    Task<ManufacturingDeployResult> DeployAsync(ManufacturingReviewBundle review,
        SecureString bootstrapPassword, SecureString administratorPassword,
        Action<WizardProgressUpdate>? progress = null, CancellationToken cancellationToken = default);
}
