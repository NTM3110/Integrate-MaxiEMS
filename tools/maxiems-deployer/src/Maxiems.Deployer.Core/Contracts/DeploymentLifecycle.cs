using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public enum DeploymentPhase
{
    ValidateInput,
    ConnectBootstrap,
    Preflight,
    ProvisionIdentity,
    ReconnectRestricted,
    InstallPrerequisites,
    StageRelease,
    VerifyRemotePayload,
    ActivateRelease,
    StartServices,
    ConfigureTailscale,
    ReconcileComponents,
    HealthCheck,
    FailureDiagnostics,
    Rollback,
    Complete,
}

public enum DeploymentPhaseOutcome
{
    Pending,
    Running,
    Passed,
    Failed,
    Skipped,
    RolledBack,
}

public enum DeploymentFinalState
{
    Succeeded,
    Cancelled,
    RolledBack,
    Failed,
}

public sealed record DeploymentPhaseResult(
    DeploymentPhase Phase,
    DeploymentPhaseOutcome Outcome,
    DateTimeOffset StartedAt,
    DateTimeOffset? CompletedAt,
    TimeSpan? Duration,
    string? ErrorCode,
    string? OperatorMessage);

public sealed record DeploymentResult(
    Guid DeploymentId,
    ReleaseId ReleaseId,
    DeploymentFinalState FinalState,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    TimeSpan Duration,
    ImmutableArray<DeploymentPhaseResult> Phases);
