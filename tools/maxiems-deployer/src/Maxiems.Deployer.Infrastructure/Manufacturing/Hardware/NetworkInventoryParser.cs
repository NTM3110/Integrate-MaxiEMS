using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

internal sealed record NetworkLinkCandidate(string LinuxName, MacAddress CurrentMac, bool LinkUp, long? SpeedMegabits);

internal static class NetworkInventoryParser
{
    public static ImmutableArray<NetworkLinkCandidate> Parse(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 16 });
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                throw new HardwareInventoryException("ip link output must be a JSON array.");
            }
            return document.RootElement.EnumerateArray()
                .Where(IsWiredCandidate)
                .Select(ParseCandidate)
                .OrderBy(link => link.LinuxName, StringComparer.Ordinal)
                .ToImmutableArray();
        }
        catch (JsonException exception)
        {
            throw new HardwareInventoryException("ip link returned malformed JSON.", exception);
        }
    }

    public static MacAddress? ParsePermanentMac(string output)
    {
        var line = output.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault(value => value.StartsWith("Permanent address:", StringComparison.OrdinalIgnoreCase));
        if (line is null)
            return null;
        var value = line[(line.IndexOf(':', StringComparison.Ordinal) + 1)..].Trim();
        if (value.Equals("not set", StringComparison.OrdinalIgnoreCase))
            return null;
        try
        {
            return new(value);
        }
        catch (ArgumentException exception)
        {
            throw new HardwareInventoryException("ethtool returned an invalid permanent MAC address.", exception);
        }
    }

    private static bool IsWiredCandidate(JsonElement item)
    {
        if (!item.TryGetProperty("ifname", out var nameValue) || nameValue.ValueKind != JsonValueKind.String
            || !item.TryGetProperty("address", out var address) || address.ValueKind != JsonValueKind.String)
            return false;
        var name = nameValue.GetString()!;
        if (name.Length is < 1 or > 15 || name.Any(character =>
            !char.IsAsciiLetterOrDigit(character) && character is not ('_' or '.' or '-')))
        {
            throw new HardwareInventoryException("ip link returned an invalid interface name.");
        }
        if (name is "lo" || name.StartsWith("tailscale", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("docker", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("veth", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("br-", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("virbr", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("wl", StringComparison.OrdinalIgnoreCase))
            return false;
        if (item.TryGetProperty("link_type", out var type)
            && !string.Equals(type.GetString(), "ether", StringComparison.OrdinalIgnoreCase))
            return false;
        if (item.TryGetProperty("linkinfo", out var linkInfo) && linkInfo.ValueKind == JsonValueKind.Object
            && linkInfo.TryGetProperty("info_kind", out var kind)
            && kind.GetString() is { } kindText && kindText is not ("ether" or ""))
            return false;
        return true;
    }

    private static NetworkLinkCandidate ParseCandidate(JsonElement item)
    {
        try
        {
            var flags = item.TryGetProperty("flags", out var flagValues) && flagValues.ValueKind == JsonValueKind.Array
                ? flagValues.EnumerateArray().Select(value => value.GetString()).ToArray()
                : [];
            long? speed = item.TryGetProperty("linkinfo", out var linkInfo)
                && linkInfo.ValueKind == JsonValueKind.Object && linkInfo.TryGetProperty("speed", out var speedValue)
                && speedValue.TryGetInt64(out var parsedSpeed) ? parsedSpeed : null;
            return new(item.GetProperty("ifname").GetString()!, new(item.GetProperty("address").GetString()!),
                flags.Contains("UP", StringComparer.Ordinal) || item.TryGetProperty("operstate", out var state)
                    && state.GetString() == "UP", speed);
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException)
        {
            throw new HardwareInventoryException("ip link returned a malformed wired interface.", exception);
        }
    }
}
