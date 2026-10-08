using System.Collections.Immutable;
using System.Globalization;
using System.Text.RegularExpressions;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Preflight;

public sealed partial class UbuntuPreflightCollector(IRemoteCommandTransport transport)
{
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(10);

    public async Task<UbuntuPreflightSnapshot> CollectAsync(CancellationToken cancellationToken = default)
    {
        var os = ParseOsRelease((await RunAsync(RemoteProgram.OsReleaseProbe, cancellationToken)).StandardOutput);
        var architecture = (await RunAsync(RemoteProgram.ArchitectureProbe, cancellationToken)).StandardOutput.Trim();
        var disk = ParseLastInteger((await RunAsync(RemoteProgram.DiskProbe, cancellationToken)).StandardOutput, "disk");
        var memory = ParseMemory((await RunAsync(RemoteProgram.MemoryProbe, cancellationToken)).StandardOutput);
        var timeText = (await RunAsync(RemoteProgram.TimeProbe, cancellationToken)).StandardOutput.Trim();
        if (!DateTimeOffset.TryParse(timeText, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var time))
        {
            throw new FormatException("Remote time probe returned an invalid timestamp.");
        }
        // Offline payloads require no public DNS lookup.
        var sudo = await RunAsync(RemoteProgram.SudoProbe, cancellationToken);
        var ports = ParsePorts((await RunAsync(RemoteProgram.PortProbe, cancellationToken)).StandardOutput);
        var services = (await RunAsync(RemoteProgram.ServiceProbe, cancellationToken)).StandardOutput
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        var legacyEnabled = await RunAsync(RemoteProgram.LegacyServiceEnabledProbe, cancellationToken);
        var accounts = ParseAccounts((await RunAsync(RemoteProgram.AccountProbe, cancellationToken)).StandardOutput);
        return new(
            os.GetValueOrDefault("ID", string.Empty),
            os.GetValueOrDefault("VERSION_ID", string.Empty),
            architecture,
            disk,
            memory,
            time,
            false,
            sudo.ExitCode == 0,
            ports,
            services.ElementAtOrDefault(0) == "active",
            services.ElementAtOrDefault(1) == "active",
            services.ElementAtOrDefault(2) == "active",
            legacyEnabled.ExitCode == 0,
            accounts);
    }

    private Task<RemoteCommandResult> RunAsync(RemoteProgram program, CancellationToken cancellationToken) =>
        transport.ExecuteAsync(new(program, [], ProbeTimeout), cancellationToken);

    private static Dictionary<string, string> ParseOsRelease(string text)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in text.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = line.IndexOf('=', StringComparison.Ordinal);
            if (separator <= 0)
            {
                continue;
            }
            values[line[..separator]] = line[(separator + 1)..].Trim('"', '\'');
        }
        return values;
    }

    private static long ParseLastInteger(string text, string probe)
    {
        var token = text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).LastOrDefault();
        if (!long.TryParse(token, NumberStyles.None, CultureInfo.InvariantCulture, out var value))
        {
            throw new FormatException($"Remote {probe} probe returned no integer value.");
        }
        return value;
    }

    private static long ParseMemory(string text)
    {
        var line = text.Split('\n', StringSplitOptions.TrimEntries)
            .FirstOrDefault(value => value.StartsWith("Mem:", StringComparison.Ordinal));
        if (line is null)
        {
            throw new FormatException("Remote memory probe returned no Mem row.");
        }
        var tokens = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        return long.TryParse(tokens.ElementAtOrDefault(1), NumberStyles.None, CultureInfo.InvariantCulture, out var total)
            ? total
            : throw new FormatException("Remote memory probe returned an invalid total.");
    }

    private static ImmutableArray<PortListener> ParsePorts(string text)
    {
        var listeners = ImmutableArray.CreateBuilder<PortListener>();
        foreach (var line in text.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var match = PortPattern().Match(line);
            if (match.Success && int.TryParse(match.Groups[2].Value, NumberStyles.None,
                CultureInfo.InvariantCulture, out var port))
            {
                listeners.Add(new(port, match.Groups[1].Value));
            }
        }
        return listeners.ToImmutable();
    }

    private static ImmutableArray<LinuxAccount> ParseAccounts(string text)
    {
        var accounts = ImmutableArray.CreateBuilder<LinuxAccount>();
        foreach (var line in text.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var fields = line.Split(':');
            if (fields.Length >= 7 && int.TryParse(fields[2], NumberStyles.None, CultureInfo.InvariantCulture, out var uid))
            {
                accounts.Add(new(fields[0], uid, fields[5], fields[6]));
            }
        }
        return accounts.ToImmutable();
    }

    [GeneratedRegex(@"\s([^\s]+):(\d+)\s")]
    private static partial Regex PortPattern();
}
