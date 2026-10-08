using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record ManufacturingDashboardCounts(
    int Profiles,
    int ApprovedProfiles,
    int Devices,
    int ReadyForWarehouse,
    int ReworkRequired,
    int DeploymentsToday);

public sealed record ManufacturingDeviceSummary(
    Guid DeviceId,
    DeviceSerial Serial,
    DeviceLifecycleState State,
    ManufacturingProfileReference Profile,
    ImmutableArray<DeviceEthernetPortRecord> EthernetPorts,
    DateTimeOffset UpdatedAt);

public interface IManufacturingOperationsRepository
{
    Task<ManufacturingDashboardCounts> GetDashboardAsync(CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ManufacturingProfile>> ListProfilesAsync(CancellationToken cancellationToken = default);
    Task<IReadOnlyList<ManufacturingDeviceSummary>> SearchDevicesAsync(string? query, int limit = 100,
        CancellationToken cancellationToken = default);
}
