using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
public sealed class DeploymentActionPlannerTests
{
    [TestMethod]
    [DataRow(false, false, false, false, "Deploy")]
    [DataRow(true, false, false, false, "Update")]
    [DataRow(true, true, false, false, "NoChange")]
    [DataRow(false, false, true, false, "LegacyMigration")]
    [DataRow(true, false, false, true, "Downgrade")]
    public void PlanSelectsDeterministicAction(bool installed, bool same, bool legacy, bool downgrade, string expected)
    {
        ReleaseId? current = installed ? new ReleaseId(same ? "release-2" : "release-1") : null;
        var request = Request(State(current, legacy: legacy), downgrade: downgrade);

        var plan = ManufacturingDeploymentActionPlanner.Plan(request);

        Assert.AreEqual(expected, plan.Action.ToString());
        Assert.IsEmpty(plan.BlockingReasons);
    }

    [TestMethod]
    public void PlanBlocksExternalPortOwner()
    {
        var state = State(null) with
        {
            Ports = [new(502, TargetServiceOwner.External, "0.0.0.0:502")]
        };

        var plan = ManufacturingDeploymentActionPlanner.Plan(Request(state));

        Assert.AreEqual(DeploymentActionKind.BlockedConflict, plan.Action);
        StringAssert.Contains(plan.BlockingReasons.Single(), "502");
    }

    [TestMethod]
    public void PlanAllowsManagedAndLegacyPortOwners()
    {
        var ports = ImmutableArray.Create(
            new TargetPortState(8084, TargetServiceOwner.MaxiemsManaged, "*:8084"),
            new TargetPortState(502, TargetServiceOwner.LegacyOpenEms, "*:502"));

        var plan = ManufacturingDeploymentActionPlanner.Plan(Request(State(null, legacy: true) with
        {
            Ports = ports
        }));

        Assert.AreEqual(DeploymentActionKind.LegacyMigration, plan.Action);
    }

    [TestMethod]
    public void PlanDowngradeRequiresAdvancedConfirmationAndReason()
    {
        var request = Request(State(new("release-3")), downgrade: true) with
        {
            AdvancedDowngradeEnabled = false,
            DowngradeConfirmed = false,
            DowngradeReason = " ",
        };

        var plan = ManufacturingDeploymentActionPlanner.Plan(request);

        Assert.AreEqual(DeploymentActionKind.BlockedConflict, plan.Action);
        Assert.HasCount(3, plan.BlockingReasons);
    }

    [TestMethod]
    public void PlanNoChangeIsSuccessfulNoOpCandidate()
    {
        var plan = ManufacturingDeploymentActionPlanner.Plan(Request(State(new("release-2"))));

        Assert.AreEqual(DeploymentActionKind.NoChange, plan.Action);
        Assert.IsEmpty(plan.BlockingReasons);
        StringAssert.Contains(plan.PlannedChanges.Single(), "without replacing");
    }

    private static DeploymentPlanningRequest Request(TargetInstallationState state, bool downgrade = false) => new(
        state,
        new(new("release-2"), "2.0.0", LocalReleaseSource.Imported, LocalReleaseStatus.ImportedVerified,
            new('a', 64), new('b', 64), ["24.04"], [SupportedArchitecture.Amd64], "1.0.0"),
        true, downgrade, true, true, downgrade ? "Controlled recovery" : null);

    private static TargetInstallationState State(ReleaseId? release, bool legacy = false) => new(
        release, release is null ? null : "1.0.0", release is not null, release is not null,
        legacy, legacy, release is not null, release is not null, []);
}
