namespace Maxiems.Deployer.Core.Manufacturing;

public static class DeviceSerialFactory
{
    public static DeviceSerial? TryCreate(HardwareInventorySnapshot hardware, ManufacturingProfile profile,
        EthernetMappingConfirmation? mapping)
    {
        ArgumentNullException.ThrowIfNull(hardware);
        ArgumentNullException.ThrowIfNull(profile);
        if (mapping is null || mapping.PhysicalToLinuxName.Count != profile.EthernetPorts.Length
            || mapping.PhysicalToLinuxName.Values.Distinct(StringComparer.Ordinal).Count()
                != mapping.PhysicalToLinuxName.Count)
            return null;
        var serialLabel = profile.EthernetPorts.Single(port => port.IsSerialSource).PhysicalLabel;
        if (!mapping.PhysicalToLinuxName.TryGetValue(serialLabel, out var linuxName))
            return null;
        var port = hardware.EthernetPorts.SingleOrDefault(item => item.LinuxName.Equals(linuxName, StringComparison.Ordinal));
        return port is
        {
            PermanentMac: { IsLocallyAdministered: false } permanent,
            Evidence.Availability: EvidenceAvailability.Observed
        }
            ? DeviceSerial.FromSerialSource(permanent) : null;
    }
}
