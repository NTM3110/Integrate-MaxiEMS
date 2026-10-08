using System.Collections.Immutable;
using System.Globalization;
using System.Text.Json;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

internal static class SystemInventoryParser
{
    public static CpuInventory ParseCpu(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 12 });
            var rows = document.RootElement.GetProperty("lscpu").EnumerateArray()
                .ToDictionary(row => row.GetProperty("field").GetString()!.TrimEnd(':'),
                    row => row.GetProperty("data").GetString() ?? string.Empty, StringComparer.OrdinalIgnoreCase);
            var architecture = Required(rows, "Architecture");
            var model = Bound(rows.GetValueOrDefault("Model name", rows.GetValueOrDefault("Model", "Unknown")));
            if (!int.TryParse(Required(rows, "CPU(s)"), NumberStyles.None, CultureInfo.InvariantCulture, out var cores)
                || cores <= 0)
                throw new HardwareInventoryException("lscpu CPU count is invalid.");
            return new(architecture, model, cores, Evidence("lscpu", $"{architecture}; {model}; {cores} logical cores"));
        }
        catch (Exception exception) when (exception is JsonException or KeyNotFoundException
            or InvalidOperationException or ArgumentException)
        {
            throw new HardwareInventoryException("lscpu returned malformed JSON.", exception);
        }
    }

    public static MemoryInventory ParseMemory(string output)
    {
        var memory = output.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault(line => line.StartsWith("Mem:", StringComparison.OrdinalIgnoreCase));
        var columns = memory?.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (columns is null || columns.Length < 2 || !long.TryParse(columns[1], NumberStyles.None,
            CultureInfo.InvariantCulture, out var bytes) || bytes <= 0)
        {
            throw new HardwareInventoryException("free returned malformed memory data.");
        }
        return new(bytes, Evidence("free --bytes", $"{bytes} bytes"));
    }

    public static ImmutableArray<StorageDeviceInventory> ParseStorage(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 24 });
            var result = ImmutableArray.CreateBuilder<StorageDeviceInventory>();
            foreach (var item in document.RootElement.GetProperty("blockdevices").EnumerateArray())
                AddStorage(item, result);
            if (result.Count == 0)
                throw new HardwareInventoryException("lsblk found no disk devices.");
            return result.ToImmutable();
        }
        catch (Exception exception) when (exception is JsonException or InvalidOperationException
            or KeyNotFoundException or FormatException or OverflowException)
        {
            throw new HardwareInventoryException("lsblk returned malformed JSON.", exception);
        }
    }

    public static OperatingSystemInventory ParseOperatingSystem(
        string osRelease, string kernel, string board, string image, string architecture)
    {
        try
        {
            var values = osRelease.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
                .Where(line => line.Contains('=')).Select(line => line.Split('=', 2))
                .ToDictionary(parts => parts[0], parts => parts[1].Trim('"'), StringComparer.Ordinal);
            if (!values.TryGetValue("ID", out var id) || !values.TryGetValue("VERSION_ID", out var version)
                || id.Length > 40 || version.Length > 40)
            {
                throw new HardwareInventoryException("os-release is missing ID or VERSION_ID.");
            }
            var kernelParts = kernel.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (kernelParts.Length < 1)
                throw new HardwareInventoryException("uname returned no kernel version.");
            return new(id, version, architecture, kernelParts[0], NullIfEmpty(image), NullIfEmpty(board), null,
                Evidence("os-release/uname/board", $"{id} {version}; kernel {kernelParts[0]}; {Bound(board)}"));
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException)
        {
            throw new HardwareInventoryException("Operating system probe output is malformed.", exception);
        }
    }

    private static void AddStorage(JsonElement item, ImmutableArray<StorageDeviceInventory>.Builder result)
    {
        if (item.TryGetProperty("type", out var type) && type.GetString() == "disk")
        {
            var name = item.GetProperty("name").GetString() ?? throw new HardwareInventoryException("lsblk disk name missing.");
            var sizeValue = item.GetProperty("size");
            var size = sizeValue.ValueKind == JsonValueKind.Number ? sizeValue.GetInt64()
                : long.Parse(sizeValue.GetString()!, CultureInfo.InvariantCulture);
            var transport = Optional(item, "tran");
            var model = Optional(item, "model");
            var mounts = item.TryGetProperty("mountpoints", out var mountpoints) && mountpoints.ValueKind == JsonValueKind.Array
                ? mountpoints.EnumerateArray().Select(value => value.GetString()).ToArray() : [];
            result.Add(new(name, Kind(name, transport), model, Optional(item, "serial"), size,
                mounts.Contains("/", StringComparer.Ordinal), Evidence("lsblk", $"{name}; {model}; {size} bytes")));
        }
        if (item.TryGetProperty("children", out var children) && children.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in children.EnumerateArray())
                AddStorage(child, result);
        }
    }

    private static StorageKind Kind(string name, string? transport) => transport?.ToLowerInvariant() switch
    {
        "nvme" => StorageKind.Nvme,
        "sata" or "ata" => StorageKind.Sata,
        "usb" => StorageKind.Usb,
        _ when name.StartsWith("mmcblk", StringComparison.Ordinal) => StorageKind.Emmc,
        _ => StorageKind.Unknown,
    };

    private static string Required(Dictionary<string, string> values, string key) =>
        values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value
            : throw new HardwareInventoryException($"lscpu field '{key}' is missing.");

    private static string? Optional(JsonElement item, string name) => item.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.String ? NullIfEmpty(value.GetString()) : null;
    private static string? NullIfEmpty(string? value) => string.IsNullOrWhiteSpace(value)
        ? null : Bound(value.Trim().TrimEnd('\0'));
    private static HardwareEvidence Evidence(string source, string summary) =>
        new(source, EvidenceAvailability.Observed, Bound(summary));
    private static string Bound(string value) => new(value.Where(character => !char.IsControl(character)).Take(512).ToArray());
}
