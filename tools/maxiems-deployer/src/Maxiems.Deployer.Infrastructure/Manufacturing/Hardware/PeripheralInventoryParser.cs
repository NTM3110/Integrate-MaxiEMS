using System.Collections.Immutable;
using System.Text.Json;
using System.Text.RegularExpressions;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

internal static partial class PeripheralInventoryParser
{
    public static ImmutableArray<HardwarePeripheralInventory> ParseUsb(string output)
    {
        var lines = Lines(output);
        var result = lines.Select(line => UsbLine().Match(line)).Where(match => match.Success)
            .Select(match => new HardwarePeripheralInventory(HardwareBus.Usb,
                match.Groups[1].Value.ToLowerInvariant(), match.Groups[2].Value.ToLowerInvariant(),
                NullIfEmpty(match.Groups[3].Value), null, [], [], Evidence("lsusb", match.Value)))
            .ToImmutableArray();
        if (lines.Length > 0 && result.Length != lines.Length)
        {
            throw new HardwareInventoryException("lsusb returned malformed output.");
        }
        return result;
    }

    public static ImmutableArray<HardwarePeripheralInventory> ParsePci(string output)
    {
        var lines = Lines(output);
        var matches = lines.Select(line => PciIds().Match(line)).Where(match => match.Success).ToArray();
        if (lines.Length > 0 && matches.Length == 0)
        {
            throw new HardwareInventoryException("lspci returned malformed output.");
        }
        return matches.Select(match => new HardwarePeripheralInventory(HardwareBus.PciExpress,
            match.Groups[1].Value.ToLowerInvariant(), match.Groups[2].Value.ToLowerInvariant(),
            Bound(match.Value), Driver().Match(match.Value) is { Success: true } driver ? driver.Groups[1].Value : null,
            [], [], Evidence("lspci", match.Value))).ToImmutableArray();
    }

    public static ImmutableArray<string> ParseDeviceNodes(string output) => output
        .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
        .Where(path => path.StartsWith("/dev/", StringComparison.Ordinal) && path.Length <= 160
            && path[5..].All(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-'))
        .Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToImmutableArray();

    public static ImmutableArray<ModuleInventory> ParseModules(
        ProbeObservation modem, ProbeObservation wireless, ProbeObservation bluetooth)
    {
        var modules = ImmutableArray.CreateBuilder<ModuleInventory>();
        modules.Add(ParseModem(modem));
        modules.Add(ParseWireless(wireless));
        modules.Add(ParseBluetooth(bluetooth));
        return modules.ToImmutable();
    }

    private static ModuleInventory ParseModem(ProbeObservation probe)
    {
        if (!probe.Available)
            return Unavailable(ModuleKind.Cellular4G, "mmcli", probe.Summary);
        try
        {
            using var document = JsonDocument.Parse(probe.Output);
            var paths = document.RootElement.TryGetProperty("modem-list", out var list)
                && list.ValueKind == JsonValueKind.Array
                ? list.EnumerateArray().Select(item => item.GetString()).Where(item => item is { Length: <= 160 }).Cast<string>()
                    .ToImmutableArray() : [];
            return new(ModuleKind.Cellular4G, HardwareBus.Unknown, null, null, null, null, [], paths,
                new("mmcli", paths.Length == 0 ? EvidenceAvailability.NotApplicable : EvidenceAvailability.Observed,
                    paths.Length == 0 ? "No modem reported." : Bound(string.Join(", ", paths))));
        }
        catch (JsonException exception)
        {
            throw new HardwareInventoryException("mmcli returned malformed JSON.", exception);
        }
    }

    private static ModuleInventory ParseWireless(ProbeObservation probe)
    {
        if (!probe.Available)
            return Unavailable(ModuleKind.Wifi, "iw", probe.Summary);
        var interfaces = probe.Output.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.StartsWith("Interface ", StringComparison.Ordinal))
            .Select(line => line[10..]).Where(IsInterface).Distinct(StringComparer.Ordinal).ToImmutableArray();
        return new(ModuleKind.Wifi, HardwareBus.Unknown, null, null, null, null, interfaces, [],
            new("iw", interfaces.Length == 0 ? EvidenceAvailability.NotApplicable : EvidenceAvailability.Observed,
                interfaces.Length == 0 ? "No wireless interface reported." : Bound(string.Join(", ", interfaces))));
    }

    private static ModuleInventory ParseBluetooth(ProbeObservation probe)
    {
        if (!probe.Available)
            return Unavailable(ModuleKind.BluetoothLowEnergy, "bluetoothctl", probe.Summary);
        var controllers = probe.Output.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.StartsWith("Controller ", StringComparison.Ordinal)).ToArray();
        return new(ModuleKind.BluetoothLowEnergy, HardwareBus.Unknown, null, null,
            controllers.FirstOrDefault(), null, [], [], new("bluetoothctl",
                controllers.Length == 0 ? EvidenceAvailability.NotApplicable : EvidenceAvailability.Observed,
                controllers.Length == 0 ? "No Bluetooth controller reported." : Bound(controllers[0])));
    }

    private static ModuleInventory Unavailable(ModuleKind kind, string source, string summary) =>
        new(kind, HardwareBus.Unknown, null, null, null, null, [], [],
            new(source, EvidenceAvailability.Unavailable, Bound(summary)));
    private static bool IsInterface(string value) => value.Length is >= 1 and <= 15
        && value.All(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-');
    private static string? NullIfEmpty(string value) => string.IsNullOrWhiteSpace(value) ? null : Bound(value);
    private static string[] Lines(string output) =>
        output.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
    private static HardwareEvidence Evidence(string source, string summary) =>
        new(source, EvidenceAvailability.Observed, Bound(summary));
    private static string Bound(string value) => new(value.Where(character => !char.IsControl(character)).Take(512).ToArray());

    [GeneratedRegex(@"\bID\s+([0-9A-Fa-f]{4}):([0-9A-Fa-f]{4})\s*(.*)$", RegexOptions.CultureInvariant)]
    private static partial Regex UsbLine();
    [GeneratedRegex(@"\[([0-9A-Fa-f]{4}):([0-9A-Fa-f]{4})\]", RegexOptions.CultureInvariant)]
    private static partial Regex PciIds();
    [GeneratedRegex(@"(?:Kernel driver in use:|Driver=)([A-Za-z0-9_.-]+)", RegexOptions.CultureInvariant)]
    private static partial Regex Driver();
}

internal sealed record ProbeObservation(bool Available, string Output, string Summary);
