using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum EvidenceAvailability
{
    Observed,
    Unavailable,
    NotApplicable,
}

public sealed record HardwareEvidence(
    string Source,
    EvidenceAvailability Availability,
    string Summary);

public sealed record CpuInventory(
    string Architecture,
    string Model,
    int LogicalCoreCount,
    HardwareEvidence Evidence);

public sealed record MemoryInventory(
    long TotalBytes,
    HardwareEvidence Evidence);

public enum StorageKind
{
    Unknown,
    Emmc,
    Nvme,
    Sata,
    Usb,
}

public sealed record StorageDeviceInventory(
    string LinuxName,
    StorageKind Kind,
    string? Model,
    string? SerialNumber,
    long SizeBytes,
    bool IsSystemDevice,
    HardwareEvidence Evidence);

public sealed record OperatingSystemInventory(
    string OperatingSystemId,
    string VersionId,
    string Architecture,
    string KernelVersion,
    string? ImageVersion,
    string? BoardModel,
    string? HardwareRevision,
    HardwareEvidence Evidence);

public sealed record EthernetPortInventory(
    string LinuxName,
    string? PhysicalLabel,
    MacAddress CurrentMac,
    MacAddress? PermanentMac,
    bool LinkUp,
    long? SpeedMegabits,
    HardwareEvidence Evidence);

public enum ModuleKind
{
    Cellular4G,
    Lora,
    Wifi,
    BluetoothLowEnergy,
}

public enum HardwareBus
{
    Unknown,
    Usb,
    PciExpress,
    Sdio,
    Uart,
    Spi,
    Integrated,
}

public sealed record ModuleInventory(
    ModuleKind Kind,
    HardwareBus Bus,
    string? VendorId,
    string? ProductId,
    string? Model,
    string? Driver,
    ImmutableArray<string> InterfaceNames,
    ImmutableArray<string> DeviceNodes,
    HardwareEvidence Evidence);

public sealed record HardwarePeripheralInventory(
    HardwareBus Bus,
    string? VendorId,
    string? ProductId,
    string? Model,
    string? Driver,
    ImmutableArray<string> InterfaceNames,
    ImmutableArray<string> DeviceNodes,
    HardwareEvidence Evidence);

public sealed record HardwareInventorySnapshot(
    DateTimeOffset CollectedAt,
    CpuInventory Cpu,
    MemoryInventory Memory,
    ImmutableArray<StorageDeviceInventory> StorageDevices,
    OperatingSystemInventory OperatingSystem,
    ImmutableArray<EthernetPortInventory> EthernetPorts,
    ImmutableArray<ModuleInventory> Modules,
    ImmutableArray<HardwarePeripheralInventory> Peripherals = default);
