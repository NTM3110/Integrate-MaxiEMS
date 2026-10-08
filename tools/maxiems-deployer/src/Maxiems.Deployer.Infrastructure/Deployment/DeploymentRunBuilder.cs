using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Security;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal sealed class DeploymentRunBuilder(
    ReleaseId releaseId,
    SecretRedactor redactor,
    Action<DeploymentExecutionEvent>? progress,
    TimeProvider? timeProvider = null)
{
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private readonly Guid deploymentId = Guid.NewGuid();
    private readonly List<DeploymentPhaseResult> phases = [];
    private readonly List<RedactedLogEvent> logs = [];
    private readonly List<DeploymentCheckEvidence> checks = [];
    private readonly Dictionary<string, string> versions = new(StringComparer.Ordinal);
    private readonly DateTimeOffset startedAt = (timeProvider ?? TimeProvider.System).GetUtcNow();
    public DeploymentPhase? LastFailedPhase { get; private set; }

    public async Task<T> PhaseAsync<T>(DeploymentPhase phase, Func<Task<T>> action, string? message = null)
    {
        var started = this.clock.GetUtcNow();
        progress?.Invoke(new(phase, DeploymentPhaseOutcome.Running, started, message));
        try
        {
            var result = await action().ConfigureAwait(false);
            var completed = this.clock.GetUtcNow();
            this.phases.Add(new(phase, DeploymentPhaseOutcome.Passed, started, completed, completed - started, null, message));
            this.logs.Add(this.Log(phase, "phase.passed", $"{phase} passed."));
            progress?.Invoke(new(phase, DeploymentPhaseOutcome.Passed, completed, message));
            return result;
        }
        catch (Exception exception)
        {
            this.LastFailedPhase = phase;
            var completed = this.clock.GetUtcNow();
            var safe = redactor.RedactFreeText(exception.Message);
            this.phases.Add(new(phase, DeploymentPhaseOutcome.Failed, started, completed, completed - started,
                "phase-failed", safe));
            this.logs.Add(this.Log(phase, "phase.failed", safe, DeploymentLogLevel.Error));
            progress?.Invoke(new(phase, DeploymentPhaseOutcome.Failed, completed, safe));
            throw;
        }
    }

    public async Task PhaseAsync(DeploymentPhase phase, Func<Task> action, string? message = null) =>
        _ = await this.PhaseAsync(phase, async () =>
        {
            await action().ConfigureAwait(false);
            return true;
        }, message).ConfigureAwait(false);

    public void AddCheck(string code, bool passed, string summary) =>
        this.checks.Add(new(code, passed, redactor.RedactFreeText(summary)));

    public void AddVersion(string name, string value) => this.versions[name] = value;

    public void TailscaleUri(DeploymentPhase phase, Uri uri) =>
        progress?.Invoke(new(phase, DeploymentPhaseOutcome.Running, this.clock.GetUtcNow(),
            "Complete Tailscale authentication in the browser.", uri));

    public void Rollback(bool succeeded, string summary)
    {
        var now = this.clock.GetUtcNow();
        var outcome = succeeded ? DeploymentPhaseOutcome.RolledBack : DeploymentPhaseOutcome.Failed;
        this.phases.Add(new(DeploymentPhase.Rollback, outcome, now, now, TimeSpan.Zero,
            succeeded ? null : "rollback-failed", redactor.RedactFreeText(summary)));
        progress?.Invoke(new(DeploymentPhase.Rollback, outcome, now, redactor.RedactFreeText(summary)));
    }

    public void FailureDiagnostics(string message)
    {
        var now = this.clock.GetUtcNow();
        var safe = redactor.RedactFreeText(message);
        this.logs.Add(this.Log(
            DeploymentPhase.FailureDiagnostics,
            "failure.diagnostics",
            safe,
            DeploymentLogLevel.Warning));
        progress?.Invoke(new(
            DeploymentPhase.FailureDiagnostics,
            DeploymentPhaseOutcome.Running,
            now,
            safe));
    }

    public DeploymentRunRecord Finish(
        DeploymentFinalState state,
        string payloadSha256,
        RollbackAudit? rollback = null,
        ManufacturingReportContext? manufacturing = null)
    {
        var completed = this.clock.GetUtcNow();
        if (state == DeploymentFinalState.Succeeded)
        {
            this.phases.Add(new(DeploymentPhase.Complete, DeploymentPhaseOutcome.Passed,
                completed, completed, TimeSpan.Zero, null, "Deployment completed."));
            progress?.Invoke(new(DeploymentPhase.Complete, DeploymentPhaseOutcome.Passed, completed,
                "Deployment completed."));
        }
        return new(this.deploymentId, state, this.startedAt, completed, completed - this.startedAt,
            this.phases.ToImmutableArray(), this.logs.ToImmutableArray())
        {
            ReleaseId = releaseId,
            ArtifactHashes = ImmutableDictionary<string, string>.Empty.Add("payloadSha256", payloadSha256),
            Versions = this.versions.ToImmutableDictionary(StringComparer.Ordinal),
            Checks = this.checks.ToImmutableArray(),
            Rollback = rollback,
            Evidence = this.checks.ToImmutableArray(),
            Manufacturing = manufacturing,
            ReportFormatVersion = manufacturing is null ? 1 : 2,
        };
    }

    private RedactedLogEvent Log(
        DeploymentPhase phase,
        string code,
        string message,
        DeploymentLogLevel level = DeploymentLogLevel.Information) => new(
        this.clock.GetUtcNow(), level, code, redactor.RedactFreeText(message),
        [new("phase", phase.ToString(), false)]);
}
