using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.Ssh;
using Maxiems.Deployer.Infrastructure.Tailscale;

namespace Maxiems.Deployer.App.Services;

public static class DeploymentTestDriver
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };

    public static async Task<int> RunAsync(string inputPath, CancellationToken cancellationToken)
    {
        TestDriverInput? input = null;
        try
        {
            input = JsonSerializer.Deserialize<TestDriverInput>(
                await File.ReadAllTextAsync(inputPath, cancellationToken), JsonOptions)
                ?? throw new InvalidDataException("Test-driver input is empty.");
            var endpoint = new HostEndpoint(input.Host, input.Port);
            var trustStore = new HostTrustStore(input.TrustStorePath);
            var coordinator = new HostTrustCoordinator(new SshNetHostKeyProbe(), trustStore);
            var decision = await coordinator.EvaluateAsync(endpoint, input.BootstrapUsername, TimeSpan.FromSeconds(20),
                cancellationToken: cancellationToken).ConfigureAwait(false);
            if (decision.Status != HostTrustStatus.KnownMatch)
            {
                if (!input.ConfirmObservedHostKey)
                    throw new InvalidOperationException("Explicit host-key confirmation is required.");
                await coordinator.ConfirmAsync(decision, cancellationToken).ConfigureAwait(false);
            }
            var materializedPayload = string.IsNullOrWhiteSpace(input.PayloadPath);
            var payload = materializedPayload
                ? await new EmbeddedPayloadProvider().MaterializeAsync(cancellationToken).ConfigureAwait(false)
                : Path.GetFullPath(input.PayloadPath!);
            try
            {
                var password = input.AdministratorPassword.ToCharArray();
                var passphrase = input.PrivateKeyPassphrase.ToCharArray();
                try
                {
                    var tailscaleOptions = input.TailscaleTimeoutMilliseconds is > 0
                        ? new DeploymentEngineOptions(TimeSpan.FromMilliseconds(input.TailscaleTimeoutMilliseconds.Value),
                            TimeSpan.FromMilliseconds(Math.Min(50, input.TailscaleTimeoutMilliseconds.Value)))
                        : null;
                    var engine = new DeploymentEngine(trustStore,
                        input.SimulateTailscale
                            ? new SimulatedTailscaleFactory(input.SimulatedTailscaleOutcome ?? "success")
                            : null,
                        options: tailscaleOptions);
                    var events = new List<object>();
                    var request = new DeploymentExecutionRequest(endpoint, input.BootstrapUsername,
                        ReadOnlyMemory<char>.Empty, input.PrivateKeyPath,
                        passphrase, password, payload, input.ManifestPath, input.ConfigureTailscale, input.ReportsDirectory);
                    var result = await engine.RunAsync(request, update => events.Add(new
                    {
                        phase = update.Phase.ToString(),
                        outcome = update.Outcome.ToString(),
                        update.Timestamp,
                        update.OperatorMessage,
                        tailscaleLoginAvailable = update.TailscaleLoginUri is not null,
                    }), cancellationToken).ConfigureAwait(false);
                    await WriteResultAsync(input.ResultPath, new
                    {
                        schemaVersion = 1,
                        status = result.Record.FinalState.ToString(),
                        result.Record,
                        result.JsonReportPath,
                        result.HtmlReportPath,
                        events,
                    }, cancellationToken).ConfigureAwait(false);
                    return result.Record.FinalState == DeploymentFinalState.Succeeded ? 0 : 1;
                }
                finally
                {
                    CryptographicOperations.ZeroMemory(System.Runtime.InteropServices.MemoryMarshal.AsBytes(password.AsSpan()));
                    CryptographicOperations.ZeroMemory(System.Runtime.InteropServices.MemoryMarshal.AsBytes(passphrase.AsSpan()));
                }
            }
            finally
            {
                if (materializedPayload && File.Exists(payload))
                    File.Delete(payload);
            }
        }
        catch (Exception exception)
        {
            if (input?.ResultPath is { Length: > 0 } resultPath)
            {
                await WriteResultAsync(resultPath, new
                {
                    schemaVersion = 1,
                    status = "DriverFailed",
                    error = exception.Message
                },
                    CancellationToken.None).ConfigureAwait(false);
            }
            return 2;
        }
    }

    private static async Task WriteResultAsync(string path, object value, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(value, JsonOptions), cancellationToken)
            .ConfigureAwait(false);
        File.Move(temporary, path, true);
    }

    private sealed class SimulatedTailscaleFactory(string outcome) : ITailscaleNodeClientFactory
    {
        public ITailscaleNodeClient Create(IRemoteCommandTransport transport) => new SimulatedTailscaleClient(outcome);
    }

    private sealed class SimulatedTailscaleClient(string outcome) : ITailscaleNodeClient
    {
        private int statusCalls;

        public Task<string> StartLoginAsync(CancellationToken cancellationToken) =>
            Task.FromResult("Complete login at https://login.tailscale.com/a/fixture-token");

        public Task<string> GetStatusJsonAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (outcome.Equals("cancel", StringComparison.OrdinalIgnoreCase))
            {
                throw new OperationCanceledException("Simulated Tailscale cancellation.");
            }
            var authenticated = outcome.Equals("success", StringComparison.OrdinalIgnoreCase)
                && Interlocked.Increment(ref this.statusCalls) >= 2;
            return Task.FromResult(authenticated
                ? "{\"BackendState\":\"Running\",\"Self\":{\"Online\":true}}"
                : "{\"BackendState\":\"NeedsLogin\",\"Self\":{\"Online\":false}}");
        }
    }

    private sealed record TestDriverInput(
        string Host,
        int Port,
        string BootstrapUsername,
        string PrivateKeyPath,
        string PrivateKeyPassphrase,
        string AdministratorPassword,
        string? PayloadPath,
        string? ManifestPath,
        bool ConfigureTailscale,
        bool SimulateTailscale,
        string? SimulatedTailscaleOutcome,
        int? TailscaleTimeoutMilliseconds,
        bool ConfirmObservedHostKey,
        string TrustStorePath,
        string ReportsDirectory,
        string ResultPath);
}
