using Renci.SshNet;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class SshLocalPortForward : IDisposable
{
    private readonly SshClient client;
    private ForwardedPortLocal? forwarded;

    internal SshLocalPortForward(SshClient client, ForwardedPortLocal forwarded)
    {
        this.client = client;
        this.forwarded = forwarded;
    }

    public int LocalPort => checked((int)(this.forwarded?.BoundPort
        ?? throw new ObjectDisposedException(nameof(SshLocalPortForward))));

    public void Dispose()
    {
        var current = Interlocked.Exchange(ref this.forwarded, null);
        if (current is null)
            return;
        if (current.IsStarted)
            current.Stop();
        this.client.RemoveForwardedPort(current);
        current.Dispose();
    }
}
