using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Core.Lifecycle;

public sealed record DeploymentRunRecord(
    Guid DeploymentId,
    DeploymentFinalState FinalState,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    TimeSpan Duration,
    ImmutableArray<DeploymentPhaseResult> Phases,
    ImmutableArray<RedactedLogEvent> Logs)
{
    public int ReportFormatVersion { get; init; } = 1;
    public ReleaseId? ReleaseId
    {
        get; init;
    }
    public ImmutableDictionary<string, string> ArtifactHashes
    {
        get; init;
    } =
        ImmutableDictionary<string, string>.Empty;
    public ImmutableDictionary<string, string> Versions
    {
        get; init;
    } =
        ImmutableDictionary<string, string>.Empty;
    public ImmutableArray<DeploymentCheckEvidence> Checks { get; init; } = [];
    public HostTrustAudit? HostTrust
    {
        get; init;
    }
    public RollbackAudit? Rollback
    {
        get; init;
    }
    public ImmutableArray<DeploymentCheckEvidence> Evidence { get; init; } = [];
    public ManufacturingReportContext? Manufacturing
    {
        get; init;
    }
}

public sealed record DeploymentCheckEvidence(string Code, bool Passed, string Summary);

public sealed record HostTrustAudit(
    HostEndpoint Endpoint,
    HostTrustStatus Status,
    string Algorithm,
    string? PreviousFingerprint,
    string ObservedFingerprint,
    DateTimeOffset ConfirmedAt);

public sealed record RollbackAudit(
    bool Attempted,
    bool PriorReleaseRestored,
    string? PriorReleaseId,
    string Summary);
