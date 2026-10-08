using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum DeviceLifecycleState
{
    Registered,
    Inspecting,
    ReadyForDeployment,
    Deploying,
    DeploymentVerified,
    ReadyForWarehouse,
    ReworkRequired,
    Archived,
}

public static class DeviceLifecyclePolicy
{
    public static bool CanTransition(DeviceLifecycleState from, DeviceLifecycleState to) => from switch
    {
        DeviceLifecycleState.Registered => to is DeviceLifecycleState.Inspecting or DeviceLifecycleState.Archived,
        DeviceLifecycleState.Inspecting => to is DeviceLifecycleState.ReadyForDeployment
            or DeviceLifecycleState.ReworkRequired or DeviceLifecycleState.Archived,
        DeviceLifecycleState.ReadyForDeployment => to is DeviceLifecycleState.Inspecting
            or DeviceLifecycleState.Deploying or DeviceLifecycleState.ReworkRequired or DeviceLifecycleState.Archived,
        DeviceLifecycleState.Deploying => to is DeviceLifecycleState.DeploymentVerified
            or DeviceLifecycleState.ReworkRequired,
        DeviceLifecycleState.DeploymentVerified => to is DeviceLifecycleState.ReadyForWarehouse
            or DeviceLifecycleState.ReworkRequired,
        DeviceLifecycleState.ReadyForWarehouse => to is DeviceLifecycleState.Inspecting
            or DeviceLifecycleState.Archived,
        DeviceLifecycleState.ReworkRequired => to is DeviceLifecycleState.Inspecting or DeviceLifecycleState.Archived,
        _ => false,
    };

    public static void EnsureTransition(DeviceLifecycleState from, DeviceLifecycleState to)
    {
        if (!CanTransition(from, to))
        {
            throw new InvalidOperationException($"Device lifecycle cannot transition from {from} to {to}.");
        }
    }
}

public sealed record DeviceEthernetPortRecord(
    string PhysicalLabel,
    string LinuxName,
    MacAddress PermanentMac,
    MacAddress CurrentMac,
    bool IsSerialSource,
    DateTimeOffset ObservedAt);

public sealed record DeviceRecord(
    Guid DeviceId,
    DeviceSerial Serial,
    DeviceLifecycleState State,
    ManufacturingProfileReference Profile,
    ImmutableArray<DeviceEthernetPortRecord> EthernetPorts,
    string? AssetId,
    string? ProjectCode,
    string? Customer,
    string? Site,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);
