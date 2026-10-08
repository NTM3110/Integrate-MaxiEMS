using System.Text.Json;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
[TestCategory("Manufacturing")]
public sealed class DeviceIdentityTests
{
    [TestMethod]
    [DataRow("00:61:3a:7d:75:5a")]
    [DataRow("00-61-3A-7D-75-5A")]
    [DataRow("00613A7D755A")]
    public void NormalizesPermanentMacAndDerivesRequestedSerial(string value)
    {
        var mac = new MacAddress(value);
        var serial = DeviceSerial.FromSerialSource(mac);

        Assert.AreEqual("00613A7D755A", mac.Normalized);
        Assert.AreEqual("00:61:3A:7D:75:5A", mac.ToColonSeparated());
        Assert.AreEqual("MR00613A7D755A", serial.Value);
        Assert.AreEqual(mac, serial.SerialSourceMac);
    }

    [TestMethod]
    [DataRow("")]
    [DataRow("00613A7D755")]
    [DataRow("00:61:3A:7D:75:ZZ")]
    [DataRow("00:00:00:00:00:00")]
    [DataRow("FF:FF:FF:FF:FF:FF")]
    [DataRow("01:00:5E:00:00:01")]
    [DataRow("0:0613A7D755A")]
    public void RejectsInvalidNonUnicastOrMalformedMac(string value)
    {
        Assert.ThrowsExactly<ArgumentException>(() => new MacAddress(value));
    }

    [TestMethod]
    public void ExposesLocallyAdministeredStatusWithoutRejectingIt()
    {
        var mac = new MacAddress("02:00:00:00:00:01");

        Assert.IsTrue(mac.IsLocallyAdministered);
        Assert.AreEqual("MR020000000001", DeviceSerial.FromSerialSource(mac).Value);
    }

    [TestMethod]
    public void IdentityValueObjectsRoundTripThroughJson()
    {
        var original = new IdentityDocument(
            new MacAddress("00:61:3A:7D:75:5A"),
            new DeviceSerial("mr00613a7d755a"));

        var json = JsonSerializer.Serialize(original);
        var restored = JsonSerializer.Deserialize<IdentityDocument>(json);

        Assert.AreEqual(original, restored);
    }

    [TestMethod]
    [DataRow("MX00613A7D755A")]
    [DataRow("MR00613A7D755")]
    [DataRow("MR01005E000001")]
    public void RejectsInvalidSerial(string value)
    {
        Assert.ThrowsExactly<ArgumentException>(() => new DeviceSerial(value));
    }

    private sealed record IdentityDocument(MacAddress Mac, DeviceSerial Serial);
}
