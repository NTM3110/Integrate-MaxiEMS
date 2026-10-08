using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum InspectionCheckRequirement
{
    Required,
    Optional,
    Forbidden,
}

public enum InspectionCheckOutcome
{
    Passed,
    Warning,
    NotApplicable,
    BlockingFailure,
}

public sealed record InspectionCheckResult(
    string Code,
    InspectionCheckRequirement Requirement,
    InspectionCheckOutcome Outcome,
    string Observed,
    string Expected,
    string EvidenceSummary);

public sealed record InspectionChecklistSnapshot(
    string TemplateId,
    int TemplateVersion,
    ImmutableArray<InspectionCheckResult> Results)
{
    public bool IsReady => this.Results.All(result => result.Outcome != InspectionCheckOutcome.BlockingFailure);
}

public sealed record TargetInspection(
    Guid InspectionId,
    HostEndpoint Endpoint,
    ManufacturingProfileReference Profile,
    HardwareInventorySnapshot Hardware,
    InspectionChecklistSnapshot Checklist,
    DeviceSerial? DerivedSerial,
    string SnapshotSha256,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt);

public sealed record FatTemplateReference(string TemplateId, int Version);

public sealed record FatResultReference(Guid ResultId, FatTemplateReference Template, DateTimeOffset CompletedAt);
