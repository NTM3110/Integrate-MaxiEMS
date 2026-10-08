using System.Runtime.Versioning;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Security;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.Cli;

internal static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (args is ["--help"] or ["-h"])
        {
            Console.WriteLine(CliOptions.Usage);
            return 0;
        }
        if (!OperatingSystem.IsLinux())
        {
            Console.Error.WriteLine("This CLI requires Linux.");
            return 2;
        }
        return await RunAsync(args).ConfigureAwait(false);
    }

    [SupportedOSPlatform("linux")]
    private static async Task<int> RunAsync(string[] args)
    {
        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancellation.Cancel(); };
        char[] bootstrap = [], passphrase = [], management = [];
        var redactor = new SecretRedactor();
        string? state = null;
        try
        {
            var options = CliOptions.Parse(args);
            redactor = new SecretRedactor([new("privateKeyPath", options.Identity)]);
            CliInputFiles.Validate(options);
            // Payload bootstrap assets define the privileged protocol used by this CLI.
            // Reject an old CLI-only/full-release mismatch before host probing or trust changes.
            BootstrapPayloadAssets.ValidateCompatibility(options.Payload);
            _ = PayloadComponentManifestResolver.Resolve(options.Payload, options.Manifest);
            if (Console.IsInputRedirected)
            {
                throw new ArgumentException("An interactive terminal is required for host trust and secret input.");
            }
            var xdg = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            var data = string.IsNullOrWhiteSpace(xdg) ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share") : xdg;
            if (!Path.IsPathFullyQualified(data))
            {
                throw new ArgumentException("XDG_DATA_HOME must be absolute.");
            }
            state = Path.Combine(data, "maxiems-deployer");
            SecureDirectory(state);
            var reports = Path.Combine(state, "reports");
            SecureDirectory(reports);
            var trustPath = Path.Combine(state, "known-hosts.json");
            RejectLink(trustPath);
            var trust = new HostTrustStore(trustPath);
            var coordinator = new HostTrustCoordinator(new SshNetHostKeyProbe(), trust);
            var decision = await coordinator.EvaluateAsync(options.Endpoint, options.Username, TimeSpan.FromSeconds(20),
                cancellationToken: cancellation.Token).ConfigureAwait(false);
            Console.WriteLine($"SSH {options.Endpoint.Host}:{options.Endpoint.Port} {decision.ObservedFingerprint.Algorithm} {decision.ObservedFingerprint.Sha256Value}");
            if (decision.Status == HostTrustStatus.Changed)
            {
                Console.Error.WriteLine("SSH host key changed. Verify the device identity and update the trust store separately before retrying.");
                return 2;
            }
            if (decision.Status == HostTrustStatus.FirstSeen)
            {
                Console.Write("Trust this host key? [y/N]: ");
                if (!HostTrustConfirmation.IsConfirmed(Console.ReadLine()))
                {
                    Console.Error.WriteLine("Host trust not confirmed.");
                    return 2;
                }
                await coordinator.ConfirmAsync(decision, cancellation.Token).ConfigureAwait(false);
            }
            File.SetUnixFileMode(trustPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            bootstrap = Prompt("Bootstrap SSH / sudo password (empty for key and passwordless sudo): ", cancellation.Token);
            passphrase = Prompt("SSH private-key passphrase (empty if unencrypted): ", cancellation.Token);
            management = Prompt("OpenEMS management password (required; separate from BMS admin/admin): ", cancellation.Token);
            OpenEmsManagementPassword.RequireNonEmpty(management, nameof(management));
            redactor = new SecretRedactor([new("privateKeyPath", options.Identity), new("password", new string(management)),
                new("bootstrapPassword", new string(bootstrap)), new("passphrase", new string(passphrase))]);
            var request = new DeploymentExecutionRequest(options.Endpoint, options.Username, bootstrap, options.Identity,
                passphrase, management, options.Payload, options.Manifest, false, reports)
            {
                RequireRebootAcceptance = options.RequireReboot,
                TargetProfile = options.TargetProfile,
                KeepFailedReleaseForInspection = options.KeepFailedReleaseForInspection
            };
            var result = await new DeploymentEngine(trust).RunAsync(request,
                progress => Console.WriteLine(redactor.RedactFreeText($"{progress.Timestamp:O} {progress.Phase}: {progress.Outcome} {progress.OperatorMessage}")),
                cancellation.Token).ConfigureAwait(false);
            Console.WriteLine($"{result.Record.FinalState}. Reports: {result.JsonReportPath} ; {result.HtmlReportPath}");
            return CliOptions.ExitCode(result.Record.FinalState);
        }
        catch (OperationCanceledException)
        {
            Console.Error.WriteLine("Deployment cancelled.");
            return 130;
        }
        catch (ArgumentException error)
        {
            Console.Error.WriteLine(redactor.RedactFreeText(error.Message));
            return 2;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(redactor.RedactFreeText(error.Message));
            return 1;
        }
        finally
        {
            Array.Clear(bootstrap);
            Array.Clear(passphrase);
            Array.Clear(management);
            if (state is not null && Directory.Exists(state))
            {
                foreach (var file in Directory.EnumerateFiles(state, "*", SearchOption.AllDirectories))
                {
                    if (new FileInfo(file).LinkTarget is null)
                    {
                        File.SetUnixFileMode(file, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                    }
                }
            }
        }
    }

    private static char[] Prompt(string prompt, CancellationToken token)
    {
        Console.Write(prompt);
        try
        {
            return SecretInput.Read(() => Console.ReadKey(intercept: true), token);
        }
        finally
        {
            Console.WriteLine();
        }
    }

    [SupportedOSPlatform("linux")]
    private static void SecureDirectory(string path)
    {
        RejectLink(path);
        Directory.CreateDirectory(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }

    private static void RejectLink(string path)
    {
        if (new FileInfo(path).LinkTarget is not null)
        {
            throw new ArgumentException("State paths must not be symbolic links.");
        }
    }
}

internal static class CliInputFiles
{
    public static void Validate(CliOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        RequireFile(options.Payload, "Payload ZIP");
        RequireFile(options.Identity, "SSH private key");
        RequireFile(options.Identity + ".pub", "SSH public key sidecar");
        if (options.Manifest is not null)
        {
            RequireFile(options.Manifest, "Optional component manifest");
        }
    }

    private static void RequireFile(string path, string inputLabel)
    {
        if (!File.Exists(path))
        {
            throw new ArgumentException($"{inputLabel} file is missing: {path}");
        }
    }
}

internal static class HostTrustConfirmation
{
    public static bool IsConfirmed(string? input)
    {
        var answer = input?.Trim();
        return string.Equals(answer, "y", StringComparison.OrdinalIgnoreCase)
            || string.Equals(answer, "yes", StringComparison.OrdinalIgnoreCase);
    }
}
