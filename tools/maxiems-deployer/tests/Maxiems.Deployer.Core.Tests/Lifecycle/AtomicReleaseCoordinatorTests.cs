using Maxiems.Deployer.Core.Lifecycle;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Lifecycle;

[TestClass]
[TestCategory("AtomicRelease")]
public sealed class AtomicReleaseCoordinatorTests
{
    private static readonly string[] KnownGoodReleases = ["v1", "v2"];

    public static IEnumerable<object[]> FaultStages => Enum.GetValues<AtomicReleaseStage>()
        .Where(stage => stage is not AtomicReleaseStage.Begin and not AtomicReleaseStage.Commit)
        .Select(stage => new object[] { stage });

    [TestMethod]
    [DynamicData(nameof(FaultStages))]
    public async Task UpgradeFailureAtEveryBoundaryRestoresOldHealthyRelease(AtomicReleaseStage fault)
    {
        var operations = new ReleaseModel(firstInstall: false, fault);

        var result = await new AtomicReleaseCoordinator(operations).RunAsync(firstInstall: false);

        Assert.IsFalse(result.Succeeded);
        Assert.AreEqual(fault, result.FailedStage);
        Assert.IsTrue(result.RollbackProof!.PreviousReleaseHealthy);
        Assert.IsTrue(result.RollbackProof.SharedStatePreserved);
        Assert.IsTrue(result.RollbackProof.LockReleased);
        Assert.IsTrue(result.RollbackProof.StagingRemoved);
        Assert.AreEqual("v1", operations.CurrentRelease);
        Assert.AreEqual(1, operations.RollbackCount);
    }

    [TestMethod]
    [DynamicData(nameof(FaultStages))]
    public async Task FirstInstallFailureAtEveryBoundaryLeavesCleanInstallAndSharedSentinel(AtomicReleaseStage fault)
    {
        var operations = new ReleaseModel(firstInstall: true, fault);

        var result = await new AtomicReleaseCoordinator(operations).RunAsync(firstInstall: true);

        Assert.IsFalse(result.Succeeded);
        Assert.IsTrue(result.RollbackProof!.CleanFirstInstall);
        Assert.IsNull(operations.CurrentRelease);
        Assert.AreEqual("shared-sentinel", operations.SharedSentinel);
        Assert.AreEqual(0, operations.Releases.Count);
    }

    [TestMethod]
    public async Task SuccessfulUpgradeCommitsCurrentAndPreviousAndPrunesOnlyAfterHealth()
    {
        var operations = new ReleaseModel(firstInstall: false, fault: null);

        var result = await new AtomicReleaseCoordinator(operations).RunAsync(firstInstall: false);

        Assert.IsTrue(result.Succeeded);
        Assert.AreEqual("v2", operations.CurrentRelease);
        Assert.AreEqual("v1", operations.PreviousRelease);
        CollectionAssert.AreEquivalent(KnownGoodReleases, operations.Releases.ToArray());
        Assert.IsFalse(operations.LockHeld);
        Assert.AreEqual(AtomicReleaseStage.FinalHealth, result.CompletedStages[^2]);
        Assert.AreEqual(AtomicReleaseStage.Commit, result.CompletedStages[^1]);
    }

    [TestMethod]
    public async Task SelfReportedRollbackWithoutMachineProofIsRejected()
    {
        var operations = new ReleaseModel(firstInstall: false, AtomicReleaseStage.EdgeStart)
        {
            ReturnInvalidProof = true,
        };

        var exception = await Assert.ThrowsExactlyAsync<AtomicReleaseException>(() =>
            new AtomicReleaseCoordinator(operations).RunAsync(firstInstall: false));

        Assert.AreEqual(AtomicReleaseStage.EdgeStart, exception.FailedStage);
    }

    private sealed class ReleaseModel(bool firstInstall, AtomicReleaseStage? fault) : IAtomicReleaseOperations
    {
        private readonly bool firstInstall = firstInstall;
        private readonly string? original = firstInstall ? null : "v1";

        public bool ReturnInvalidProof
        {
            get; init;
        }
        public bool LockHeld
        {
            get; private set;
        }
        public bool StagingExists
        {
            get; private set;
        }
        public string SharedSentinel { get; } = "shared-sentinel";
        public string? CurrentRelease { get; private set; } = firstInstall ? null : "v1";
        public string? PreviousRelease
        {
            get; private set;
        }
        public HashSet<string> Releases { get; } = firstInstall ? [] : ["v0", "v1"];
        public int RollbackCount
        {
            get; private set;
        }

        public Task ExecuteAsync(AtomicReleaseStage stage, CancellationToken cancellationToken)
        {
            if (stage == fault)
            {
                throw new IOException($"fixture fault at {stage}");
            }
            switch (stage)
            {
                case AtomicReleaseStage.Begin:
                    this.LockHeld = true;
                    break;
                case AtomicReleaseStage.Upload:
                    this.StagingExists = true;
                    break;
                case AtomicReleaseStage.Extraction:
                    this.Releases.Add("v2");
                    this.StagingExists = false;
                    break;
                case AtomicReleaseStage.SymlinkSwitch:
                    this.CurrentRelease = "v2";
                    break;
                case AtomicReleaseStage.Commit:
                    this.PreviousRelease = this.original;
                    this.Releases.RemoveWhere(release => release != "v2" && release != this.original);
                    this.LockHeld = false;
                    break;
            }
            return Task.CompletedTask;
        }

        public Task RollBackAsync(CancellationToken cancellationToken)
        {
            this.RollbackCount++;
            this.CurrentRelease = this.original;
            this.Releases.Remove("v2");
            this.StagingExists = false;
            this.LockHeld = false;
            return Task.CompletedTask;
        }

        public Task<ReleaseRollbackProof> VerifyRollbackAsync(CancellationToken cancellationToken) =>
            Task.FromResult<ReleaseRollbackProof>(
            this.ReturnInvalidProof
                ? new(false, false, false, false, false)
                : new(
                    PreviousReleaseHealthy: !this.firstInstall && this.CurrentRelease == this.original,
                    CleanFirstInstall: this.firstInstall && this.CurrentRelease is null && this.Releases.Count == 0,
                    SharedStatePreserved: this.SharedSentinel == "shared-sentinel",
                    LockReleased: !this.LockHeld,
                    StagingRemoved: !this.StagingExists));
    }
}
