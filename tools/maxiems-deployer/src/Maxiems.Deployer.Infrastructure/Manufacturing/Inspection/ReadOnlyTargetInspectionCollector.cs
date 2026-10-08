using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;
using Maxiems.Deployer.Infrastructure.Preflight;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;

public sealed class ReadOnlyTargetInspectionCollector(IRemoteCommandTransport transport, TimeProvider? timeProvider = null)
{
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(10);
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public async Task<ReadOnlyTargetInspection> CollectAsync(HostEndpoint endpoint, LocalReleaseDescriptor selectedRelease,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        ArgumentNullException.ThrowIfNull(selectedRelease);
        var started = this.clock.GetUtcNow();
        var platform = await new UbuntuPreflightCollector(transport).CollectAsync(cancellationToken).ConfigureAwait(false);
        var hardware = await new HardwareInventoryCollector(transport, this.clock).CollectAsync(cancellationToken)
            .ConfigureAwait(false);
        var installation = await CollectInstallationAsync(platform, cancellationToken).ConfigureAwait(false);
        var compatible = IsCompatible(selectedRelease, platform);
        var completed = this.clock.GetUtcNow();
        var hash = Hash(endpoint, platform, hardware, installation, selectedRelease, compatible);
        return new(Guid.NewGuid(), endpoint, platform, hardware, installation, compatible, hash, started, completed);
    }

    private async Task<TargetInstallationState> CollectInstallationAsync(UbuntuPreflightSnapshot platform,
        CancellationToken cancellationToken)
    {
        var enabled = await RunAsync(RemoteProgram.ManagedServiceEnabledProbe, cancellationToken).ConfigureAwait(false);
        var current = await RunAsync(RemoteProgram.CurrentReleaseProbe, cancellationToken).ConfigureAwait(false);
        var config = await RunAsync(RemoteProgram.ConfigurationPresentProbe, cancellationToken).ConfigureAwait(false);
        var releases = await RunAsync(RemoteProgram.RollbackReleaseProbe, cancellationToken).ConfigureAwait(false);
        var currentId = ParseCurrentRelease(current.StandardOutput);
        var rollback = releases.ExitCode == 0 && releases.StandardOutput
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Any(value => currentId is null || !value.Equals(currentId.Value.Value, StringComparison.Ordinal));
        var ports = platform.Listeners.Where(listener => listener.Port is 80 or 443 or 502 or 8080 or 8084 or 8085)
            .Select(listener => new TargetPortState(listener.Port, Owner(listener.Port, platform), listener.LocalAddress))
            .OrderBy(port => port.Port).ThenBy(port => port.Listener, StringComparer.Ordinal).ToImmutableArray();
        return new(currentId, null, platform.OpenEmsActive, enabled.ExitCode == 0,
            platform.LegacyOpenEmsActive, platform.LegacyOpenEmsEnabled, config.ExitCode == 0, rollback, ports);
    }

    private Task<RemoteCommandResult> RunAsync(RemoteProgram program, CancellationToken cancellationToken) =>
        transport.ExecuteAsync(new(program, [], ProbeTimeout), cancellationToken);

    private static ReleaseId? ParseCurrentRelease(string output)
    {
        var path = output.Trim().Replace('\\', '/');
        if (path.Length == 0 || path.Equals("absent", StringComparison.OrdinalIgnoreCase))
            return null;
        var value = path[(path.LastIndexOf('/') + 1)..];
        try
        {
            return new(value);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    private static TargetServiceOwner Owner(int port, UbuntuPreflightSnapshot snapshot)
    {
        if (port is 80 or 443 && snapshot.NginxActive)
            return TargetServiceOwner.MaxiemsManaged;
        if (port is 502 or 8080 or 8084 or 8085)
        {
            if (snapshot.OpenEmsActive)
                return TargetServiceOwner.MaxiemsManaged;
            if (snapshot.LegacyOpenEmsActive)
                return TargetServiceOwner.LegacyOpenEms;
        }
        return TargetServiceOwner.External;
    }

    private static bool IsCompatible(LocalReleaseDescriptor release, UbuntuPreflightSnapshot platform) =>
        release.Status is LocalReleaseStatus.Embedded or LocalReleaseStatus.ImportedVerified
        && release.SupportedUbuntuVersions.Contains(platform.VersionId, StringComparer.Ordinal)
        && ParseArchitecture(platform.Architecture) is { } architecture
        && release.SupportedArchitectures.Contains(architecture);

    private static SupportedArchitecture? ParseArchitecture(string value) => value.ToLowerInvariant() switch
    {
        "amd64" or "x86_64" => SupportedArchitecture.Amd64,
        "arm64" or "aarch64" => SupportedArchitecture.Arm64,
        _ => null,
    };

    private static string Hash(HostEndpoint endpoint, UbuntuPreflightSnapshot platform,
        HardwareInventorySnapshot hardware, TargetInstallationState installation, LocalReleaseDescriptor release,
        bool compatible)
    {
        var frozen = new
        {
            endpoint.Host,
            endpoint.Port,
            Platform = platform,
            Hardware = hardware,
            Installation = installation,
            SelectedRelease = release,
            Compatible = compatible
        };
        return Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(frozen))).ToLowerInvariant();
    }
}
