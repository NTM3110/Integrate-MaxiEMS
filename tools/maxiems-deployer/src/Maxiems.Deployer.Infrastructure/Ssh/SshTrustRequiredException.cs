using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class SshTrustRequiredException(HostEndpoint endpoint)
    : InvalidOperationException($"SSH host trust must be confirmed before connecting to {SshEndpointNormalizer.Normalize(endpoint)}.")
{
    public HostEndpoint Endpoint { get; } = endpoint;
}
