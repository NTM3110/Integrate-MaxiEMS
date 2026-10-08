using System.Runtime.CompilerServices;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
[TestCategory("Wizard")]
public sealed class MainWindowViewModelTests
{
    [TestMethod]
    public async Task ValidatesTargetAndRequiresExplicitChangedKeyConfirmation()
    {
        var trust = new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.Changed));
        using var viewModel = Create(new CompletedOrchestrator(), new(), trust);

        await viewModel.EvaluateTrustAsync();
        StringAssert.Contains(viewModel.ValidationMessage, "valid SSH host");
        viewModel.Host = "edge.fixture.test";
        await viewModel.EvaluateTrustAsync();

        Assert.AreEqual(WizardPage.Trust, viewModel.CurrentPage);
        StringAssert.Contains(viewModel.TrustWarning, "man-in-the-middle");
        StringAssert.Contains(viewModel.TrustDecision!.PreviousFingerprint!.Sha256Value, "old-fingerprint");
        await viewModel.ConfirmTrustAsync();
        Assert.AreEqual(0, trust.ConfirmCount);
        Assert.AreEqual(WizardPage.Trust, viewModel.CurrentPage);

        viewModel.ReplacementRiskAcknowledged = true;
        await viewModel.ConfirmTrustAsync();
        Assert.AreEqual(1, trust.ConfirmCount);
        Assert.AreEqual(WizardPage.Credentials, viewModel.CurrentPage);
    }

    [TestMethod]
    public async Task RejectReturnsToTargetWithoutMutatingTrust()
    {
        var trust = new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.FirstSeen));
        using var viewModel = Create(new CompletedOrchestrator(), new(), trust);
        viewModel.Host = "edge.fixture.test";

        await viewModel.EvaluateTrustAsync();
        viewModel.RejectTrust();

        Assert.AreEqual(WizardPage.Target, viewModel.CurrentPage);
        Assert.IsNull(viewModel.TrustDecision);
        Assert.AreEqual(0, trust.ConfirmCount);
    }

    [TestMethod]
    public async Task BusyStatePreventsDuplicateDeployAndCancelReturnsSafeResult()
    {
        var orchestrator = new CancellableOrchestrator();
        using var viewModel = Create(orchestrator, new(), new(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)));
        SetValidInputs(viewModel);
        var first = viewModel.StartDeploymentAsync(
            WizardTestSupport.Secret("admin-secret"), WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("key-secret"));
        await orchestrator.Started.Task;
        Assert.IsTrue(viewModel.IsBusy);
        Assert.AreEqual(WizardPage.Progress, viewModel.CurrentPage);

        await viewModel.StartDeploymentAsync(WizardTestSupport.Secret("unused"), WizardTestSupport.Secret("unused"),
            WizardTestSupport.Secret("unused"));
        Assert.AreEqual(1, orchestrator.RunCount);
        viewModel.CancelCommand.Execute(null);
        await first;

        Assert.IsFalse(viewModel.IsBusy);
        Assert.AreEqual("Cancelled", viewModel.CurrentPhase);
        Assert.AreEqual(WizardPage.Result, viewModel.CurrentPage);
        Assert.IsFalse(viewModel.CancelCommand.CanExecute(null));
    }

    [TestMethod]
    public async Task RendersPhaseTimingLiveElapsedTailscaleAndAtomicHistoryCompletion()
    {
        var started = DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var clock = new ManualTimeProvider(started);
        var history = new MemoryWizardHistory();
        var completed = WizardTestSupport.History(startedAt: started);
        var orchestrator = new TimelineOrchestrator(clock, completed);
        using var viewModel = Create(
            orchestrator, history, new(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)), clock);
        SetValidInputs(viewModel);

        await viewModel.StartDeploymentAsync(WizardTestSupport.Secret("admin"), WizardTestSupport.Secret("bootstrap"),
            WizardTestSupport.Secret("key"));
        viewModel.RefreshElapsed();

        var row = viewModel.Timeline.Single(item => item.Phase == DeploymentPhase.ConfigureTailscale);
        Assert.AreEqual(DeploymentPhaseOutcome.Passed, row.Outcome);
        Assert.AreEqual(started, row.StartedAt);
        Assert.AreEqual(started.AddSeconds(5), row.CompletedAt);
        Assert.AreEqual(TimeSpan.FromSeconds(5), row.Duration);
        Assert.AreEqual(TimeSpan.FromSeconds(5), viewModel.Elapsed);
        Assert.AreEqual("Complete", viewModel.CurrentPhase);
        Assert.AreEqual(DeploymentFinalState.Succeeded, viewModel.FinalState);
        Assert.AreEqual("Deployment verified", viewModel.ResultHeading);
        Assert.IsNull(viewModel.TailscaleLoginUrl, "Login tokens must leave visual state when the run ends.");
        Assert.AreEqual(completed.DeploymentId, history.Entries.Single().DeploymentId);
        Assert.AreEqual(completed.DeploymentId, viewModel.History.Single().DeploymentId);
    }

    [TestMethod]
    public async Task HistoryIsSortedAndSearchableByTargetAndStatus()
    {
        var history = new MemoryWizardHistory();
        var older = WizardTestSupport.History("old-edge:22", DeploymentFinalState.Failed,
            DateTimeOffset.Parse("2026-07-18T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
        var newer = WizardTestSupport.History("new-edge:22", DeploymentFinalState.Succeeded,
            DateTimeOffset.Parse("2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
        history.Entries.AddRange([older, newer]);
        using var viewModel = Create(new CompletedOrchestrator(), history,
            new(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)));

        await viewModel.LoadHistoryAsync();
        Assert.AreEqual(newer.DeploymentId, viewModel.FilteredHistory[0].DeploymentId);
        viewModel.HistorySearch = "failed";
        Assert.AreEqual(older.DeploymentId, viewModel.FilteredHistory.Single().DeploymentId);
        viewModel.HistorySearch = "new-edge";
        Assert.AreEqual(newer.Reports.HtmlReportPath, viewModel.FilteredHistory.Single().Reports.HtmlReportPath);
        viewModel.HistorySearch = string.Empty;
        viewModel.HistorySort = HistorySort.Target;
        Assert.AreEqual("new-edge:22", viewModel.FilteredHistory[0].TargetDisplayName);
        viewModel.HistorySort = HistorySort.Status;
        Assert.AreEqual(DeploymentFinalState.Succeeded, viewModel.FilteredHistory[0].FinalState);
        var launcher = new RecordingReportLauncher();
        using var detailViewModel = new MainWindowViewModel(new CompletedOrchestrator(), history,
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)), launcher,
            synchronizationContext: new());
        detailViewModel.SelectedHistory = newer;
        detailViewModel.OpenHistoryReportCommand.Execute(null);
        Assert.AreEqual(newer.Reports.HtmlReportPath, launcher.OpenedPaths.Single());
    }

    [TestMethod]
    public async Task MissingSshKeyPairStopsBeforeDeployment()
    {
        var orchestrator = new CompletedOrchestrator();
        using var viewModel = Create(orchestrator, new(),
            new(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)), fileExists: _ => false);
        SetValidInputs(viewModel);
        viewModel.CurrentPage = WizardPage.Credentials;

        viewModel.NextCommand.Execute(null);
        Assert.AreEqual(WizardPage.Credentials, viewModel.CurrentPage);
        StringAssert.Contains(viewModel.ValidationMessage, "matching .pub");
        StringAssert.Contains(viewModel.ValidationMessage, "turn off technician mode");

        await viewModel.StartDeploymentAsync(
            WizardTestSupport.Secret("administrator-secret"), WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("key-secret"));
        Assert.AreEqual(0, orchestrator.RunCount);
        Assert.AreEqual(WizardPage.Credentials, viewModel.CurrentPage);
    }

    [TestMethod]
    public async Task FailedTerminalResultCannotAppearVerified()
    {
        var started = DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var failed = WizardTestSupport.History(
            state: DeploymentFinalState.Failed, startedAt: started);
        using var viewModel = Create(new FailedOrchestrator(failed), new(),
            new(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)));
        SetValidInputs(viewModel);

        await viewModel.StartDeploymentAsync(
            WizardTestSupport.Secret("administrator-secret"), WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("key-secret"));

        Assert.AreEqual(WizardPage.Result, viewModel.CurrentPage);
        Assert.AreEqual(DeploymentFinalState.Failed, viewModel.FinalState);
        Assert.AreEqual("Deployment failed", viewModel.ResultHeading);
        Assert.AreEqual("×", viewModel.ResultGlyph);
        StringAssert.Contains(viewModel.ResultDetails, "private/public key pair was not found");
        Assert.AreNotEqual("Deployment verified", viewModel.ResultHeading);
    }

    [TestMethod]
    public async Task WorkerModeUsesManagedIdentityBuiltInProfileAndOneTimePassword()
    {
        var orchestrator = new CapturingOrchestrator();
        var identity = new FixedIdentityProvider();
        var profile = new FixedProfileProvider();
        using var viewModel = new MainWindowViewModel(orchestrator, new MemoryWizardHistory(),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            new RecordingReportLauncher(), synchronizationContext: new(), fileExists: _ => false,
            identityProvider: identity, profileProvider: profile);
        viewModel.Host = "edge.fixture.test";
        viewModel.Username = "worker";
        viewModel.EnableModbusProfile = true;

        await viewModel.StartDeploymentAsync(WizardTestSupport.Secret("administrator-secret"),
            WizardTestSupport.Secret("temporary-device-password"), WizardTestSupport.Secret("ignored"));

        Assert.AreEqual(1, identity.CallCount);
        Assert.AreEqual(1, profile.CallCount);
        Assert.AreEqual("C:\\managed\\worker-key.pem", orchestrator.PrivateKeyPath);
        Assert.AreEqual("C:\\managed\\modbus.json", orchestrator.ManifestPath);
        Assert.AreEqual("worker", orchestrator.Username);
        Assert.AreEqual("temporary-device-password".Length, orchestrator.BootstrapPasswordLength);
        Assert.AreEqual("managed-passphrase".Length, orchestrator.PrivateKeyPassphraseLength);
        Assert.AreEqual("Automatic worker pairing", viewModel.AccessModeSummary);
        Assert.AreEqual("MAXiEMS + Modbus TCP", viewModel.ComponentProfileSummary);
    }

    private static MainWindowViewModel Create(
        IWizardDeploymentOrchestrator orchestrator,
        MemoryWizardHistory history,
        RecordingTrustEvaluator trust,
        TimeProvider? timeProvider = null,
        Func<string, bool>? fileExists = null) => new(
            orchestrator, history, trust, new RecordingReportLauncher(), timeProvider, new(), fileExists ?? (_ => true));

    private static void SetValidInputs(MainWindowViewModel viewModel)
    {
        viewModel.Host = "edge.fixture.test";
        viewModel.Username = "admin";
        viewModel.UseAdvancedAccess = true;
        viewModel.PrivateKeyPath = "C:\\fixture\\id_ed25519";
    }

    private sealed class CompletedOrchestrator : IWizardDeploymentOrchestrator
    {
        public int RunCount
        {
            get; private set;
        }

        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            this.RunCount++;
            await Task.Yield();
            yield break;
        }
    }

    private sealed class FailedOrchestrator(DeploymentHistorySummary history) : IWizardDeploymentOrchestrator
    {
        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            var now = DateTimeOffset.UtcNow;
            yield return new(DeploymentPhase.ValidateInput, DeploymentPhaseOutcome.Running, now);
            yield return new(DeploymentPhase.ValidateInput, DeploymentPhaseOutcome.Failed, now,
                "SSH private/public key pair was not found.");
            yield return new(DeploymentPhase.Complete, DeploymentPhaseOutcome.Failed, now,
                "Failed", CompletedHistory: history);
            await Task.CompletedTask;
        }
    }

    private sealed class CancellableOrchestrator : IWizardDeploymentOrchestrator
    {
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public int RunCount
        {
            get; private set;
        }

        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            this.RunCount++;
            this.Started.TrySetResult();
            yield return new(DeploymentPhase.Preflight, DeploymentPhaseOutcome.Running, DateTimeOffset.UtcNow);
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        }
    }

    private sealed class TimelineOrchestrator(
        ManualTimeProvider clock,
        DeploymentHistorySummary history) : IWizardDeploymentOrchestrator
    {
        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            yield return new(DeploymentPhase.ConfigureTailscale, DeploymentPhaseOutcome.Running,
                clock.GetUtcNow(), TailscaleLoginUrl: "https://login.tailscale.com/fixture-token");
            clock.Advance(TimeSpan.FromSeconds(5));
            yield return new(DeploymentPhase.ConfigureTailscale, DeploymentPhaseOutcome.Passed, clock.GetUtcNow());
            yield return new(DeploymentPhase.Complete, DeploymentPhaseOutcome.Passed, clock.GetUtcNow(),
                CompletedHistory: history);
            await Task.CompletedTask;
        }
    }

    private sealed class CapturingOrchestrator : IWizardDeploymentOrchestrator
    {
        public string? Username
        {
            get; private set;
        }
        public string? PrivateKeyPath
        {
            get; private set;
        }
        public string? ManifestPath
        {
            get; private set;
        }
        public int BootstrapPasswordLength
        {
            get; private set;
        }
        public int PrivateKeyPassphraseLength
        {
            get; private set;
        }

        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            this.Username = request.Username;
            this.PrivateKeyPath = request.PrivateKeyPath;
            this.ManifestPath = request.ManifestPath;
            this.BootstrapPasswordLength = request.BootstrapPassword.Length;
            this.PrivateKeyPassphraseLength = request.PrivateKeyPassphrase.Length;
            await Task.Yield();
            yield break;
        }
    }

    private sealed class FixedIdentityProvider : IWizardSshIdentityProvider
    {
        public int CallCount
        {
            get; private set;
        }

        public WizardSshIdentity GetOrCreate()
        {
            this.CallCount++;
            return new("C:\\managed\\worker-key.pem", WizardTestSupport.Secret("managed-passphrase"));
        }
    }

    private sealed class FixedProfileProvider : IWizardComponentProfileProvider
    {
        public int CallCount
        {
            get; private set;
        }

        public string? ResolveManifest(bool enableModbusProfile)
        {
            this.CallCount++;
            return enableModbusProfile ? "C:\\managed\\modbus.json" : null;
        }
    }
}
