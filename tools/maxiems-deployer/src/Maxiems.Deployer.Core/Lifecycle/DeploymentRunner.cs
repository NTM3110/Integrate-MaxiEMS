using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Security;

namespace Maxiems.Deployer.Core.Lifecycle;

public sealed class DeploymentRunner
{
    private static readonly ImmutableArray<DeploymentPhase> RequiredPhases =
    [
        DeploymentPhase.ValidateInput,
        DeploymentPhase.ConnectBootstrap,
        DeploymentPhase.Preflight,
        DeploymentPhase.ProvisionIdentity,
        DeploymentPhase.ReconnectRestricted,
        DeploymentPhase.InstallPrerequisites,
        DeploymentPhase.StageRelease,
        DeploymentPhase.VerifyRemotePayload,
        DeploymentPhase.ActivateRelease,
        DeploymentPhase.StartServices,
        DeploymentPhase.ConfigureTailscale,
        DeploymentPhase.ReconcileComponents,
        DeploymentPhase.HealthCheck,
    ];

    private readonly ImmutableArray<IDeploymentStep> steps;
    private readonly SecretRedactor redactor;
    private readonly TimeProvider timeProvider;

    public DeploymentRunner(
        IEnumerable<IDeploymentStep> steps,
        SecretRedactor redactor,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(steps);
        this.steps = steps.ToImmutableArray();
        if (!this.steps.Select(step => step.Phase).SequenceEqual(RequiredPhases))
        {
            throw new ArgumentException("Deployment steps must match the required phase order exactly.", nameof(steps));
        }
        this.redactor = redactor ?? throw new ArgumentNullException(nameof(redactor));
        this.timeProvider = timeProvider ?? TimeProvider.System;
    }

    public async Task<DeploymentRunRecord> RunAsync(CancellationToken cancellationToken = default)
    {
        var deploymentId = Guid.NewGuid();
        var startedAt = this.timeProvider.GetUtcNow();
        var results = ImmutableArray.CreateBuilder<DeploymentPhaseResult>();
        var logs = ImmutableArray.CreateBuilder<RedactedLogEvent>();
        var journal = new DeploymentJournal();
        var mutationStarted = false;

        foreach (var step in this.steps)
        {
            var phaseStartedAt = this.timeProvider.GetUtcNow();
            if (step.MayMutateTarget)
            {
                mutationStarted = true;
                journal.Register(step);
            }

            try
            {
                cancellationToken.ThrowIfCancellationRequested();
                await step.ExecuteAsync(cancellationToken).ConfigureAwait(false);
                var completedAt = this.timeProvider.GetUtcNow();
                results.Add(Passed(step.Phase, phaseStartedAt, completedAt));
                logs.Add(this.Log(step.Phase, "phase.passed", $"{step.Phase} passed."));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                var completedAt = this.timeProvider.GetUtcNow();
                results.Add(Failed(step.Phase, phaseStartedAt, completedAt, "cancelled", "Deployment cancelled."));
                return mutationStarted
                    ? await this.RollBackAsync(deploymentId, startedAt, results, logs, journal, "cancelled").ConfigureAwait(false)
                    : this.Finish(deploymentId, startedAt, DeploymentFinalState.Cancelled, results, logs);
            }
            catch (Exception exception)
            {
                var completedAt = this.timeProvider.GetUtcNow();
                var message = this.redactor.RedactFreeText(exception.Message);
                results.Add(Failed(step.Phase, phaseStartedAt, completedAt, "phase-failed", message));
                logs.Add(this.Log(step.Phase, "phase.failed", message, DeploymentLogLevel.Error));
                return mutationStarted
                    ? await this.RollBackAsync(deploymentId, startedAt, results, logs, journal, "phase-failed").ConfigureAwait(false)
                    : this.Finish(deploymentId, startedAt, DeploymentFinalState.Failed, results, logs);
            }
        }

        var completeAt = this.timeProvider.GetUtcNow();
        results.Add(Passed(DeploymentPhase.Complete, completeAt, completeAt));
        return this.Finish(deploymentId, startedAt, DeploymentFinalState.Succeeded, results, logs);
    }

    private async Task<DeploymentRunRecord> RollBackAsync(
        Guid deploymentId,
        DateTimeOffset startedAt,
        ImmutableArray<DeploymentPhaseResult>.Builder results,
        ImmutableArray<RedactedLogEvent>.Builder logs,
        DeploymentJournal journal,
        string reason)
    {
        var rollbackStartedAt = this.timeProvider.GetUtcNow();
        logs.Add(this.Log(DeploymentPhase.Rollback, "rollback.started", $"Rollback started after {reason}."));
        try
        {
            await journal.CompensateAsync(CancellationToken.None).ConfigureAwait(false);
            var completedAt = this.timeProvider.GetUtcNow();
            results.Add(new(
                DeploymentPhase.Rollback,
                DeploymentPhaseOutcome.RolledBack,
                rollbackStartedAt,
                completedAt,
                completedAt - rollbackStartedAt,
                null,
                "Rollback completed."));
            return this.Finish(deploymentId, startedAt, DeploymentFinalState.RolledBack, results, logs);
        }
        catch (Exception exception)
        {
            var completedAt = this.timeProvider.GetUtcNow();
            var message = this.redactor.RedactFreeText(exception.Message);
            results.Add(Failed(DeploymentPhase.Rollback, rollbackStartedAt, completedAt, "rollback-failed", message));
            return this.Finish(deploymentId, startedAt, DeploymentFinalState.Failed, results, logs);
        }
    }

    private DeploymentRunRecord Finish(
        Guid deploymentId,
        DateTimeOffset startedAt,
        DeploymentFinalState finalState,
        ImmutableArray<DeploymentPhaseResult>.Builder results,
        ImmutableArray<RedactedLogEvent>.Builder logs)
    {
        var completedAt = this.timeProvider.GetUtcNow();
        return new(
            deploymentId,
            finalState,
            startedAt,
            completedAt,
            completedAt - startedAt,
            results.ToImmutable(),
            logs.ToImmutable());
    }

    private RedactedLogEvent Log(
        DeploymentPhase phase,
        string code,
        string message,
        DeploymentLogLevel level = DeploymentLogLevel.Information) => new(
        this.timeProvider.GetUtcNow(),
        level,
        code,
        this.redactor.RedactFreeText(message),
        [new("phase", phase.ToString(), false)]);

    private static DeploymentPhaseResult Passed(
        DeploymentPhase phase,
        DateTimeOffset startedAt,
        DateTimeOffset completedAt) => new(
        phase, DeploymentPhaseOutcome.Passed, startedAt, completedAt, completedAt - startedAt, null, null);

    private static DeploymentPhaseResult Failed(
        DeploymentPhase phase,
        DateTimeOffset startedAt,
        DateTimeOffset completedAt,
        string errorCode,
        string message) => new(
        phase, DeploymentPhaseOutcome.Failed, startedAt, completedAt, completedAt - startedAt, errorCode, message);
}
