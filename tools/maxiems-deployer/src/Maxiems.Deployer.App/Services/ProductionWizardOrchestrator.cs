using System.IO;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Threading.Channels;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Deployment;

namespace Maxiems.Deployer.App.Services;

public sealed class ProductionWizardOrchestrator(
    DeploymentEngine engine,
    IDeploymentPayloadProvider payloadProvider,
    string reportsDirectory) : IWizardDeploymentOrchestrator
{
    public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
        WizardDeploymentRequest request,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var channel = Channel.CreateUnbounded<WizardProgressUpdate>(new()
        {
            SingleReader = true,
            SingleWriter = true,
        });
        var worker = this.RunCoreAsync(request, channel.Writer, cancellationToken);
        await foreach (var update in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
        {
            yield return update;
        }
        await worker.ConfigureAwait(false);
    }

    private async Task RunCoreAsync(
        WizardDeploymentRequest request,
        ChannelWriter<WizardProgressUpdate> writer,
        CancellationToken cancellationToken)
    {
        string? payloadPath = null;
        char[]? bootstrapPassword = null;
        char[]? password = null;
        char[]? passphrase = null;
        try
        {
            payloadPath = await payloadProvider.MaterializeAsync(cancellationToken).ConfigureAwait(false);
            bootstrapPassword = Copy(request.BootstrapPassword);
            password = Copy(request.AdministratorPassword);
            passphrase = Copy(request.PrivateKeyPassphrase);
            var execution = new DeploymentExecutionRequest(request.Endpoint, request.Username, bootstrapPassword,
                request.PrivateKeyPath,
                passphrase, password, payloadPath, request.ManifestPath, request.EnableTailscale, reportsDirectory);
            var result = await engine.RunAsync(execution, update => writer.TryWrite(new(update.Phase, update.Outcome,
                update.Timestamp, update.OperatorMessage, update.TailscaleLoginUri?.AbsoluteUri)), cancellationToken)
                .ConfigureAwait(false);
            var releaseId = result.Record.ReleaseId ?? throw new InvalidDataException("Deployment returned no release ID.");
            var history = new DeploymentHistorySummary(result.Record.DeploymentId,
                $"{request.Endpoint.Host}:{request.Endpoint.Port}", releaseId, result.Record.FinalState,
                result.Record.StartedAt, result.Record.CompletedAt, result.Record.Duration,
                new(result.JsonReportPath, result.HtmlReportPath));
            writer.TryWrite(new(DeploymentPhase.Complete,
                result.Record.FinalState == DeploymentFinalState.Succeeded
                    ? DeploymentPhaseOutcome.Passed : DeploymentPhaseOutcome.Failed,
                result.Record.CompletedAt, result.Record.FinalState.ToString(), CompletedHistory: history));
            writer.TryComplete();
        }
        catch (Exception exception)
        {
            writer.TryComplete(exception);
        }
        finally
        {
            if (bootstrapPassword is not null)
            {
                CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(bootstrapPassword.AsSpan()));
            }
            if (password is not null)
                CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(password.AsSpan()));
            if (passphrase is not null)
                CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(passphrase.AsSpan()));
            if (payloadPath is not null && File.Exists(payloadPath))
                File.Delete(payloadPath);
        }
    }

    private static char[] Copy(SecureString value)
    {
        var result = new char[value.Length];
        var pointer = Marshal.SecureStringToGlobalAllocUnicode(value);
        try
        {
            Marshal.Copy(pointer, result, 0, result.Length);
            return result;
        }
        finally
        {
            Marshal.ZeroFreeGlobalAllocUnicode(pointer);
        }
    }
}
