using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum ManufacturingProfileState
{
    Draft,
    Validated,
    Approved,
    Archived,
}

public sealed record ManufacturingProfileReference(
    string ProfileId,
    int Version,
    string Sha256);

public sealed record NumericRange(long Minimum, long Maximum)
{
    public bool Contains(long value) => value >= this.Minimum && value <= this.Maximum;
}

public sealed record CpuRequirement(
    ImmutableArray<string> Architectures,
    ImmutableArray<string> Models,
    int MinimumLogicalCores);

public sealed record StorageRequirement(
    StorageKind Kind,
    NumericRange SizeBytes,
    ImmutableArray<string> AllowedModels);

public sealed record OperatingSystemRequirement(
    string OperatingSystemId,
    ImmutableArray<string> Versions,
    ImmutableArray<string> Architectures,
    ImmutableArray<string> ImageVersions,
    bool RequireExactKernel,
    string? KernelVersion);

public sealed record EthernetPortRequirement(
    string PhysicalLabel,
    bool IsSerialSource);

public enum ModuleRequirementMode
{
    Required,
    Optional,
    Forbidden,
}

public sealed record ModuleEvidenceMatcher(
    HardwareBus Bus,
    string? VendorId,
    string? ProductId,
    string? Model,
    string? Driver,
    string? InterfaceName,
    string? DeviceNode);

public sealed record ModuleRequirement(
    string RuleId,
    ModuleKind Kind,
    ModuleRequirementMode Mode,
    ImmutableArray<ModuleEvidenceMatcher> Matchers);

public sealed record ManufacturingProfile(
    ManufacturingProfileReference Reference,
    ManufacturingProfileState State,
    string DisplayName,
    string ProductFamily,
    string HardwareVariant,
    CpuRequirement Cpu,
    NumericRange MemoryBytes,
    ImmutableArray<StorageRequirement> Storage,
    OperatingSystemRequirement OperatingSystem,
    ImmutableArray<EthernetPortRequirement> EthernetPorts,
    ImmutableArray<ModuleRequirement> Modules,
    ReleaseId DefaultReleaseId,
    string? ComponentProfileId,
    bool ConfigureTailscale,
    bool RequireRebootAcceptance,
    string ChangeNote);
