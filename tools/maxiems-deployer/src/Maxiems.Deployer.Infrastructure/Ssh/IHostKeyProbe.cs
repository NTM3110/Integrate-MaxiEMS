using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public interface IHostKeyProbe
{
    Task<SshHostFingerprint> ProbeAsync(
        HostEndpoint endpoint,
        string username,
        TimeSpan timeout,
        CancellationToken cancellationToken = default);
}
