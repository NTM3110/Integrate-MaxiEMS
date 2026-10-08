using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public static class RemoteCommandEncoder
{
    private static readonly Dictionary<RemoteProgram, ProgramPolicy> Programs =
        new Dictionary<RemoteProgram, ProgramPolicy>
        {
            [RemoteProgram.DeployHelper] = new("/usr/bin/sudo -n /usr/local/libexec/maxiems-deploy-helper", int.MaxValue),
            [RemoteProgram.NetworkHelper] = new("/usr/bin/sudo -n /usr/local/sbin/maxiems-network-helper", int.MaxValue),
            [RemoteProgram.OsReleaseProbe] = new("/usr/bin/cat /etc/os-release", 0),
            [RemoteProgram.ArchitectureProbe] = new("/usr/bin/dpkg --print-architecture", 0),
            [RemoteProgram.DiskProbe] = new("/usr/bin/df --block-size=1 --output=avail /", 0),
            [RemoteProgram.MemoryProbe] = new("/usr/bin/free --bytes", 0),
            [RemoteProgram.TimeProbe] = new("/usr/bin/date --utc --iso-8601=seconds", 0),
            [RemoteProgram.DnsProbe] = new("/usr/bin/getent ahosts archive.ubuntu.com", 0),
            [RemoteProgram.SudoProbe] = new("/usr/bin/sudo -n /usr/bin/true", 0),
            [RemoteProgram.PortProbe] = new("/usr/bin/ss --no-header --tcp --listening --numeric", 0),
            [RemoteProgram.ServiceProbe] = new(
                "/usr/bin/systemctl is-active maxiems-edge.service nginx.service openems.service", 0),
            [RemoteProgram.LegacyServiceEnabledProbe] = new("/usr/bin/systemctl is-enabled openems.service", 0),
            [RemoteProgram.AccountProbe] = new("/usr/bin/getent passwd maxiems-deploy openems", 0),
            [RemoteProgram.CurrentIdentityProbe] = new("/usr/bin/id", 0),
            [RemoteProgram.BroadSudoDenialProbe] = new("/usr/bin/sudo -n /usr/bin/id", 0),
            [RemoteProgram.SudoAllowlistProbe] = new("/usr/bin/sudo -n -l", 0),
            [RemoteProgram.NetworkLinksProbe] = new("/usr/sbin/ip -json -details link show", 0),
            [RemoteProgram.PermanentMacProbe] = new("/usr/sbin/ethtool --show-permaddr", 1,
                ValidateInterfaceName),
            [RemoteProgram.CpuInventoryProbe] = new("/usr/bin/lscpu --json --bytes", 0),
            [RemoteProgram.BlockDeviceProbe] = new(
                "/usr/bin/lsblk --json --bytes --output NAME,TYPE,SIZE,MODEL,SERIAL,TRAN,MOUNTPOINTS", 0),
            [RemoteProgram.KernelInventoryProbe] = new("/usr/bin/uname --kernel-release --machine", 0),
            [RemoteProgram.BoardInventoryProbe] = new(
                "/usr/bin/cat /sys/devices/virtual/dmi/id/product_name", 0),
            [RemoteProgram.DeviceTreeBoardInventoryProbe] = new(
                "/usr/bin/cat /sys/firmware/devicetree/base/model", 0),
            [RemoteProgram.ImageVersionProbe] = new("/usr/bin/cat /etc/maxiems-image", 0),
            [RemoteProgram.UsbInventoryProbe] = new("/usr/bin/lsusb", 0),
            [RemoteProgram.PciInventoryProbe] = new("/usr/bin/lspci -mm -nn -k", 0),
            [RemoteProgram.ModemInventoryProbe] = new("/usr/bin/mmcli --list-modems --output-json", 0),
            [RemoteProgram.WirelessInventoryProbe] = new("/usr/sbin/iw dev", 0),
            [RemoteProgram.BluetoothInventoryProbe] = new(
                "/usr/bin/timeout --signal=TERM 5s /usr/bin/bluetoothctl list", 0),
            [RemoteProgram.DeviceNodeInventoryProbe] = new("/usr/bin/find /dev -maxdepth 1 -type c", 0),
            [RemoteProgram.SdioInventoryProbe] = new(
                "/usr/bin/find /sys/bus/sdio/devices -mindepth 1 -maxdepth 1 -type l", 0),
            [RemoteProgram.ManagedServiceEnabledProbe] = new(
                "/usr/bin/systemctl is-enabled maxiems-edge.service", 0),
            [RemoteProgram.CurrentReleaseProbe] = new("/usr/bin/readlink -f /opt/maxiems/current", 0),
            [RemoteProgram.ConfigurationPresentProbe] = new(
                "/usr/bin/test -d /var/lib/maxiems/shared/config", 0),
            [RemoteProgram.RollbackReleaseProbe] = new(
                "/usr/bin/find /opt/maxiems/releases -mindepth 1 -maxdepth 1 -type d -printf '%f\\n'", 0),
            [RemoteProgram.FailureProbe] = new("/usr/bin/false", 0),
        };

    public static string Encode(RemoteCommandRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (!Programs.TryGetValue(request.Program, out var policy))
        {
            throw new ArgumentOutOfRangeException(nameof(request), request.Program, "Remote program is not allowlisted.");
        }
        if (request.Arguments.Length > policy.MaximumArguments)
        {
            throw new ArgumentException("This typed remote operation does not accept arguments.", nameof(request));
        }
        if (policy.ArgumentValidator is not null && request.Arguments.Any(argument =>
            !policy.ArgumentValidator(argument.Value)))
        {
            throw new ArgumentException("Remote command contains an invalid typed argument.", nameof(request));
        }
        if (request.Timeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(request), "Remote command timeout must be positive.");
        }
        return string.Join(' ', new[] { policy.Command }.Concat(request.Arguments.Select(argument => Quote(argument.Value))));
    }

    private static bool ValidateInterfaceName(string value) => value.Length is >= 1 and <= 15
        && value.All(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-');

    private static string Quote(string value)
    {
        if (value.Contains('\0'))
        {
            throw new ArgumentException("Remote command arguments must not contain NUL characters.", nameof(value));
        }
        return $"'{value.Replace("'", "'\\''", StringComparison.Ordinal)}'";
    }

    private sealed record ProgramPolicy(
        string Command,
        int MaximumArguments,
        Func<string, bool>? ArgumentValidator = null);
}
