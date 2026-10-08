using System.Collections.Immutable;
using System.Reflection;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
[TestCategory("Manufacturing")]
public sealed class ManufacturingContractTests
{
    [TestMethod]
    public void SnapshotAndChecklistUseImmutableCollections()
    {
        var evidence = new HardwareEvidence("ip-link", EvidenceAvailability.Observed, "wired interface");
        var ethernet = new EthernetPortInventory(
            "enp2s0", "LAN2", new MacAddress("00:61:3A:7D:75:5A"),
            new MacAddress("00:61:3A:7D:75:5A"), true, 1000, evidence);
        var snapshot = new HardwareInventorySnapshot(
            DateTimeOffset.Parse("2026-07-22T08:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
            new("arm64", "Example CPU", 8, evidence),
            new(8L * 1024 * 1024 * 1024, evidence),
            [new("mmcblk0", StorageKind.Emmc, "Example eMMC", "STORAGE-1", 64L * 1024 * 1024 * 1024,
                true, evidence)],
            new("ubuntu", "24.04", "arm64", "6.8.0", "maxiems-2026.07", "Example Board", "A1", evidence),
            [ethernet],
            []);
        var checklist = new InspectionChecklistSnapshot(
            "manufacturing-v1", 1,
            [new("identity.serial", InspectionCheckRequirement.Required,
                InspectionCheckOutcome.Passed, "MR00613A7D755A", "unique", "derived")]);

        Assert.AreEqual(1, snapshot.StorageDevices.Length);
        Assert.AreEqual(1, snapshot.EthernetPorts.Length);
        Assert.AreEqual(1, checklist.Results.Length);
        Assert.AreEqual(typeof(ImmutableArray<StorageDeviceInventory>), snapshot.StorageDevices.GetType());
    }

    [TestMethod]
    public void ContractsCarryTraceabilityWithoutCredentialFields()
    {
        var forbidden = new[] { "password", "passphrase", "privatekey", "token", "secret", "credential" };
        var offenders = typeof(DeviceSerial).Assembly.GetExportedTypes()
            .Where(type => type.Namespace == typeof(DeviceSerial).Namespace)
            .SelectMany(type => type.GetProperties(BindingFlags.Instance | BindingFlags.Public)
                .Select(property => $"{type.Name}.{property.Name}"))
            .Where(name => forbidden.Any(term => name.Contains(term, StringComparison.OrdinalIgnoreCase)))
            .ToArray();

        CollectionAssert.AreEqual(Array.Empty<string>(), offenders);
    }

    [TestMethod]
    public void DeploymentContextFreezesDeviceProfileInspectionReleaseAndEngineerIdentity()
    {
        var profile = new ManufacturingProfileReference("maxiems-edge-v2", 3, new string('a', 64));
        var release = new LocalReleaseDescriptor(
            new ReleaseId("20260722080000-123456789abc"), "2026.07.2", LocalReleaseSource.Embedded,
            LocalReleaseStatus.Embedded, new string('b', 64), new string('c', 64),
            ["24.04"], [SupportedArchitecture.Arm64], "1.0.0");
        var context = new ManufacturingDeploymentContext(
            Guid.Parse("8ec851e5-67dc-4aa9-aa28-7bb65e650002"),
            new DeviceSerial("MR00613A7D755A"), profile,
            Guid.Parse("8ec851e5-67dc-4aa9-aa28-7bb65e650003"), new string('d', 64),
            release, DeploymentActionKind.Deploy,
            new("ATE\\engineer01", "ENG-WS-01"));

        Assert.AreEqual("MR00613A7D755A", context.Serial.Value);
        Assert.AreEqual(3, context.Profile.Version);
        Assert.AreEqual(DeploymentActionKind.Deploy, context.Action);
        Assert.AreEqual("ENG-WS-01", context.Engineer.WorkstationName);
    }
}
