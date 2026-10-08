using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

internal sealed class OpenSshFixture : IAsyncDisposable
{
    private const string KeyPassphrase = "fixture-passphrase";

    private readonly string openSshDirectory;
    private readonly string root;
    private readonly StringBuilder serverLog = new();
    private Process? server;

    private OpenSshFixture(string openSshDirectory, string root, int port)
    {
        this.openSshDirectory = openSshDirectory;
        this.root = root;
        this.Port = port;
    }

    public int Port
    {
        get;
    }

    public string EncryptedEd25519Key => Path.Combine(this.root, "client-ed25519");

    public string EncryptedRsaKey => Path.Combine(this.root, "client-rsa");

    public string RemoteUploadDirectory => "/" + UnixPath(Path.Combine(this.root, "incoming"));

    public string Log => this.serverLog.ToString();

    public static async Task<OpenSshFixture> StartAsync(string openSshDirectory)
    {
        var root = Path.Combine(Path.GetTempPath(), $"maxiems-sshd-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        Directory.CreateDirectory(Path.Combine(root, "incoming"));
        var fixture = new OpenSshFixture(openSshDirectory, root, GetFreePort());
        fixture.GenerateKey("ed25519", fixture.EncryptedEd25519Key, KeyPassphrase);
        fixture.GenerateKey("rsa", fixture.EncryptedRsaKey, KeyPassphrase, "3072");
        await File.WriteAllTextAsync(
            Path.Combine(root, "authorized_keys"),
            await File.ReadAllTextAsync(fixture.EncryptedEd25519Key + ".pub")
                + await File.ReadAllTextAsync(fixture.EncryptedRsaKey + ".pub"));
        fixture.GenerateKey("ed25519", Path.Combine(root, "host-key"), string.Empty);
        await fixture.StartServerAsync();
        return fixture;
    }

    public async Task RotateHostKeyAsync(string algorithm)
    {
        await this.StopServerAsync();
        var hostKey = Path.Combine(this.root, "host-key");
        File.Delete(hostKey);
        File.Delete(hostKey + ".pub");
        this.GenerateKey(algorithm, hostKey, string.Empty, algorithm == "rsa" ? "3072" : null);
        await this.StartServerAsync();
    }

    public async ValueTask DisposeAsync()
    {
        await this.StopServerAsync();
        Directory.Delete(this.root, true);
    }

    private async Task StartServerAsync()
    {
        var configPath = Path.Combine(this.root, "sshd_config");
        var sftpServer = Path.Combine(this.openSshDirectory, "sftp-server.exe");
        var config = $"""
			Port {this.Port}
			ListenAddress 127.0.0.1
			HostKey {UnixPath(Path.Combine(this.root, "host-key"))}
			AuthorizedKeysFile {UnixPath(Path.Combine(this.root, "authorized_keys"))}
			PidFile {UnixPath(Path.Combine(this.root, "sshd.pid"))}
			PubkeyAuthentication yes
			PasswordAuthentication no
			KbdInteractiveAuthentication no
			StrictModes no
			LogLevel VERBOSE
			AllowUsers {Environment.UserName}
			Subsystem sftp {UnixPath(sftpServer)}
			""";
        await File.WriteAllTextAsync(configPath, config);
        var startInfo = new ProcessStartInfo
        {
            FileName = Path.Combine(this.openSshDirectory, "sshd.exe"),
            WorkingDirectory = this.openSshDirectory,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
        };
        startInfo.ArgumentList.Add("-D");
        startInfo.ArgumentList.Add("-e");
        startInfo.ArgumentList.Add("-f");
        startInfo.ArgumentList.Add(configPath);
        this.server = Process.Start(startInfo) ?? throw new InvalidOperationException("Failed to start OpenSSH fixture.");
        this.server.ErrorDataReceived += (_, eventArgs) =>
        {
            if (eventArgs.Data is not null)
            {
                this.serverLog.AppendLine(eventArgs.Data);
            }
        };
        this.server.BeginErrorReadLine();
        await WaitForPortAsync(this.Port, this.server);
    }

    private async Task StopServerAsync()
    {
        if (this.server is null)
        {
            return;
        }
        if (!this.server.HasExited)
        {
            this.server.Kill(true);
            await this.server.WaitForExitAsync();
        }
        this.server.Dispose();
        this.server = null;
    }

    private void GenerateKey(string algorithm, string path, string passphrase, string? bits = null)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = Path.Combine(this.openSshDirectory, "ssh-keygen.exe"),
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
        };
        foreach (var argument in new[] { "-q", "-t", algorithm, "-N", passphrase, "-f", path })
        {
            startInfo.ArgumentList.Add(argument);
        }
        if (bits is not null)
        {
            startInfo.ArgumentList.Add("-b");
            startInfo.ArgumentList.Add(bits);
        }
        using var process = Process.Start(startInfo)!;
        process.WaitForExit();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException(process.StandardError.ReadToEnd());
        }
    }

    private static int GetFreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static async Task WaitForPortAsync(int port, Process server)
    {
        for (var attempt = 0; attempt < 100; attempt++)
        {
            if (server.HasExited)
            {
                throw new InvalidOperationException("OpenSSH fixture exited before listening.");
            }
            try
            {
                using var client = new TcpClient();
                await client.ConnectAsync(IPAddress.Loopback, port);
                return;
            }
            catch (SocketException)
            {
                await Task.Delay(50);
            }
        }
        throw new TimeoutException("OpenSSH fixture did not start listening.");
    }

    private static string UnixPath(string path) => path.Replace('\\', '/');
}
