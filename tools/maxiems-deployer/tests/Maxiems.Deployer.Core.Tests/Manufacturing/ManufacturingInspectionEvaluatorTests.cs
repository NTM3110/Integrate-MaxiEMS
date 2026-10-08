using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
[TestCategory("ManufacturingInspection")]
public sealed class ManufacturingInspectionEvaluatorTests
{
    private static readonly DateTimeOffset Timestamp = DateTimeOffset.Parse("2026-07-22T08:00:00Z",
        System.Globalization.CultureInfo.InvariantCulture);
    private static readonly EngineeringIdentity Engineer = new("ATE\\engineer", "ENG-01");

    [TestMethod]
    public void ApprovedFixtureDerivesExactSerialAndProducesDeterministicOrderedGate()
    {
        var hardware = Hardware();
        var profile = Profile();
        var first = ManufacturingInspectionEvaluator.Evaluate(hardware, profile, Mapping("eth0", "eth1"), new(false, false));
        var reversed = hardware with
        {
            EthernetPorts = hardware.EthernetPorts.Reverse().ToImmutableArray(),
            Peripherals = hardware.Peripherals.Reverse().ToImmutableArray(),
        };
        var second = ManufacturingInspectionEvaluator.Evaluate(reversed, profile, Mapping("eth0", "eth1"), new(false, false));

        Assert.IsTrue(first.IsReady);
        Assert.AreEqual("MR00613A7D755A", first.DerivedSerial!.Value);
        CollectionAssert.AreEqual(first.Checklist.Results.Select(item => item.Code).Order(StringComparer.Ordinal).ToArray(),
            first.Checklist.Results.Select(item => item.Code).ToArray());
        Assert.AreEqual(first.SnapshotSha256, second.SnapshotSha256);
        Assert.AreEqual(64, first.SnapshotSha256.Length);
    }

    [TestMethod]
    public void MappingControlsSerialSourceAndNeverUsesSpoofedCurrentMac()
    {
        var hardware = Hardware();
        var normal = ManufacturingInspectionEvaluator.Evaluate(hardware, Profile(), Mapping("eth0", "eth1"), new(false, false));
        var swapped = ManufacturingInspectionEvaluator.Evaluate(hardware, Profile(), Mapping("eth1", "eth0"), new(false, false));

        Assert.AreEqual("MR00613A7D755A", normal.DerivedSerial!.Value);
        Assert.AreEqual("MR00613A7D755C", swapped.DerivedSerial!.Value);
        Assert.AreNotEqual("MR021122334455", normal.DerivedSerial.Value);
        Assert.IsFalse(ManufacturingInspectionEvaluator.Evaluate(hardware, Profile(), null, new(false, false)).IsReady);
    }

    [TestMethod]
    public void DuplicateAndNonOverridablePlatformOrIdentityCodesBlockReadiness()
    {
        var duplicate = ManufacturingInspectionEvaluator.Evaluate(Hardware(), Profile(), Mapping("eth0", "eth1"),
            new(true, true));
        var unsupported = Hardware() with
        {
            OperatingSystem = Hardware().OperatingSystem with
            {
                Architecture = "riscv64"
            },
        };
        var platform = ManufacturingInspectionEvaluator.Evaluate(unsupported, Profile(), Mapping("eth0", "eth1"),
            new(false, false));

        Assert.AreEqual(InspectionCheckOutcome.BlockingFailure,
            duplicate.Checklist.Results.Single(item => item.Code == "identity.unique").Outcome);
        Assert.AreEqual(InspectionCheckOutcome.BlockingFailure,
            platform.Checklist.Results.Single(item => item.Code == "os.architecture").Outcome);
        Assert.IsFalse(duplicate.IsReady);
        Assert.IsFalse(platform.IsReady);
    }

