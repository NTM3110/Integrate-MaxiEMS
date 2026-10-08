using System.Diagnostics;
using System.Security.Cryptography;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Renci.SshNet;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class SshNetTransportSession : IRemoteCommandTransport, IRemoteFileTransport, IAsyncDisposable
{
    private const string UploadDirectory = "/var/lib/maxiems-deploy/incoming";

    private readonly SshClient sshClient;
    private readonly SftpClient sftpClient;
    private readonly TimeSpan operationTimeout;
    private readonly string uploadDirectory;

    private SshNetTransportSession(
        SshClient sshClient,
        SftpClient sftpClient,
        TimeSpan operationTimeout,
        string uploadDirectory)
    {
        this.sshClient = sshClient;
        this.sftpClient = sftpClient;
        this.operationTimeout = operationTimeout;
        this.uploadDirectory = uploadDirectory;
    }

    public static Task<SshNetTransportSession> ConnectAsync(
        SshTransportOptions options,
        HostTrustStore trustStore,
        CancellationToken cancellationToken = default) =>
        ConnectCoreAsync(options, trustStore, UploadDirectory, cancellationToken);

    internal static Task<SshNetTransportSession> ConnectForFixtureAsync(
        SshTransportOptions options,
        HostTrustStore trustStore,
        string uploadDirectory,
        CancellationToken cancellationToken = default) =>
        ConnectCoreAsync(options, trustStore, uploadDirectory, cancellationToken);

    private static async Task<SshNetTransportSession> ConnectCoreAsync(
        SshTransportOptions options,
        HostTrustStore trustStore,
        string uploadDirectory,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(trustStore);
        var trusted = await trustStore.FindAsync(options.Endpoint, cancellationToken).ConfigureAwait(false)
            ?? throw new SshTrustRequiredException(options.Endpoint);
        var expected = new SshHostFingerprint(trusted.Algorithm, trusted.Fingerprint);
        var privateKey = options.PrivateKeyPassphrase.IsEmpty
            ? new PrivateKeyFile(options.PrivateKeyPath)
            : new PrivateKeyFile(options.PrivateKeyPath, options.PrivateKeyPassphrase.ToString());
        var connectionInfo = new PrivateKeyConnectionInfo(
            options.Endpoint.Host,
            options.Endpoint.Port,
            options.Username,
            privateKey)
        {
            Timeout = options.ConnectTimeout,
        };
        var sshClient = new SshClient(connectionInfo);
        var sftpClient = new SftpClient(connectionInfo);
        ApplyPinnedHostKey(sshClient, expected);
        ApplyPinnedHostKey(sftpClient, expected);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(options.ConnectTimeout);
        try
        {
            await sshClient.ConnectAsync(timeout.Token).ConfigureAwait(false);
            await sftpClient.ConnectAsync(timeout.Token).ConfigureAwait(false);
            return new(sshClient, sftpClient, options.OperationTimeout, uploadDirectory.TrimEnd('/'));
        }
        catch
        {
            sshClient.Dispose();
            sftpClient.Dispose();
            privateKey.Dispose();
            throw;
        }
    }

    public async Task<RemoteCommandResult> ExecuteAsync(
        RemoteCommandRequest request,
        CancellationToken cancellationToken = default)
    {
        var commandText = RemoteCommandEncoder.Encode(request);
        using var command = this.sshClient.CreateCommand(commandText);
        command.CommandTimeout = request.Timeout < this.operationTimeout ? request.Timeout : this.operationTimeout;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(command.CommandTimeout);
        var stopwatch = Stopwatch.StartNew();
        await command.ExecuteAsync(timeout.Token).ConfigureAwait(false);
        return new(
            command.ExitStatus ?? -1,
            command.Result,
            command.Error,
            stopwatch.Elapsed);
    }

    public async Task<SftpUploadResult> UploadToRandomStagingAsync(
        Stream source,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (!source.CanRead || !source.CanSeek)
        {
            throw new ArgumentException("Upload source must be readable and seekable.", nameof(source));
        }
        var start = source.Position;
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(source, cancellationToken).ConfigureAwait(false))
            .ToLowerInvariant();
        var length = source.Position - start;
        source.Position = start;
        var remotePath = $"{this.uploadDirectory}/.upload-{Guid.NewGuid():N}";
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(this.operationTimeout);
        await this.sftpClient.UploadFileAsync(source, remotePath, timeout.Token).ConfigureAwait(false);
        using var verification = new HashingWriteStream();
        await this.sftpClient.DownloadFileAsync(remotePath, verification, timeout.Token).ConfigureAwait(false);
        if (verification.BytesWritten != length || !hash.Equals(verification.GetSha256(), StringComparison.OrdinalIgnoreCase))
        {
            this.sftpClient.DeleteFile(remotePath);
            throw new InvalidDataException($"Remote SHA-256 verification failed for staging file {remotePath}.");
        }
        return new(remotePath, length, hash);
    }

    public SshLocalPortForward OpenLocalForward(uint remotePort)
    {
        if (remotePort is 0 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(remotePort));
        }
        var forwarded = new ForwardedPortLocal("127.0.0.1", 0, "127.0.0.1", remotePort);
        this.sshClient.AddForwardedPort(forwarded);
        try
        {
            forwarded.Start();
            return new(this.sshClient, forwarded);
        }
        catch
        {
            this.sshClient.RemoveForwardedPort(forwarded);
            forwarded.Dispose();
            throw;
        }
    }

    public ValueTask DisposeAsync()
    {
        this.sftpClient.Dispose();
        this.sshClient.Dispose();
        return ValueTask.CompletedTask;
    }

    private static void ApplyPinnedHostKey(BaseClient client, SshHostFingerprint expected)
    {
        client.HostKeyReceived += (_, eventArgs) =>
        {
            var observed = SshFingerprint.Create(eventArgs.HostKeyName, eventArgs.FingerPrintSHA256);
            eventArgs.CanTrust = SshFingerprint.FixedEquals(expected, observed);
        };
    }
}
