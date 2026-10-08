using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Tailscale;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public interface ITailscaleNodeClientFactory
{
    ITailscaleNodeClient Create(IRemoteCommandTransport transport);
}

public sealed class SshTailscaleNodeClientFactory : ITailscaleNodeClientFactory
{
    public ITailscaleNodeClient Create(IRemoteCommandTransport transport) =>
        new SshTailscaleNodeClient(transport, TimeSpan.FromMinutes(2));
}
