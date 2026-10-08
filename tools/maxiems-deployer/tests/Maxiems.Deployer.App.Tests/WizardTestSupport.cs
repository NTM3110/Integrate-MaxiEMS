using System.Security;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App.Tests;

internal static class WizardTestSupport
{
    internal static SecureString Secret(string value)
    {
        var result = new SecureString();
        foreach (var character in value)
            result.AppendChar(character);
        result.MakeReadOnly();
        return result;
    }

    internal static HostTrustDecision Trust(HostTrustStatus status) => new(
        new("edge.fixture.test", 22),
        status,
        new("ssh-ed25519", "SHA256:new-fingerprint"),
        status == HostTrustStatus.Changed ? new("ssh-ed25519", "SHA256:old-fingerprint") : null);

    internal static DeploymentHistorySummary History(
        string target = "edge.fixture.test:22",
        DeploymentFinalState state = DeploymentFinalState.Succeeded,
        DateTimeOffset? startedAt = null)
    {
        var started = startedAt ?? DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        return new(
            Guid.NewGuid(),
            target,
            new("20260719010000-0123456789ab"),
            state,
            started,
            started.AddMinutes(1),
            TimeSpan.FromMinutes(1),
            new("report.json", "report.html"));
    }
}

internal sealed class RecordingTrustEvaluator(HostTrustDecision decision) : IWizardTrustEvaluator
{
    public HostTrustDecision Decision { get; set; } = decision;
    public int ConfirmCount
    {
        get; private set;
    }

    public Task<HostTrustDecision> EvaluateAsync(
        HostEndpoint endpoint,
        string username,
        CancellationToken cancellationToken) => Task.FromResult(this.Decision with
        {
            Endpoint = endpoint
        });

    public Task ConfirmAsync(HostTrustDecision decision, CancellationToken cancellationToken)
    {
        this.ConfirmCount++;
        return Task.CompletedTask;
    }
}

internal sealed class MemoryWizardHistory : IWizardHistory
{
    public List<DeploymentHistorySummary> Entries { get; } = [];

    public Task<IReadOnlyList<DeploymentHistorySummary>> LoadAsync(CancellationToken cancellationToken) =>
        Task.FromResult<IReadOnlyList<DeploymentHistorySummary>>(this.Entries.ToArray());

    public Task AppendAsync(DeploymentHistorySummary summary, CancellationToken cancellationToken)
    {
        this.Entries.Add(summary);
        return Task.CompletedTask;
    }
}

internal sealed class RecordingReportLauncher : IWizardReportLauncher
{
    public List<string> OpenedPaths { get; } = [];

    public void Open(string path) => this.OpenedPaths.Add(path);
}

internal sealed class ManualTimeProvider(DateTimeOffset now) : TimeProvider
{
    private DateTimeOffset now = now;

    public override DateTimeOffset GetUtcNow() => this.now;

    public void Advance(TimeSpan duration) => this.now += duration;

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period) =>
        new NullTimer();

    private sealed class NullTimer : ITimer
    {
        public bool Change(TimeSpan dueTime, TimeSpan period) => true;
        public void Dispose()
        {
        }
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}
