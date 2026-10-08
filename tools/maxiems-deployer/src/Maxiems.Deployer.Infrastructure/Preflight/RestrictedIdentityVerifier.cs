using System.Text.RegularExpressions;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Preflight;

public sealed class RestrictedIdentityVerifier(IRemoteCommandTransport transport)
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    public async Task<RestrictedIdentityVerification> VerifyAsync(CancellationToken cancellationToken = default)
    {
        var identity = await RunAsync(RemoteProgram.CurrentIdentityProbe, cancellationToken).ConfigureAwait(false);
        var broad = await RunAsync(RemoteProgram.BroadSudoDenialProbe, cancellationToken).ConfigureAwait(false);
        var allowlist = await RunAsync(RemoteProgram.SudoAllowlistProbe, cancellationToken).ConfigureAwait(false);
        var text = allowlist.StandardOutput;
        var hasDeploy = text.Contains("/usr/local/libexec/maxiems-deploy-helper", StringComparison.Ordinal);
        var forbidden = text.Contains("/bin/bash", StringComparison.Ordinal)
            || text.Contains("/bin/sh", StringComparison.Ordinal)
            || text.Contains("/usr/bin/id", StringComparison.Ordinal);
        var identityMatch = Regex.Match(identity.StandardOutput, @"(?:^|\s)uid=([1-9]\d*)\(maxiems-deploy\)(?:\s|$)",
            RegexOptions.CultureInvariant);
        var expectedIdentity = identity.ExitCode == 0 && identityMatch.Success
            && int.TryParse(identityMatch.Groups[1].Value, out var userId) && userId < 1000;
        return new(
            expectedIdentity,
            broad.ExitCode != 0,
            allowlist.ExitCode == 0 && hasDeploy && !forbidden);
    }

    private Task<RemoteCommandResult> RunAsync(RemoteProgram program, CancellationToken cancellationToken) =>
        transport.ExecuteAsync(new(program, [], Timeout), cancellationToken);
}
