using Maxiems.Deployer.Core.Contracts;
using Renci.SshNet;
using Renci.SshNet.Common;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class SshNetHostKeyProbe : IHostKeyProbe
{
    public async Task<SshHostFingerprint> ProbeAsync(
        HostEndpoint endpoint,
        string username,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        using var authentication = new NoneAuthenticationMethod(username);
        var connectionInfo = new ConnectionInfo(
            endpoint.Host,
            endpoint.Port,
            username,
            authentication)
        {
            Timeout = timeout,
        };
        using var client = new SshClient(connectionInfo);
        SshHostFingerprint? observed = null;
        client.HostKeyReceived += (_, eventArgs) =>
        {
            observed = SshFingerprint.Create(eventArgs.HostKeyName, eventArgs.FingerPrintSHA256);
            eventArgs.CanTrust = false;
        };
        using var timeoutSource = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutSource.CancelAfter(timeout);
        try
        {
            await client.ConnectAsync(timeoutSource.Token).ConfigureAwait(false);
        }
        catch (SshException) when (observed is not null && !cancellationToken.IsCancellationRequested)
        {
            return observed;
        }
        return observed ?? throw new InvalidOperationException("SSH server did not present a host key.");
    }
}
