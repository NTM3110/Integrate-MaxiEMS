using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text.Json;

namespace Maxiems.Deployer.Core.Manufacturing;

public static class ManufacturingInspectionEvaluator
{
    public static ManufacturingInspectionEvaluation Evaluate(HardwareInventorySnapshot hardware,
        ManufacturingProfile profile, EthernetMappingConfirmation? mapping, DeviceIdentityConflict conflict)
    {
        ArgumentNullException.ThrowIfNull(hardware);
        ArgumentNullException.ThrowIfNull(profile);
        var checks = ImmutableArray.CreateBuilder<InspectionCheckResult>();
        Add(checks, "profile.approved", InspectionCheckRequirement.Required,
            profile.State == ManufacturingProfileState.Approved, profile.State.ToString(), "Approved", "profile state");
        var architecture = NormalizeArchitecture(hardware.Cpu.Architecture);
        Add(checks, "cpu.architecture", InspectionCheckRequirement.Required,
            profile.Cpu.Architectures.Contains(architecture, StringComparer.Ordinal), architecture,
            string.Join(", ", profile.Cpu.Architectures), hardware.Cpu.Evidence.Summary);
        Add(checks, "cpu.cores", InspectionCheckRequirement.Required,
            hardware.Cpu.LogicalCoreCount >= profile.Cpu.MinimumLogicalCores,
            hardware.Cpu.LogicalCoreCount.ToString(System.Globalization.CultureInfo.InvariantCulture),
            $">= {profile.Cpu.MinimumLogicalCores}", hardware.Cpu.Evidence.Summary);
        Add(checks, "cpu.model", InspectionCheckRequirement.Required,
            profile.Cpu.Models.Contains(hardware.Cpu.Model, StringComparer.OrdinalIgnoreCase), hardware.Cpu.Model,
            string.Join(", ", profile.Cpu.Models), hardware.Cpu.Evidence.Summary);
        Add(checks, "memory.total", InspectionCheckRequirement.Required,
            profile.MemoryBytes.Contains(hardware.Memory.TotalBytes), hardware.Memory.TotalBytes.ToString(
                System.Globalization.CultureInfo.InvariantCulture), Range(profile.MemoryBytes), hardware.Memory.Evidence.Summary);
        EvaluateStorage(checks, hardware, profile);
        EvaluateOperatingSystem(checks, hardware, profile);
        EvaluateEthernet(checks, hardware, profile, mapping);
        EvaluateModules(checks, hardware, profile);
        var serial = DeviceSerialFactory.TryCreate(hardware, profile, mapping);
        Add(checks, "identity.serial", InspectionCheckRequirement.Required, serial is not null,
            serial?.Value ?? "Unavailable", "MR + proven serial-source permanent MAC", "confirmed Ethernet mapping");
        Add(checks, "identity.unique", InspectionCheckRequirement.Required,
            serial is not null && !conflict.SerialAlreadyRegistered && !conflict.PermanentMacOwnedByAnotherDevice,
            conflict is { SerialAlreadyRegistered: false, PermanentMacOwnedByAnotherDevice: false } ? "Available" : "Conflict",
            "Locally unique SN and permanent MAC", "manufacturing store");
        var ordered = checks.OrderBy(check => check.Code, StringComparer.Ordinal).ToImmutableArray();
        return new(serial, new("manufacturing-profile", profile.Reference.Version, ordered), Hash(hardware, mapping));
    }

    private static void EvaluateStorage(ImmutableArray<InspectionCheckResult>.Builder checks,
        HardwareInventorySnapshot hardware, ManufacturingProfile profile)
    {
        for (var index = 0; index < profile.Storage.Length; index++)
        {
            var required = profile.Storage[index];
            var match = hardware.StorageDevices.FirstOrDefault(device => device.Kind == required.Kind
                && required.SizeBytes.Contains(device.SizeBytes)
                && (required.AllowedModels.Length == 0 || device.Model is not null
                    && required.AllowedModels.Contains(device.Model, StringComparer.OrdinalIgnoreCase)));
            Add(checks, $"storage.{index:D2}", InspectionCheckRequirement.Required, match is not null,
                match is null ? "No matching device" : $"{match.LinuxName} {match.SizeBytes}",
                $"{required.Kind} {Range(required.SizeBytes)}", match?.Evidence.Summary ?? "lsblk inventory");
        }
    }

