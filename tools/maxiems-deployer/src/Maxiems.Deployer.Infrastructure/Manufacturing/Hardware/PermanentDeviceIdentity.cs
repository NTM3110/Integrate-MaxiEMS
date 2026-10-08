using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

public static class PermanentDeviceIdentity
{
    public static DeviceSerial? TryDerive(
        IEnumerable<EthernetPortInventory> observedPorts,
        string confirmedLinuxInterface)
    {
        ArgumentNullException.ThrowIfNull(observedPorts);
        ArgumentException.ThrowIfNullOrWhiteSpace(confirmedLinuxInterface);
        var port = observedPorts.SingleOrDefault(item => item.LinuxName.Equals(confirmedLinuxInterface,
            StringComparison.Ordinal));
        return port?.PermanentMac is { IsLocallyAdministered: false } permanent
            && port.Evidence.Availability == EvidenceAvailability.Observed
                ? DeviceSerial.FromSerialSource(permanent) : null;
    }
}
