using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

[TestClass]
[TestCategory("SshTransport")]
public sealed class RemoteCommandEncoderTests
{
    [TestMethod]
    public void ArgumentVectorIsQuotedWithoutExposingGeneralShellApi()
    {
        var request = new RemoteCommandRequest(
            RemoteProgram.DeployHelper,
            [new("stage"), new("value with spaces"), new("eth0;id"), new("it's")],
            TimeSpan.FromSeconds(5));

        var encoded = RemoteCommandEncoder.Encode(request);

        Assert.AreEqual(
            "/usr/bin/sudo -n /usr/local/libexec/maxiems-deploy-helper 'stage' 'value with spaces' 'eth0;id' 'it'\\''s'",
            encoded);
    }

    [TestMethod]
    public void NetworkHelperUsesPublicSbinEntrypoint()
    {
        Assert.AreEqual("/usr/bin/sudo -n /usr/local/sbin/maxiems-network-helper 'status' 'transaction-id'",
            RemoteCommandEncoder.Encode(new(RemoteProgram.NetworkHelper,
                [new("status"), new("transaction-id")], TimeSpan.FromSeconds(5))));
    }

    [TestMethod]
    public void NullByteAndInvalidTimeoutAreRejected()
    {
        Assert.ThrowsExactly<ArgumentException>(() => RemoteCommandEncoder.Encode(new(
            RemoteProgram.DeployHelper, [new("bad\0argument")], TimeSpan.FromSeconds(1))));
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => RemoteCommandEncoder.Encode(new(
            RemoteProgram.DeployHelper, [], TimeSpan.Zero)));
    }

    [TestMethod]
    public void FixedPreflightProgramsCannotReceiveCallerControlledArguments()
    {
        Assert.AreEqual("/usr/bin/cat /etc/os-release", RemoteCommandEncoder.Encode(new(
            RemoteProgram.OsReleaseProbe, [], TimeSpan.FromSeconds(1))));
        Assert.ThrowsExactly<ArgumentException>(() => RemoteCommandEncoder.Encode(new(
            RemoteProgram.SudoProbe, [new("/bin/bash")], TimeSpan.FromSeconds(1))));
        Assert.ThrowsExactly<ArgumentException>(() => RemoteCommandEncoder.Encode(new(
            RemoteProgram.AccountProbe, [new("root")], TimeSpan.FromSeconds(1))));
    }

    [TestMethod]
    public void HardwareInventoryProgramsMapToExactReadOnlyCommands()
    {
        var expected = new Dictionary<RemoteProgram, string>
        {
            [RemoteProgram.NetworkLinksProbe] = "/usr/sbin/ip -json -details link show",
            [RemoteProgram.CpuInventoryProbe] = "/usr/bin/lscpu --json --bytes",
            [RemoteProgram.BlockDeviceProbe] =
                "/usr/bin/lsblk --json --bytes --output NAME,TYPE,SIZE,MODEL,SERIAL,TRAN,MOUNTPOINTS",
            [RemoteProgram.KernelInventoryProbe] = "/usr/bin/uname --kernel-release --machine",
            [RemoteProgram.BoardInventoryProbe] =
                "/usr/bin/cat /sys/devices/virtual/dmi/id/product_name",
            [RemoteProgram.DeviceTreeBoardInventoryProbe] =
                "/usr/bin/cat /sys/firmware/devicetree/base/model",
            [RemoteProgram.ImageVersionProbe] = "/usr/bin/cat /etc/maxiems-image",
            [RemoteProgram.UsbInventoryProbe] = "/usr/bin/lsusb",
            [RemoteProgram.PciInventoryProbe] = "/usr/bin/lspci -mm -nn -k",
            [RemoteProgram.ModemInventoryProbe] = "/usr/bin/mmcli --list-modems --output-json",
            [RemoteProgram.WirelessInventoryProbe] = "/usr/sbin/iw dev",
            [RemoteProgram.BluetoothInventoryProbe] =
                "/usr/bin/timeout --signal=TERM 5s /usr/bin/bluetoothctl list",
            [RemoteProgram.DeviceNodeInventoryProbe] = "/usr/bin/find /dev -maxdepth 1 -type c",
            [RemoteProgram.SdioInventoryProbe] =
                "/usr/bin/find /sys/bus/sdio/devices -mindepth 1 -maxdepth 1 -type l",
        };
        foreach (var item in expected)
        {
            Assert.IsFalse(item.Value.Contains("sudo", StringComparison.Ordinal));
            Assert.AreEqual(item.Value, RemoteCommandEncoder.Encode(new(item.Key, [], TimeSpan.FromSeconds(5))));
            Assert.ThrowsExactly<ArgumentException>(() => RemoteCommandEncoder.Encode(new(
                item.Key, [new("unexpected")], TimeSpan.FromSeconds(5))));
        }
    }

    [TestMethod]
    public void PermanentMacProbeAcceptsOnlyValidatedLinuxInterfaceName()
    {
        Assert.AreEqual("/usr/sbin/ethtool --show-permaddr 'enp1s0'", RemoteCommandEncoder.Encode(new(
            RemoteProgram.PermanentMacProbe, [new("enp1s0")], TimeSpan.FromSeconds(5))));
        foreach (var malicious in new[] { "eth0;id", "eth0 id", "../eth0", "eth0$(id)", new string('a', 16) })
        {
            Assert.ThrowsExactly<ArgumentException>(() => RemoteCommandEncoder.Encode(new(
                RemoteProgram.PermanentMacProbe, [new(malicious)], TimeSpan.FromSeconds(5))));
        }
    }
}
