using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum LocalReleaseSource
{
    Embedded,
    Imported,
}

public enum LocalReleaseStatus
{
    Embedded,
    ImportedVerified,
    Deprecated,
    Blocked,
}

public sealed record LocalReleaseDescriptor(
    ReleaseId ReleaseId,
    string Version,
    LocalReleaseSource Source,
    LocalReleaseStatus Status,
    string ArtifactSha256,
    string PayloadSha256,
    ImmutableArray<string> SupportedUbuntuVersions,
    ImmutableArray<SupportedArchitecture> SupportedArchitectures,
    string MinimumDeployerVersion);

public enum DeploymentActionKind
{
    Deploy,
    Update,
    NoChange,
    LegacyMigration,
    Downgrade,
    BlockedConflict,
}

public sealed record DeploymentActionPlan(
    DeploymentActionKind Action,
    ReleaseId? CurrentReleaseId,
    ReleaseId DesiredReleaseId,
    bool RollbackAvailable,
    ImmutableArray<string> BlockingReasons,
    ImmutableArray<string> PlannedChanges);

public sealed record EngineeringIdentity(
    string WindowsUserName,
    string WorkstationName);

public sealed record ManufacturingDeploymentContext(
    Guid DeviceId,
    DeviceSerial Serial,
    ManufacturingProfileReference Profile,
    Guid InspectionId,
    string InspectionSha256,
    LocalReleaseDescriptor Release,
    DeploymentActionKind Action,
    EngineeringIdentity Engineer);
