using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record StoredInspection(
    TargetInspection Inspection,
    Guid? DeviceId,
    string SnapshotJson,
    EngineeringIdentity Engineer);

public sealed record StoredDeploymentRun(
    Guid RunId,
    Guid DeviceId,
    Guid InspectionId,
    HostEndpoint Endpoint,
    ReleaseId ReleaseId,
    DeploymentActionKind Action,
    DeploymentFinalState FinalState,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    ReportReference Reports,
    EngineeringIdentity Engineer)
{
    public DeploymentActionKind PlannedAction { get; init; } = Action;
    public DeploymentActionKind ObservedAction { get; init; } = Action;
    public Guid? RetryOfRunId
    {
        get; init;
    }
}

public sealed record ManufacturingHistoryEntry(
    Guid RunId,
    Guid? DeviceId,
    DeviceSerial? Serial,
    ImmutableArray<MacAddress> PermanentMacAddresses,
    string? ProfileDisplayName,
    HostEndpoint Endpoint,
    ReleaseId ReleaseId,
    DeploymentActionKind PlannedAction,
    DeploymentActionKind ObservedAction,
    DeploymentFinalState FinalState,
    DateTimeOffset StartedAt,
    ReportReference Reports,
    ReportIntegrityState ReportIntegrity,
    Guid? RelatedRunId,
    string? RunRelation);

public interface IManufacturingProfileRepository
{
    Task<ManufacturingProfile> AddAsync(
        string canonicalJson,
        string createdBy,
        DateTimeOffset createdAt,
        CancellationToken cancellationToken = default);

    Task<string?> FindCanonicalJsonAsync(
        ManufacturingProfileReference reference,
        CancellationToken cancellationToken = default);
}

public interface IDeviceRepository
{
    Task RegisterAsync(DeviceRecord device, string evidenceSummary, CancellationToken cancellationToken = default);
    Task<DeviceRecord?> FindBySerialAsync(DeviceSerial serial, CancellationToken cancellationToken = default);
    Task UpdateLifecycleAsync(Guid deviceId, DeviceLifecycleState expected, DeviceLifecycleState targetState,
        DateTimeOffset updatedAt, CancellationToken cancellationToken = default);
}

public interface IInspectionRepository
{
    Task AppendAsync(StoredInspection inspection, CancellationToken cancellationToken = default);
}

public interface IDeploymentRunRepository
{
    Task AppendAsync(StoredDeploymentRun run, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ManufacturingHistoryEntry>> SearchAsync(
        string? query,
        int limit = 100,
        CancellationToken cancellationToken = default);
}
