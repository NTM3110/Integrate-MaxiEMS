using System.Collections.Immutable;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal sealed class DeploymentRemoteOperations(IRemoteCommandTransport transport, TimeSpan timeout)
{
    public Task<string> RunAsync(
        string operation,
        IEnumerable<string>? arguments = null,
        CancellationToken cancellationToken = default)
    {
        return RunAsync(operation, arguments, null, cancellationToken);
    }

    public async Task<string> RunAsync(
        string operation,
        IEnumerable<string>? arguments,
        TimeSpan? timeoutOverride,
        CancellationToken cancellationToken = default)
    {
        var values = new[] { operation }.Concat(arguments ?? []);
        var request = new RemoteCommandRequest(RemoteProgram.DeployHelper,
            values.Select(value => new RemoteArgument(value)).ToImmutableArray(), timeoutOverride ?? timeout);
        var result = await transport.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            var detail = result.StandardError.Trim();
            if (detail.Length > 4096)
                detail = detail[^4096..];
            throw new IOException($"Deployment helper operation '{operation}' failed with exit {result.ExitCode}: {detail}");
        }
        return result.StandardOutput.Trim();
    }
}
