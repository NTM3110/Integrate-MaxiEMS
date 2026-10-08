using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record ManufacturingReportContext(
    Guid DeviceId,
    DeviceSerial Serial,
    ImmutableArray<DeviceEthernetPortRecord> EthernetPorts,
    ManufacturingProfileReference Profile,
    Guid InspectionId,
    string InspectionSha256,
    string HardwareSnapshotSha256,
    InspectionChecklistSnapshot Checklist,
    DeploymentActionKind PlannedAction,
    DeploymentActionKind ObservedAction,
    LocalReleaseDescriptor Release,
    EngineeringIdentity Engineer,
    HostEndpoint Endpoint,
    string DeployerVersion,
    string? GitCommit,
    string LocalTimeZoneId,
    Guid? RelatedRunId = null,
    string? RunRelation = null);
