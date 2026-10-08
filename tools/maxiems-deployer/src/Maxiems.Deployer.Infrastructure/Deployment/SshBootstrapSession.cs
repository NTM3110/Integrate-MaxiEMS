using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Preflight;
using Maxiems.Deployer.Infrastructure.Ssh;
using Renci.SshNet;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public sealed class SshBootstrapSession : IRemoteCommandTransport, IAsyncDisposable
{
    internal const short SshDirectoryMode = 700;
    internal const short AuthorizedKeysMode = 600;

    private readonly SshClient ssh;
    private readonly SftpClient sftp;
    private readonly TimeSpan operationTimeout;
    private readonly ReadOnlyMemory<char> bootstrapPassword;

    private SshBootstrapSession(
        SshClient ssh,
        SftpClient sftp,
        TimeSpan operationTimeout,
        ReadOnlyMemory<char> bootstrapPassword)
    {
        this.ssh = ssh;
        this.sftp = sftp;
        this.operationTimeout = operationTimeout;
        this.bootstrapPassword = bootstrapPassword;
    }

    public static async Task<SshBootstrapSession> ConnectAsync(
        SshTransportOptions options,
        HostTrustStore trustStore,
        CancellationToken cancellationToken)
    {
        var trusted = await trustStore.FindAsync(options.Endpoint, cancellationToken).ConfigureAwait(false)
            ?? throw new SshTrustRequiredException(options.Endpoint);
        var expected = new SshHostFingerprint(trusted.Algorithm, trusted.Fingerprint);
        var connection = CreateConnectionInfo(options);
        var ssh = new SshClient(connection);
        var sftp = new SftpClient(connection);
        Pin(ssh, expected);
        Pin(sftp, expected);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(options.ConnectTimeout);
        try
        {
            await ssh.ConnectAsync(timeout.Token).ConfigureAwait(false);
            await sftp.ConnectAsync(timeout.Token).ConfigureAwait(false);
            return new(ssh, sftp, options.OperationTimeout, options.BootstrapPassword);
        }
        catch
        {
            ssh.Dispose();
            sftp.Dispose();
            throw;
        }
    }

    public async Task InstallAsync(
        IReadOnlyCollection<BootstrapPayloadAsset> assets,
        string publicKey,
        CancellationToken cancellationToken)
    {
        await this.EnrollPublicKeyAsync(publicKey, cancellationToken).ConfigureAwait(false);
        var staging = $"/tmp/maxiems-bootstrap-{Guid.NewGuid():N}";
        await RunCheckedAsync($"/usr/bin/install -d -m 0700 -- {staging}", cancellationToken).ConfigureAwait(false);
        try
        {
            foreach (var asset in assets)
            {
                await using var source = new MemoryStream(asset.Content, writable: false);
                await this.sftp.UploadFileAsync(source, $"{staging}/{asset.RemoteName}", cancellationToken)
                    .ConfigureAwait(false);
            }
            await RunCheckedAsync($"/usr/bin/chmod 0700 -- {staging}/maxiems-bootstrap-helper", cancellationToken)
                .ConfigureAwait(false);
            var helper = $"{staging}/maxiems-bootstrap-helper install-layout {staging}";
            if (this.bootstrapPassword.IsEmpty)
            {
                await RunCheckedAsync(BuildSudoCommand(helper, usePassword: false), cancellationToken).ConfigureAwait(false);
            }
            else
            {
                await RunSudoCheckedAsync(BuildSudoCommand(helper, usePassword: true), cancellationToken)
                    .ConfigureAwait(false);
            }
        }
        finally
        {
            await RunCheckedAsync($"/usr/bin/rm -rf -- {staging}", CancellationToken.None).ConfigureAwait(false);
        }
    }

    internal static ConnectionInfo CreateConnectionInfo(SshTransportOptions options)
    {
        if (!options.BootstrapPassword.IsEmpty)
        {
            return new PasswordConnectionInfo(options.Endpoint.Host, options.Endpoint.Port,
                options.Username, options.BootstrapPassword.ToString())
            {
                Timeout = options.ConnectTimeout
            };
        }
        var privateKey = options.PrivateKeyPassphrase.IsEmpty
            ? new PrivateKeyFile(options.PrivateKeyPath)
            : new PrivateKeyFile(options.PrivateKeyPath, options.PrivateKeyPassphrase.ToString());
        return new PrivateKeyConnectionInfo(options.Endpoint.Host, options.Endpoint.Port,
            options.Username, privateKey)
        {
            Timeout = options.ConnectTimeout
        };
    }

    internal static string BuildSudoCommand(string helper, bool usePassword) => usePassword
        ? $"/usr/bin/sudo -S -p '' -- {helper}"
        : $"/usr/bin/sudo -n {helper}";

    public async Task<RemoteCommandResult> ExecuteAsync(
        RemoteCommandRequest request,
        CancellationToken cancellationToken = default)
    {
        if (request.Program == RemoteProgram.SudoProbe && !this.bootstrapPassword.IsEmpty)
        {
            return await this.ExecutePasswordSudoAsync(
                EncodeCommand(request, usePasswordSudo: true), request.Timeout, cancellationToken).ConfigureAwait(false);
        }
        var started = Stopwatch.StartNew();
        using var command = this.ssh.CreateCommand(EncodeCommand(request, usePasswordSudo: false));
        command.CommandTimeout = request.Timeout < this.operationTimeout ? request.Timeout : this.operationTimeout;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(command.CommandTimeout);
        await command.ExecuteAsync(timeout.Token).ConfigureAwait(false);
        return new(command.ExitStatus ?? -1, command.Result, command.Error, started.Elapsed);
    }

    internal static string EncodeCommand(RemoteCommandRequest request, bool usePasswordSudo) =>
        request.Program == RemoteProgram.SudoProbe && usePasswordSudo
            ? BuildSudoCommand("/usr/bin/true", usePassword: true)
            : RemoteCommandEncoder.Encode(request);

    public ValueTask DisposeAsync()
    {
        this.sftp.Dispose();
        this.ssh.Dispose();
        return ValueTask.CompletedTask;
    }

    private async Task RunCheckedAsync(string text, CancellationToken cancellationToken)
    {
        using var command = this.ssh.CreateCommand(text);
        command.CommandTimeout = this.operationTimeout;
        await command.ExecuteAsync(cancellationToken).ConfigureAwait(false);
        if (command.ExitStatus != 0)
        {
            throw new IOException($"Bootstrap operation failed with exit {command.ExitStatus}.");
        }
    }

    private async Task RunSudoCheckedAsync(string text, CancellationToken cancellationToken)
    {
        var result = await this.ExecutePasswordSudoAsync(text, this.operationTimeout, cancellationToken)
            .ConfigureAwait(false);
        if (result.ExitCode != 0)
            throw new IOException($"Bootstrap operation failed with exit {result.ExitCode}.");
    }

    private async Task<RemoteCommandResult> ExecutePasswordSudoAsync(
        string text,
        TimeSpan timeoutValue,
        CancellationToken cancellationToken)
    {
        var started = Stopwatch.StartNew();
        using var command = this.ssh.CreateCommand(text);
        command.CommandTimeout = timeoutValue < this.operationTimeout ? timeoutValue : this.operationTimeout;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(command.CommandTimeout);
        var execute = command.ExecuteAsync(timeout.Token);
        var passwordBytes = new byte[Encoding.UTF8.GetByteCount(this.bootstrapPassword.Span) + 1];
        try
        {
            var length = Encoding.UTF8.GetBytes(this.bootstrapPassword.Span, passwordBytes);
            passwordBytes[length] = (byte)'\n';
            using (var input = command.CreateInputStream())
            {
                await input.WriteAsync(passwordBytes, timeout.Token).ConfigureAwait(false);
            }
            await execute.ConfigureAwait(false);
            return new(command.ExitStatus ?? -1, command.Result, command.Error, started.Elapsed);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(passwordBytes);
        }
    }

    private async Task EnrollPublicKeyAsync(string publicKey, CancellationToken cancellationToken)
    {
        if (!SshPublicKeyValidator.IsValid(publicKey))
            throw new InvalidDataException("SSH public key is invalid.");
        var sshDirectory = this.sftp.WorkingDirectory.TrimEnd('/') + "/.ssh";
        var authorizedKeys = sshDirectory + "/authorized_keys";
        if (!this.sftp.Exists(sshDirectory))
            await this.sftp.CreateDirectoryAsync(sshDirectory, cancellationToken)
            .ConfigureAwait(false);
        this.sftp.ChangePermissions(sshDirectory, SshDirectoryMode);
        var current = string.Empty;
        if (this.sftp.Exists(authorizedKeys))
        {
            using var destination = new MemoryStream();
            await this.sftp.DownloadFileAsync(authorizedKeys, destination, cancellationToken).ConfigureAwait(false);
            current = Encoding.UTF8.GetString(destination.ToArray());
        }
        if (current.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Contains(publicKey, StringComparer.Ordinal))
            return;
        var content = string.IsNullOrWhiteSpace(current)
            ? publicKey + "\n"
            : current.TrimEnd('\r', '\n') + "\n" + publicKey + "\n";
        var temporary = authorizedKeys + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await using var source = new MemoryStream(Encoding.UTF8.GetBytes(content), writable: false);
            await this.sftp.UploadFileAsync(source, temporary, cancellationToken).ConfigureAwait(false);
            this.sftp.ChangePermissions(temporary, AuthorizedKeysMode);
            this.sftp.RenameFile(temporary, authorizedKeys, true);
            this.sftp.ChangePermissions(authorizedKeys, AuthorizedKeysMode);
        }
        finally
        {
            if (this.sftp.Exists(temporary))
                this.sftp.DeleteFile(temporary);
        }
    }

    private static void Pin(BaseClient client, SshHostFingerprint expected)
    {
        client.HostKeyReceived += (_, eventArgs) =>
        {
            var observed = SshFingerprint.Create(eventArgs.HostKeyName, eventArgs.FingerPrintSHA256);
            eventArgs.CanTrust = SshFingerprint.FixedEquals(expected, observed);
        };
    }
}
