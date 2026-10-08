using System.Collections.Immutable;
using System.Security;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.App.ViewModels.Operations;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
[TestCategory("Manufacturing")]
public sealed class ManufacturingDeploymentViewModelTests
{
    [TestMethod]
    public async Task GuidedFlowFreezesReviewDeploysAndClearsCredentials()
    {
        var fixture = Fixture.Create();
        var backend = new RecordingBackend(fixture);
        var trust = new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch));
        using var viewModel = new ManufacturingDeploymentViewModel(backend, trust, () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "edge.fixture.test";

        await viewModel.EvaluateTrustAsync();
        Assert.IsTrue(viewModel.TrustConfirmed);
        using (var bootstrap = WizardTestSupport.Secret("bootstrap-secret"))
        using (var administrator = WizardTestSupport.Secret("administrator-secret"))
        {
            await viewModel.InspectAsync(bootstrap, administrator);
        }
        Assert.AreEqual(ManufacturingWorkflowStep.LanMapping, viewModel.Step);
        Assert.AreEqual("enp2s0", viewModel.SerialSourceLinuxName);
        Assert.AreEqual("enp1s0", viewModel.SecondaryLinuxName);
        StringAssert.Contains(viewModel.SerialPreview, "MR00613A7D755A");
        await viewModel.ConfirmMappingAsync();
        Assert.AreEqual("MR00613A7D755A", viewModel.DerivedSerial);
        Assert.IsTrue(viewModel.CanDeploy);

        await viewModel.DeployAsync();

        Assert.AreEqual(1, backend.DeployCount);
        Assert.AreEqual(ManufacturingWorkflowStep.Result, viewModel.Step);
        Assert.AreEqual(DeploymentFinalState.Succeeded, viewModel.Result!.FinalState);
        Assert.IsFalse(viewModel.HasCredentials);
        Assert.IsFalse(viewModel.CanDeploy);
    }

    [TestMethod]
    public async Task FirstSeenTrustAndShortAdministratorPasswordCanProceed()
    {
        var fixture = Fixture.Create();
        var trust = new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.FirstSeen));
        using var viewModel = new ManufacturingDeploymentViewModel(new RecordingBackend(fixture), trust,
            () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "edge.fixture.test";
        await viewModel.EvaluateTrustAsync();
        await viewModel.ConfirmTrustAsync();
        Assert.IsFalse(viewModel.TrustConfirmed);
        viewModel.TrustRiskAcknowledged = true;
        await viewModel.ConfirmTrustAsync();
        Assert.IsTrue(viewModel.TrustConfirmed);
        await viewModel.InspectAsync(WizardTestSupport.Secret("bootstrap"), WizardTestSupport.Secret("short"));
        Assert.AreEqual(ManufacturingWorkflowStep.LanMapping, viewModel.Step);
        Assert.IsTrue(viewModel.HasCredentials);
    }

    [TestMethod]
    public async Task EmptyAdministratorPasswordRemainsBlocked()
    {
        var fixture = Fixture.Create();
        using var viewModel = new ManufacturingDeploymentViewModel(new RecordingBackend(fixture),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)), () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "edge.fixture.test";
        await viewModel.EvaluateTrustAsync();

        await viewModel.InspectAsync(WizardTestSupport.Secret("bootstrap"), WizardTestSupport.Secret(""));

        StringAssert.Contains(viewModel.Status, "required");
        Assert.IsFalse(viewModel.HasCredentials);
    }

    [TestMethod]
    public void PublicViewModelSurfaceContainsNoPlaintextCredentialProperties()
    {
        Assert.IsFalse(typeof(ManufacturingDeploymentViewModel).GetProperties().Any(property =>
            property.PropertyType == typeof(string) && (property.Name.Contains("password", StringComparison.OrdinalIgnoreCase)
                || property.Name.Contains("passphrase", StringComparison.OrdinalIgnoreCase))));
    }

    [TestMethod]
    public async Task SshTrustTimeoutIsReportedWithoutEscapingTheUiBoundary()
    {
        var fixture = Fixture.Create();
        using var viewModel = new ManufacturingDeploymentViewModel(new RecordingBackend(fixture),
            new TimeoutTrustEvaluator(), () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "192.168.127.19";

        await viewModel.EvaluateTrustAsync();

        StringAssert.Contains(viewModel.Status, "timed out");
        Assert.IsFalse(viewModel.IsBusy);
        Assert.IsFalse(viewModel.TrustConfirmed);
        Assert.IsNull(viewModel.TrustDecision);
        Assert.AreEqual(ManufacturingWorkflowStep.Target, viewModel.Step);
    }

    [TestMethod]
    public async Task ReadOnlyInspectionTimeoutIsContainedAndLeavesTheDeviceUnchanged()
    {
        var fixture = Fixture.Create();
        using var viewModel = new ManufacturingDeploymentViewModel(new HangingInspectionBackend(),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            () => [fixture.Profile], inspectionTimeout: TimeSpan.FromMilliseconds(20));
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "edge.fixture.test";
        await viewModel.EvaluateTrustAsync();

        await viewModel.InspectAsync(WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("administrator-secret"));

        StringAssert.Contains(viewModel.Status, "timed out");
        Assert.IsFalse(viewModel.IsBusy);
        Assert.IsFalse(viewModel.HasInspection);
        Assert.IsFalse(viewModel.HasCredentials);
        Assert.IsFalse(viewModel.CanCancelCurrentOperation);
        Assert.AreEqual(ManufacturingWorkflowStep.Inspect, viewModel.Step);
    }

    [TestMethod]
    public async Task EngineerCanCancelAReadOnlyInspectionSafely()
    {
        var fixture = Fixture.Create();
        using var viewModel = new ManufacturingDeploymentViewModel(new HangingInspectionBackend(),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "edge.fixture.test";
        await viewModel.EvaluateTrustAsync();

        var inspection = viewModel.InspectAsync(WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("administrator-secret"));
        Assert.IsTrue(viewModel.CanCancelCurrentOperation);
        viewModel.CancelCurrentOperation();
        await inspection;

        StringAssert.Contains(viewModel.Status, "cancelled safely");
        Assert.IsFalse(viewModel.IsBusy);
        Assert.IsFalse(viewModel.HasCredentials);
    }

    [TestMethod]
    public async Task ActualWanConventionMapsEth1ToSerialSourceAndEth0ToSecondaryPort()
    {
        var fixture = Fixture.Create();
        var evidence = new HardwareEvidence("fixture", EvidenceAvailability.Observed, "permanent MAC");
        var ports = ImmutableArray.Create(
            new EthernetPortInventory("eth0", null, new("D2:C6:39:88:87:61"),
                new("D2:C6:39:88:87:61"), true, 1000, evidence),
            new EthernetPortInventory("eth1", null, new("00:E0:9A:44:46:42"),
                new("00:E0:9A:44:46:42"), true, 1000, evidence));
        var hardware = fixture.Draft.ReadOnlyInspection.Hardware with
        {
            EthernetPorts = ports
        };
        var readOnly = fixture.Draft.ReadOnlyInspection with
        {
            Hardware = hardware
        };
        fixture = fixture with
        {
            Draft = fixture.Draft with
            {
                ReadOnlyInspection = readOnly
            }
        };
        using var viewModel = new ManufacturingDeploymentViewModel(new RecordingBackend(fixture),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            () => [fixture.Profile]);
        viewModel.NotifyProfilesChanged();
        viewModel.Host = "100.102.60.39";
        await viewModel.EvaluateTrustAsync();

        await viewModel.InspectAsync(WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret("administrator-secret"));

        Assert.AreEqual("eth1", viewModel.SerialSourceLinuxName);
        Assert.AreEqual("eth0", viewModel.SecondaryLinuxName);
        Assert.AreEqual("eth1 — 00:E0:9A:44:46:42", viewModel.SerialSourceSummary);
        StringAssert.Contains(viewModel.SerialPreview, "MR00E09A444642");
        Assert.IsTrue(viewModel.CanConfirmMapping);
    }

    private sealed class TimeoutTrustEvaluator : IWizardTrustEvaluator
    {
        public Task<HostTrustDecision> EvaluateAsync(HostEndpoint endpoint, string username,
            CancellationToken cancellationToken) => Task.FromException<HostTrustDecision>(new TimeoutException());
        public Task ConfirmAsync(HostTrustDecision decision, CancellationToken cancellationToken) =>
            Task.FromException(new TimeoutException());
    }

    private sealed class HangingInspectionBackend : IManufacturingWorkflowBackend
    {
        public async Task<ManufacturingInspectionDraft> InspectAsync(ManufacturingInspectRequest request,
            CancellationToken cancellationToken = default)
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("Unreachable.");
        }

        public Task<ManufacturingReviewBundle> ReviewAsync(ManufacturingInspectionDraft draft,
            EthernetMappingConfirmation mapping, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<ManufacturingDeployResult> DeployAsync(ManufacturingReviewBundle review,
            SecureString bootstrapPassword, SecureString administratorPassword,
            Action<WizardProgressUpdate>? progress = null, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
    }

    private sealed class RecordingBackend(Fixture fixture) : IManufacturingWorkflowBackend
    {
        public int DeployCount
        {
            get; private set;
        }
        public Task<ManufacturingInspectionDraft> InspectAsync(ManufacturingInspectRequest request,
            CancellationToken cancellationToken = default) => Task.FromResult(fixture.Draft);
        public Task<ManufacturingReviewBundle> ReviewAsync(ManufacturingInspectionDraft draft,
            EthernetMappingConfirmation mapping, CancellationToken cancellationToken = default) =>
            Task.FromResult(fixture.Review);
        public Task<ManufacturingDeployResult> DeployAsync(ManufacturingReviewBundle review,
            SecureString bootstrapPassword, SecureString administratorPassword,
            Action<WizardProgressUpdate>? progress = null, CancellationToken cancellationToken = default)
        {
            this.DeployCount++;
            progress?.Invoke(new(DeploymentPhase.Complete, DeploymentPhaseOutcome.Passed,
                DateTimeOffset.Parse("2026-07-22T09:02:00Z", System.Globalization.CultureInfo.InvariantCulture)));
            return Task.FromResult(new ManufacturingDeployResult(DeploymentFinalState.Succeeded,
                Guid.NewGuid(), new("report.json", "report.html"), "Deploy completed."));
        }
    }

    private sealed record Fixture(ManufacturingProfile Profile, ManufacturingInspectionDraft Draft,
        ManufacturingReviewBundle Review)
    {
        public static Fixture Create()
        {
            var at = DateTimeOffset.Parse("2026-07-22T09:00:00Z",
                System.Globalization.CultureInfo.InvariantCulture);
            var evidence = new HardwareEvidence("fixture", EvidenceAvailability.Observed, "stable fixture evidence");
            var ports = ImmutableArray.Create(
                new EthernetPortInventory("enp1s0", null, new("00:11:22:33:44:55"),
                    new("00:11:22:33:44:55"), true, 1000, evidence),
                new EthernetPortInventory("enp2s0", null, new("00:61:3A:7D:75:5A"),
                    new("00:61:3A:7D:75:5A"), true, 1000, evidence));
            var hardware = new HardwareInventorySnapshot(at, new("arm64", "MAXiEMS CPU", 4, evidence),
                new(4L * 1024 * 1024 * 1024, evidence),
                [new("mmcblk0", StorageKind.Emmc, "eMMC", null, 32L * 1024 * 1024 * 1024, true, evidence)],
                new("ubuntu", "24.04", "arm64", "6.8.0", null, "MAXiEMS", "A1", evidence), ports, []);
            var release = new LocalReleaseDescriptor(new("20260722090000-123456789abc"), "phase1",
                LocalReleaseSource.Embedded, LocalReleaseStatus.Embedded, new string('a', 64), new string('b', 64),
                ["24.04"], [SupportedArchitecture.Arm64], "1.0.0");
            var profile = new ManufacturingProfile(new("edge", 1, new string('c', 64)),
                ManufacturingProfileState.Approved, "MAXiEMS Edge", "MAXiEMS", "A1",
                new(["arm64"], ["MAXiEMS CPU"], 4), new(3L << 30, 5L << 30),
                [new(StorageKind.Emmc, new(30L << 30, 40L << 30), [])],
                new("ubuntu", ["24.04"], ["arm64"], [], false, null),
                [new("LAN1", false), new("LAN2", true)], [], release.ReleaseId, null, false, false, "fixture");
            var platform = new UbuntuPreflightSnapshot("ubuntu", "24.04", "arm64", 10L << 30, 4L << 30,
                at, true, true, [], false, false, false, false, []);
            var installation = new TargetInstallationState(null, null, false, false, false, false, false, false, []);
            var readOnly = new ReadOnlyTargetInspection(Guid.NewGuid(), new("edge.fixture.test", 22), platform,
                hardware, installation, true, new string('d', 64), at, at.AddSeconds(2));
            var draft = new ManufacturingInspectionDraft(readOnly, profile, release, "admin");
            var serial = new DeviceSerial("MR00613A7D755A");
            var devicePorts = ImmutableArray.Create(
                new DeviceEthernetPortRecord("LAN1", "enp1s0", ports[0].PermanentMac!, ports[0].CurrentMac, false, at),
                new DeviceEthernetPortRecord("LAN2", "enp2s0", ports[1].PermanentMac!, ports[1].CurrentMac, true, at));
            var device = new DeviceRecord(Guid.NewGuid(), serial, DeviceLifecycleState.Registered, profile.Reference,
                devicePorts, null, null, null, null, at, at);
            var checklist = new InspectionChecklistSnapshot("manufacturing-profile", 1,
                [new("identity.serial", InspectionCheckRequirement.Required, InspectionCheckOutcome.Passed,
                    serial.Value, "unique", "fixture")]);
            var inspection = new TargetInspection(readOnly.InspectionId, readOnly.Endpoint, profile.Reference, hardware,
                checklist, serial, new string('e', 64), at, at.AddSeconds(2));
            var action = new DeploymentActionPlan(DeploymentActionKind.Deploy, null, release.ReleaseId, false, [],
                ["Activate verified release"]);
            var engineer = new EngineeringIdentity("engineer", "fixture");
            var context = new ManufacturingDeploymentContext(device.DeviceId, serial, profile.Reference,
                inspection.InspectionId, inspection.SnapshotSha256, release, action.Action, engineer);
            var identity = new TargetIdentityExpectation(readOnly.SnapshotSha256,
                devicePorts.Select(port => port.PermanentMac).ToImmutableArray());
            var report = new ManufacturingReportContext(device.DeviceId, serial, devicePorts, profile.Reference,
                inspection.InspectionId, inspection.SnapshotSha256, readOnly.SnapshotSha256, checklist, action.Action,
                action.Action, release, engineer, readOnly.Endpoint, "1.0.0", null, "UTC");
            return new(profile, draft, new(inspection, device, true, action, context, identity, report, "admin", false,
                false));
        }
    }
}
