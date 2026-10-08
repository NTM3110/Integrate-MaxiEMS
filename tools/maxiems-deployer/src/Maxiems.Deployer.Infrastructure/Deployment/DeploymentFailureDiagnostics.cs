namespace Maxiems.Deployer.Infrastructure.Deployment;

internal static class DeploymentFailureDiagnostics
{
    internal const int MaximumOutputCharacters = 32768;
    internal static readonly TimeSpan CollectionTimeout = TimeSpan.FromSeconds(15);

    public static async Task CollectAsync(
        DeploymentRemoteOperations? operations,
        DeploymentRunBuilder run)
    {
        ArgumentNullException.ThrowIfNull(run);
        if (operations is null)
        {
            return;
        }

        try
        {
            var output = await operations
                .RunAsync(
                    "diagnostics",
                    arguments: null,
                    timeoutOverride: CollectionTimeout,
                    cancellationToken: CancellationToken.None)
                .ConfigureAwait(false);
            if (output.Length > MaximumOutputCharacters)
            {
                output = output[..MaximumOutputCharacters];
            }
            run.FailureDiagnostics(string.IsNullOrWhiteSpace(output)
                ? "Remote failure diagnostics returned no output."
                : "Remote failure diagnostics:\n" + output);
        }
        catch (Exception exception)
        {
            // Diagnostics are best effort. A failure here must never delay or
            // replace the transaction rollback that follows.
            run.FailureDiagnostics("Remote failure diagnostics unavailable: " + exception.Message);
        }
    }
}