    private static void EvaluateOperatingSystem(ImmutableArray<InspectionCheckResult>.Builder checks,
        HardwareInventorySnapshot hardware, ManufacturingProfile profile)
    {
        var actual = hardware.OperatingSystem;
        var expected = profile.OperatingSystem;
        Add(checks, "os.id", InspectionCheckRequirement.Required,
            actual.OperatingSystemId.Equals(expected.OperatingSystemId, StringComparison.OrdinalIgnoreCase),
            actual.OperatingSystemId, expected.OperatingSystemId, actual.Evidence.Summary);
        Add(checks, "os.version", InspectionCheckRequirement.Required,
            expected.Versions.Contains(actual.VersionId, StringComparer.Ordinal), actual.VersionId,
            string.Join(", ", expected.Versions), actual.Evidence.Summary);
        Add(checks, "os.architecture", InspectionCheckRequirement.Required,
            expected.Architectures.Contains(NormalizeArchitecture(actual.Architecture), StringComparer.Ordinal),
            NormalizeArchitecture(actual.Architecture), string.Join(", ", expected.Architectures), actual.Evidence.Summary);
        Add(checks, "os.image", InspectionCheckRequirement.Required, expected.ImageVersions.Length == 0
            || actual.ImageVersion is not null && expected.ImageVersions.Contains(actual.ImageVersion, StringComparer.Ordinal),
            actual.ImageVersion ?? "Unavailable", string.Join(", ", expected.ImageVersions), actual.Evidence.Summary);
        Add(checks, "os.kernel", InspectionCheckRequirement.Required, !expected.RequireExactKernel
            || actual.KernelVersion.Equals(expected.KernelVersion, StringComparison.Ordinal), actual.KernelVersion,
            expected.RequireExactKernel ? expected.KernelVersion! : "Any supported kernel", actual.Evidence.Summary);
    }

    private static void EvaluateEthernet(ImmutableArray<InspectionCheckResult>.Builder checks,
        HardwareInventorySnapshot hardware, ManufacturingProfile profile, EthernetMappingConfirmation? mapping)
    {
        var mappingValid = mapping is not null && mapping.PhysicalToLinuxName.Count == 2
            && profile.EthernetPorts.All(port => mapping.PhysicalToLinuxName.ContainsKey(port.PhysicalLabel));
        Add(checks, "ethernet.mapping", InspectionCheckRequirement.Required, mappingValid,
            mappingValid ? string.Join(", ", mapping!.PhysicalToLinuxName.OrderBy(item => item.Key)
                .Select(item => $"{item.Key}={item.Value}")) : "Unconfirmed", "Two confirmed physical port mappings",
            "engineer confirmation");
        foreach (var requirement in profile.EthernetPorts.OrderBy(port => port.PhysicalLabel, StringComparer.Ordinal))
        {
            var observed = mappingValid ? hardware.EthernetPorts.SingleOrDefault(port => port.LinuxName ==
                mapping!.PhysicalToLinuxName[requirement.PhysicalLabel]) : null;
            Add(checks, $"ethernet.{requirement.PhysicalLabel.ToLowerInvariant()}", InspectionCheckRequirement.Required,
                observed?.PermanentMac is not null && observed.Evidence.Availability == EvidenceAvailability.Observed,
                observed?.PermanentMac?.Normalized ?? "Permanent MAC unavailable", "Proven permanent MAC",
                observed?.Evidence.Summary ?? "mapping unavailable");
        }
    }

