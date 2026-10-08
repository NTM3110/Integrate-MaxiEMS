using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Maxiems.Deployer.Infrastructure.Payload;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal sealed record DeploymentRecoveryResult(
    DeploymentFinalState State,
    RollbackAudit? Audit);

internal static class DeploymentFailureRecovery
{
    public static async Task<DeploymentRecoveryResult> RecoverAsync(
        ReleaseId releaseId,
        string? previousRelease,
        bool transactionActive,
        SshReleaseTransactionClient? transaction,
        OpenEmsManagedCheckpoint? componentCheckpoint,
        DeploymentApiSession? api,
        DeploymentRemoteOperations? operations,
        DeploymentRunBuilder run,
        Exception? transportFailure = null)
    {
        var attempted = transactionActive || componentCheckpoint is not null;
        if (!attempted)
            return new(DeploymentFinalState.Failed, null);
        var failures = new List<Exception>();
        if (transportFailure is not null)
            failures.Add(transportFailure);
        var componentsRestored = componentCheckpoint is null;
        if (componentCheckpoint is not null && api is null)
        {
            failures.Add(new IOException("Recovery API transport is unavailable."));
        }
        if (componentCheckpoint is not null && api is not null)
        {
            try
            {
                await new OpenEmsComponentReconciler(api.Client).RestoreAsync(componentCheckpoint, CancellationToken.None)
                    .ConfigureAwait(false);
                componentsRestored = true;
            }
            catch (Exception exception)
            {
                failures.Add(exception);
            }
        }

        var releaseRestored = !transactionActive;
        if (transactionActive && transaction is null)
        {
            failures.Add(new IOException("Recovery release transport is unavailable."));
        }
        if (transactionActive && transaction is not null)
        {
            try
            {
                await transaction.AbortAsync(releaseId, CancellationToken.None).ConfigureAwait(false);
                releaseRestored = true;
            }
            catch (Exception exception)
            {
                failures.Add(exception);
            }
        }

        if (componentsRestored && componentCheckpoint is not null && api is not null && operations is not null)
        {
            try
            {
                if (!transactionActive || !releaseRestored)
                {
                    await operations.RunAsync("service", ["restart"], CancellationToken.None).ConfigureAwait(false);
                }
                await new OpenEmsReadinessProbe(api.Client).WaitAsync(TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(2),
                    CancellationToken.None).ConfigureAwait(false);
                await new OpenEmsComponentReconciler(api.Client).VerifyAsync(componentCheckpoint, CancellationToken.None)
                    .ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                failures.Add(exception);
            }
        }

        var succeeded = releaseRestored && componentsRestored && failures.Count == 0;
        var summary = succeeded ? "Release and managed component state rolled back and verified."
            : "Rollback failed: " + string.Join(" | ", failures.Select(failure => failure.Message));
        run.Rollback(succeeded, summary);
        return new(succeeded ? DeploymentFinalState.RolledBack : DeploymentFinalState.Failed,
            new(true, releaseRestored && previousRelease is not null and not "absent", previousRelease, summary));
    }
}
