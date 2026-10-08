using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record EthernetMappingConfirmation(
    ImmutableDictionary<string, string> PhysicalToLinuxName,
    EngineeringIdentity Engineer,
    DateTimeOffset ConfirmedAt);

public sealed record DeviceIdentityConflict(
    bool SerialAlreadyRegistered,
    bool PermanentMacOwnedByAnotherDevice);

public sealed record ManufacturingInspectionEvaluation(
    DeviceSerial? DerivedSerial,
    InspectionChecklistSnapshot Checklist,
    string SnapshotSha256)
{
    public bool IsReady => this.DerivedSerial is not null && this.Checklist.IsReady;
}

public interface IDeviceIdentityRegistry
{
    Task<DeviceIdentityConflict> FindConflictAsync(
        DeviceSerial serial,
        MacAddress permanentMac,
        Guid? currentDeviceId = null,
        CancellationToken cancellationToken = default);
}