    private static void EvaluateModules(ImmutableArray<InspectionCheckResult>.Builder checks,
        HardwareInventorySnapshot hardware, ManufacturingProfile profile)
    {
        foreach (var rule in profile.Modules.OrderBy(rule => rule.RuleId, StringComparer.Ordinal))
        {
            var found = rule.Matchers.Any(matcher => hardware.Peripherals.Any(item => Matches(matcher, item)));
            var unavailable = rule.Matchers.Any(matcher => hardware.Peripherals.Any(item =>
                BusMatches(matcher, item) && item.Evidence.Availability == EvidenceAvailability.Unavailable));
            var requirement = rule.Mode switch
            {
                ModuleRequirementMode.Required => InspectionCheckRequirement.Required,
                ModuleRequirementMode.Optional => InspectionCheckRequirement.Optional,
                _ => InspectionCheckRequirement.Forbidden,
            };
            var outcome = rule.Mode switch
            {
                ModuleRequirementMode.Required => found ? InspectionCheckOutcome.Passed : InspectionCheckOutcome.BlockingFailure,
                ModuleRequirementMode.Optional => found ? InspectionCheckOutcome.Passed
                    : unavailable ? InspectionCheckOutcome.Warning : InspectionCheckOutcome.NotApplicable,
                _ => found || unavailable ? InspectionCheckOutcome.BlockingFailure : InspectionCheckOutcome.Passed,
            };
            checks.Add(new($"module.{rule.RuleId}", requirement, outcome,
                found ? "Detected" : unavailable ? "Evidence unavailable" : "Not detected",
                rule.Mode.ToString(), "stable peripheral identity evidence"));
        }
    }

    private static bool Matches(ModuleEvidenceMatcher expected, HardwarePeripheralInventory actual) =>
        actual.Evidence.Availability == EvidenceAvailability.Observed && BusMatches(expected, actual)
        && Match(expected.VendorId, actual.VendorId) && Match(expected.ProductId, actual.ProductId)
        && Match(expected.Model, actual.Model) && Match(expected.Driver, actual.Driver)
        && (expected.InterfaceName is null || actual.InterfaceNames.Contains(expected.InterfaceName, StringComparer.Ordinal))
        && (expected.DeviceNode is null || actual.DeviceNodes.Contains(expected.DeviceNode, StringComparer.Ordinal));
    private static bool BusMatches(ModuleEvidenceMatcher expected, HardwarePeripheralInventory actual) =>
        expected.Bus == HardwareBus.Unknown || expected.Bus == actual.Bus;
    private static bool Match(string? expected, string? actual) => expected is null
        || actual is not null && expected.Equals(actual, StringComparison.OrdinalIgnoreCase);

    private static void Add(ImmutableArray<InspectionCheckResult>.Builder checks, string code,
        InspectionCheckRequirement requirement, bool passed, string observed, string expected, string evidence) =>
        checks.Add(new(code, requirement, passed ? InspectionCheckOutcome.Passed : InspectionCheckOutcome.BlockingFailure,
            observed, expected, evidence));
    private static string NormalizeArchitecture(string value) => value.ToLowerInvariant() switch
    {
        "x86_64" => "amd64",
        "aarch64" => "arm64",
        _ => value.ToLowerInvariant(),
    };
    private static string Range(NumericRange range) => $"{range.Minimum}..{range.Maximum}";
    private static string Hash(HardwareInventorySnapshot hardware, EthernetMappingConfirmation? mapping)
    {
        var normalized = new
        {
            hardware.CollectedAt,
            hardware.Cpu,
            hardware.Memory,
            Storage = hardware.StorageDevices.OrderBy(item => item.LinuxName, StringComparer.Ordinal).ToArray(),
            hardware.OperatingSystem,
            Ethernet = hardware.EthernetPorts.OrderBy(item => item.LinuxName, StringComparer.Ordinal).ToArray(),
            Modules = hardware.Modules.OrderBy(item => item.Kind).ThenBy(item => item.Model, StringComparer.Ordinal).ToArray(),
            Peripherals = hardware.Peripherals.OrderBy(item => item.Bus).ThenBy(item => item.VendorId, StringComparer.Ordinal)
                .ThenBy(item => item.ProductId, StringComparer.Ordinal).ThenBy(item => item.Model, StringComparer.Ordinal).ToArray(),
            Mapping = mapping?.PhysicalToLinuxName.OrderBy(item => item.Key, StringComparer.Ordinal).ToArray(),
            Engineer = mapping?.Engineer,
            ConfirmedAt = mapping?.ConfirmedAt,
        };
        return Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(normalized))).ToLowerInvariant();
    }
}
