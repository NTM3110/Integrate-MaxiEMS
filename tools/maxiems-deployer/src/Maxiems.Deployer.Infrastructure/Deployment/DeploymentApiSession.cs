using System.Buffers.Binary;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Text;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal sealed class DeploymentApiSession : IDisposable
{
    private readonly SshLocalPortForward restForward;
    private readonly HttpClient http;

    public DeploymentApiSession(SshNetTransportSession session, ReadOnlySpan<char> password)
    {
        this.restForward = session.OpenLocalForward(8084);
        this.http = new HttpClient();
        var credentials = Convert.ToBase64String(Encoding.UTF8.GetBytes($"Admin:{password.ToString()}"));
        this.http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", credentials);
        this.Client = new(this.http, new($"http://127.0.0.1:{this.restForward.LocalPort}/jsonrpc"),
            TimeSpan.FromSeconds(20));
    }

    public OpenEmsJsonRpcClient Client
    {
        get;
    }

    public static async Task ProbeModbusAsync(SshNetTransportSession session, CancellationToken cancellationToken)
    {
        using var forward = session.OpenLocalForward(502);
        var deadline = DateTimeOffset.UtcNow.AddMinutes(2);
        Exception? lastFailure = null;
        while (DateTimeOffset.UtcNow < deadline)
        {
            try
            {
                await ProbeModbusOnceAsync(forward.LocalPort, cancellationToken).ConfigureAwait(false);
                return;
            }
            catch (Exception exception) when (exception is IOException or SocketException)
            {
                lastFailure = exception;
            }
            catch (OperationCanceledException exception) when (!cancellationToken.IsCancellationRequested)
            {
                lastFailure = exception;
            }
            await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
        }
        cancellationToken.ThrowIfCancellationRequested();
        throw new TimeoutException("Modbus/TCP did not return a valid protocol response within two minutes.", lastFailure);
    }

    private static async Task ProbeModbusOnceAsync(int port, CancellationToken cancellationToken)
    {
        using var attemptTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        attemptTimeout.CancelAfter(TimeSpan.FromSeconds(10));
        using var client = new TcpClient();
        await client.ConnectAsync("127.0.0.1", port, attemptTimeout.Token).ConfigureAwait(false);
        // OpenEMS exposes its first Modbus record at address 1 for unit ID 1.
        var request = new byte[] { 0, 1, 0, 0, 0, 6, 1, 3, 0, 1, 0, 1 };
        var stream = client.GetStream();
        await stream.WriteAsync(request, attemptTimeout.Token).ConfigureAwait(false);
        var header = new byte[7];
        await stream.ReadExactlyAsync(header, attemptTimeout.Token).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(4, 2));
        if (header[0] != 0 || header[1] != 1 || header[2] != 0 || header[3] != 0 || header[6] != 1
            || length is < 2 or > 254)
        {
            throw new IOException("Modbus/TCP probe returned an invalid protocol response.");
        }
        var pdu = new byte[length - 1];
        await stream.ReadExactlyAsync(pdu, attemptTimeout.Token).ConfigureAwait(false);
        var validRead = pdu[0] == 3 && pdu.Length >= 2 && pdu[1] == pdu.Length - 2;
        var validException = pdu[0] == 0x83 && pdu.Length == 2 && pdu[1] is >= 1 and <= 11;
        if (!validRead && !validException)
        {
            throw new IOException("Modbus/TCP probe returned an invalid protocol response.");
        }
    }

    public void Dispose()
    {
        this.http.Dispose();
        this.restForward.Dispose();
    }
}
