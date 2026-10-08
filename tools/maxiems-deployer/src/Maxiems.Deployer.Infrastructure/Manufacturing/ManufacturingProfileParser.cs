using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing;

public sealed class ManufacturingProfileException(string message) : Exception(message);

public static class ManufacturingProfileParser
{
    public static ManufacturingProfile Parse(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = 32,
            });
            return ParseRoot(document.RootElement);
        }
        catch (JsonException exception)
        {
            throw new ManufacturingProfileException($"Manufacturing profile is not valid JSON: {exception.Message}");
        }
    }

    private static ManufacturingProfile ParseRoot(JsonElement root)
    {
        var value = new StrictJsonObject(root, "manufacturing profile", "schemaVersion", "profile", "hardware",
            "deployment");
        if (value.RequiredInt32("schemaVersion") != 1)
        {
            throw new ManufacturingProfileException("schemaVersion must be the integer 1.");
        }
        var hash = ManufacturingProfileCanonicalizer.ComputeSha256(root);
        var identity = ParseIdentity(value.Required("profile"), hash);
        var hardware = new StrictJsonObject(value.Required("hardware"), "hardware", "cpu", "memoryBytes", "storage",
            "operatingSystem", "ethernetPorts", "modules");
        var cpu = ParseCpu(hardware.Required("cpu"));
        var memory = ParseRange(hardware.Required("memoryBytes"), "memoryBytes");
        var storage = ParseStorage(hardware.RequiredArray("storage"));
        var operatingSystem = ParseOperatingSystem(hardware.Required("operatingSystem"));
        var ethernet = ParseEthernet(hardware.RequiredArray("ethernetPorts"));
        var modules = ParseModules(hardware.RequiredArray("modules"));
        var deployment = new StrictJsonObject(value.Required("deployment"), "deployment", "defaultReleaseId",
            "componentProfileId", "configureTailscale", "requireRebootAcceptance");
        var releaseId = deployment.RequiredString("defaultReleaseId", 27);
        if (!IsReleaseId(releaseId))
        {
            throw new ManufacturingProfileException("defaultReleaseId has an invalid format.");
        }
        return new(identity.Reference, identity.State, identity.DisplayName, identity.ProductFamily,
            identity.HardwareVariant, cpu, memory, storage, operatingSystem, ethernet, modules,
            new ReleaseId(releaseId), deployment.OptionalString("componentProfileId", 80),
            deployment.RequiredBoolean("configureTailscale"),
            deployment.RequiredBoolean("requireRebootAcceptance"), identity.ChangeNote);
    }

    private static ProfileIdentity ParseIdentity(JsonElement element, string hash)
    {
        var value = new StrictJsonObject(element, "profile", "id", "version", "state", "displayName",
            "productFamily", "hardwareVariant", "changeNote");
        var id = value.RequiredString("id", 64);
        if (!IsSafeId(id))
            throw new ManufacturingProfileException("profile id has an invalid format.");
        var version = value.RequiredInt32("version");
        if (version < 1)
            throw new ManufacturingProfileException("profile version must be at least 1.");
        var state = value.RequiredString("state", 10) switch
        {
            "draft" => ManufacturingProfileState.Draft,
            "validated" => ManufacturingProfileState.Validated,
            "approved" => ManufacturingProfileState.Approved,
            "archived" => ManufacturingProfileState.Archived,
            _ => throw new ManufacturingProfileException("profile state is unsupported."),
        };
        return new(new(id, version, hash), state, value.RequiredString("displayName", 120),
            value.RequiredString("productFamily", 80), value.RequiredString("hardwareVariant", 80),
            value.RequiredString("changeNote", 500));
    }

    private static CpuRequirement ParseCpu(JsonElement element)
    {
        var value = new StrictJsonObject(element, "cpu", "architectures", "models", "minimumLogicalCores");
        var architectures = ReadStringArray(value.RequiredArray("architectures"), "cpu architectures", false);
        if (architectures.Any(item => item is not ("amd64" or "arm64")))
        {
            throw new ManufacturingProfileException("cpu architecture is unsupported.");
        }
        var cores = value.RequiredInt32("minimumLogicalCores");
        if (cores is < 1 or > 1024)
        {
            throw new ManufacturingProfileException("minimumLogicalCores must be between 1 and 1024.");
        }
        return new(architectures, ReadStringArray(value.RequiredArray("models"), "cpu models", false), cores);
    }

    private static NumericRange ParseRange(JsonElement element, string scope)
    {
        var value = new StrictJsonObject(element, scope, "minimum", "maximum");
        var minimum = value.RequiredInt64("minimum");
        var maximum = value.RequiredInt64("maximum");
        if (minimum < 0 || maximum < minimum)
        {
            throw new ManufacturingProfileException($"{scope} maximum must be greater than or equal to its non-negative minimum.");
        }
        return new(minimum, maximum);
    }

    private static ImmutableArray<StorageRequirement> ParseStorage(JsonElement array)
    {
        if (array.GetArrayLength() == 0)
            throw new ManufacturingProfileException("storage must not be empty.");
        return array.EnumerateArray().Select((item, index) =>
        {
            var value = new StrictJsonObject(item, $"storage[{index}]", "kind", "sizeBytes", "allowedModels");
            return new StorageRequirement(ParseStorageKind(value.RequiredString("kind", 16)),
                ParseRange(value.Required("sizeBytes"), $"storage[{index}].sizeBytes"),
                ReadStringArray(value.RequiredArray("allowedModels"), $"storage[{index}].allowedModels", true));
        }).ToImmutableArray();
    }

    private static OperatingSystemRequirement ParseOperatingSystem(JsonElement element)
    {
        var value = new StrictJsonObject(element, "operatingSystem", "id", "versions", "architectures",
            "imageVersions", "requireExactKernel", "kernelVersion");
        var exactKernel = value.RequiredBoolean("requireExactKernel");
        var kernel = value.OptionalString("kernelVersion", 120);
        if (exactKernel && kernel is null)
        {
            throw new ManufacturingProfileException("kernelVersion is required when requireExactKernel is true.");
        }
        var architectures = ReadStringArray(value.RequiredArray("architectures"), "OS architectures", false);
        if (architectures.Any(item => item is not ("amd64" or "arm64")))
        {
            throw new ManufacturingProfileException("operating system architecture is unsupported.");
        }
        return new(value.RequiredString("id", 40),
            ReadStringArray(value.RequiredArray("versions"), "OS versions", false), architectures,
            ReadStringArray(value.RequiredArray("imageVersions"), "OS image versions", true), exactKernel, kernel);
    }

    private static ImmutableArray<EthernetPortRequirement> ParseEthernet(JsonElement array)
    {
        if (array.GetArrayLength() != 2)
        {
            throw new ManufacturingProfileException("ethernetPorts must contain exactly two entries.");
        }
        var ports = array.EnumerateArray().Select((item, index) =>
        {
            var value = new StrictJsonObject(item, $"ethernetPorts[{index}]", "physicalLabel", "serialSource");
            return new EthernetPortRequirement(value.RequiredString("physicalLabel", 40),
                value.RequiredBoolean("serialSource"));
        }).ToImmutableArray();
        if (ports.Select(port => port.PhysicalLabel).Distinct(StringComparer.OrdinalIgnoreCase).Count() != ports.Length)
        {
            throw new ManufacturingProfileException("Duplicate Ethernet physical label.");
        }
        if (ports.Count(port => port.IsSerialSource) != 1)
        {
            throw new ManufacturingProfileException("ethernetPorts must define exactly one serial source.");
        }
        return ports;
    }

    private static ImmutableArray<ModuleRequirement> ParseModules(JsonElement array)
    {
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        return array.EnumerateArray().Select((item, index) =>
        {
            var value = new StrictJsonObject(item, $"modules[{index}]", "ruleId", "kind", "mode", "matchers");
            var id = value.RequiredString("ruleId", 64);
            if (!IsSafeId(id))
                throw new ManufacturingProfileException($"Module rule id '{id}' has an invalid format.");
            if (!ids.Add(id))
                throw new ManufacturingProfileException($"Duplicate module rule id '{id}'.");
            return new ModuleRequirement(id, ParseModuleKind(value.RequiredString("kind", 32)),
                ParseModuleMode(value.RequiredString("mode", 16)), ParseMatchers(value.RequiredArray("matchers"), index));
        }).ToImmutableArray();
    }

    private static ImmutableArray<ModuleEvidenceMatcher> ParseMatchers(JsonElement array, int moduleIndex)
    {
        if (array.GetArrayLength() == 0)
        {
            throw new ManufacturingProfileException($"modules[{moduleIndex}].matchers must not be empty.");
        }
        return array.EnumerateArray().Select((item, matcherIndex) =>
        {
            var value = new StrictJsonObject(item, $"modules[{moduleIndex}].matchers[{matcherIndex}]", "bus",
                "vendorId", "productId", "model", "driver", "interfaceName", "deviceNode");
            var matcher = new ModuleEvidenceMatcher(ParseBus(value.RequiredString("bus", 24)),
                value.OptionalString("vendorId", 32), value.OptionalString("productId", 32),
                value.OptionalString("model", 120), value.OptionalString("driver", 120),
                value.OptionalString("interfaceName", 80), value.OptionalString("deviceNode", 160));
            if (matcher is { VendorId: null, ProductId: null, Model: null, Driver: null, InterfaceName: null, DeviceNode: null })
            {
                throw new ManufacturingProfileException("Module matcher must contain stable identity evidence.");
            }
            return matcher;
        }).ToImmutableArray();
    }

    private static ImmutableArray<string> ReadStringArray(JsonElement array, string scope, bool allowEmpty)
    {
        var values = array.EnumerateArray().Select(item => item.ValueKind == JsonValueKind.String
            && !string.IsNullOrWhiteSpace(item.GetString()) && item.GetString()!.Length <= 120
                ? item.GetString()!
                : throw new ManufacturingProfileException($"{scope} must contain non-empty strings.")).ToImmutableArray();
        if (!allowEmpty && values.Length == 0)
            throw new ManufacturingProfileException($"{scope} must not be empty.");
        if (values.Distinct(StringComparer.Ordinal).Count() != values.Length)
        {
            throw new ManufacturingProfileException($"{scope} must not contain duplicates.");
        }
        return values;
    }

    private static bool IsSafeId(string value) => value.Length is >= 3 and <= 64
        && value[0] is >= 'a' and <= 'z' or >= '0' and <= '9'
        && value.All(character => character is >= 'a' and <= 'z' or >= '0' and <= '9' or '.' or '_' or '-');

    private static bool IsReleaseId(string value) => value.Length == 27 && value[14] == '-'
        && value[..14].All(char.IsAsciiDigit)
        && value[15..].All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static StorageKind ParseStorageKind(string value) => value switch
    {
        "unknown" => StorageKind.Unknown,
        "emmc" => StorageKind.Emmc,
        "nvme" => StorageKind.Nvme,
        "sata" => StorageKind.Sata,
        "usb" => StorageKind.Usb,
        _ => throw new ManufacturingProfileException($"Unsupported storage kind '{value}'."),
    };

    private static ModuleKind ParseModuleKind(string value) => value switch
    {
        "cellular4G" => ModuleKind.Cellular4G,
        "lora" => ModuleKind.Lora,
        "wifi" => ModuleKind.Wifi,
        "bluetoothLowEnergy" => ModuleKind.BluetoothLowEnergy,
        _ => throw new ManufacturingProfileException($"Unsupported module kind '{value}'."),
    };

    private static ModuleRequirementMode ParseModuleMode(string value) => value switch
    {
        "required" => ModuleRequirementMode.Required,
        "optional" => ModuleRequirementMode.Optional,
        "forbidden" => ModuleRequirementMode.Forbidden,
        _ => throw new ManufacturingProfileException($"Unsupported module mode '{value}'."),
    };

    private static HardwareBus ParseBus(string value) => value switch
    {
        "unknown" => HardwareBus.Unknown,
        "usb" => HardwareBus.Usb,
        "pciExpress" => HardwareBus.PciExpress,
        "sdio" => HardwareBus.Sdio,
        "uart" => HardwareBus.Uart,
        "spi" => HardwareBus.Spi,
        "integrated" => HardwareBus.Integrated,
        _ => throw new ManufacturingProfileException($"Unsupported hardware bus '{value}'."),
    };

    private sealed record ProfileIdentity(
        ManufacturingProfileReference Reference,
        ManufacturingProfileState State,
        string DisplayName,
        string ProductFamily,
        string HardwareVariant,
        string ChangeNote);
}