    [TestMethod]
    public void RamStorageAndRequiredOptionalForbiddenModuleMatrixIsDeterministic()
    {
        var profile = Profile();
        var baseHardware = Hardware();
        var atTolerance = baseHardware with
        {
            Memory = baseHardware.Memory with
            {
                TotalBytes = 4_000_000_000
            },
            StorageDevices = [baseHardware.StorageDevices[0] with { SizeBytes = 60_000_000_000 }],
        };
        Assert.IsTrue(ManufacturingInspectionEvaluator.Evaluate(atTolerance, profile, Mapping("eth0", "eth1"),
            new(false, false)).IsReady);

        var missingRequired = baseHardware with
        {
            Peripherals = []
        };
        var missing = ManufacturingInspectionEvaluator.Evaluate(missingRequired, profile, Mapping("eth0", "eth1"),
            new(false, false));
        Assert.AreEqual(InspectionCheckOutcome.BlockingFailure,
            missing.Checklist.Results.Single(item => item.Code == "module.cellular").Outcome);
        Assert.AreEqual(InspectionCheckOutcome.NotApplicable,
            missing.Checklist.Results.Single(item => item.Code == "module.lora").Outcome);

        var forbidden = baseHardware with
        {
            Peripherals = baseHardware.Peripherals.Add(Peripheral(
            HardwareBus.PciExpress, "9999", "0001", [], []))
        };
        Assert.AreEqual(InspectionCheckOutcome.BlockingFailure,
            ManufacturingInspectionEvaluator.Evaluate(forbidden, profile, Mapping("eth0", "eth1"), new(false, false))
                .Checklist.Results.Single(item => item.Code == "module.wifi-forbidden").Outcome);

        var forbiddenEvidenceMissing = baseHardware with
        {
            Peripherals = baseHardware.Peripherals.Add(new(
            HardwareBus.PciExpress, null, null, null, null, [], [],
            new("lspci", EvidenceAvailability.Unavailable, "tool unavailable")))
        };
        Assert.AreEqual(InspectionCheckOutcome.BlockingFailure,
            ManufacturingInspectionEvaluator.Evaluate(forbiddenEvidenceMissing, profile, Mapping("eth0", "eth1"),
                new(false, false)).Checklist.Results.Single(item => item.Code == "module.wifi-forbidden").Outcome);
    }

    [TestMethod]
    public void MissingOptionalEvidenceWarnsButDoesNotPretendHardwareWasAbsent()
    {
        var hardware = Hardware() with
        {
            Peripherals = Hardware().Peripherals.Add(new(HardwareBus.Uart,
            null, null, null, null, [], [], new("UART", EvidenceAvailability.Unavailable, "probe unavailable")))
        };

        var evaluation = ManufacturingInspectionEvaluator.Evaluate(hardware, Profile(), Mapping("eth0", "eth1"),
            new(false, false));

        Assert.AreEqual(InspectionCheckOutcome.Warning,
            evaluation.Checklist.Results.Single(item => item.Code == "module.lora").Outcome);
        Assert.IsTrue(evaluation.IsReady);
    }

    private static ManufacturingProfile Profile() => new(new("edge", 1, new string('a', 64)),
        ManufacturingProfileState.Approved, "Edge", "MAXiEMS", "Fixture",
        new(["arm64"], ["Fixture CPU"], 4), new(4_000_000_000, 8_000_000_000),
        [new(StorageKind.Emmc, new(60_000_000_000, 70_000_000_000), ["Fixture eMMC"])],
        new("ubuntu", ["24.04"], ["arm64"], ["image-1"], false, null),
        [new("LAN1", false), new("LAN2", true)],
        [
            new("cellular", ModuleKind.Cellular4G, ModuleRequirementMode.Required,
                [new(HardwareBus.Usb, "2c7c", "0125", null, null, null, null)]),
            new("lora", ModuleKind.Lora, ModuleRequirementMode.Optional,
                [new(HardwareBus.Uart, null, null, null, null, null, "/dev/ttyS1")]),
            new("wifi-forbidden", ModuleKind.Wifi, ModuleRequirementMode.Forbidden,
                [new(HardwareBus.PciExpress, "9999", "0001", null, null, null, null)]),
        ], new ReleaseId("20260722080000-123456789abc"), null, false, true, "fixture");

    private static HardwareInventorySnapshot Hardware()
    {
        var evidence = new HardwareEvidence("fixture", EvidenceAvailability.Observed, "verified fixture evidence");
        return new(Timestamp, new("aarch64", "Fixture CPU", 4, evidence), new(4_000_000_000, evidence),
            [new("mmcblk0", StorageKind.Emmc, "Fixture eMMC", "DISK", 64_000_000_000, true, evidence)],
            new("ubuntu", "24.04", "aarch64", "6.8.0", "image-1", "Fixture Board", null, evidence),
            [
                new("eth0", null, new("00:61:3A:7D:75:5C"), new("00:61:3A:7D:75:5C"), true, 1000, evidence),
                new("eth1", null, new("02:11:22:33:44:55"), new("00:61:3A:7D:75:5A"), true, 1000, evidence),
            ], [], [Peripheral(HardwareBus.Usb, "2c7c", "0125", [], [])]);
    }

    private static EthernetMappingConfirmation Mapping(string lan1, string lan2) => new(
        ImmutableDictionary.CreateRange(new[] {
            new KeyValuePair<string, string>("LAN1", lan1), new KeyValuePair<string, string>("LAN2", lan2),
        }), Engineer, Timestamp);

    private static HardwarePeripheralInventory Peripheral(HardwareBus bus, string vendor, string product,
        ImmutableArray<string> interfaces, ImmutableArray<string> nodes) => new(bus, vendor, product, null, null,
        interfaces, nodes, new("fixture", EvidenceAvailability.Observed, "stable IDs"));
}
