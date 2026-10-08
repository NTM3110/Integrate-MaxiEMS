using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record ReadOnlyTargetInspection(
    Guid InspectionId,
    HostEndpoint Endpoint,
    UbuntuPreflightSnapshot Platform,
    HardwareInventorySnapshot Hardware,
    TargetInstallationState Installation,
    bool SelectedReleaseCompatible,
    string SnapshotSha256,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt);
