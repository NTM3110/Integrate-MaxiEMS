using System.Collections.Immutable;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Tailscale;

public sealed class SshTailscaleNodeClient(
    IRemoteCommandTransport transport,
    TimeSpan operationTimeout) : ITailscaleNodeClient
{
    public Task<string> StartLoginAsync(CancellationToken cancellationToken) =>
        ExecuteAsync("up", cancellationToken);

    public Task<string> GetStatusJsonAsync(CancellationToken cancellationToken) =>
        ExecuteAsync("status", cancellationToken);

    private async Task<string> ExecuteAsync(string action, CancellationToken cancellationToken)
    {
        var request = new RemoteCommandRequest(
            RemoteProgram.DeployHelper,
            ImmutableArray.Create(new RemoteArgument("tailscale"), new RemoteArgument(action)),
            operationTimeout);
        var result = await transport.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0 && !result.StandardError.Contains("login.tailscale.com/", StringComparison.Ordinal))
        {
            throw new IOException($"Tailscale helper action '{action}' failed with exit {result.ExitCode}.");
        }
        return string.Concat(result.StandardOutput, Environment.NewLine, result.StandardError);
    }
}
