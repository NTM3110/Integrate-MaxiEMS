using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Manufacturing;

[TestClass]
[TestCategory("Manufacturing")]
public sealed class ManufacturingWarehouseAcceptanceTests
{
    [TestMethod]
    public void BaselineEvidenceAllowsWarehouseIntake()
    {
        var result = ManufacturingWarehouseAcceptance.Evaluate(Run(Baseline()), DeploymentActionKind.Deploy, false);

        Assert.IsTrue(result.IsReady);
        Assert.IsTrue(result.Checks.All(check => check.Outcome == InspectionCheckOutcome.Passed));
    }

    [TestMethod]
    public void EveryMissingOrFailedRequiredCheckBlocksWarehouseIntake()
    {
        foreach (var code in Baseline().Select(check => check.Code))
        {
            var missing = ManufacturingWarehouseAcceptance.Evaluate(
                Run(Baseline().Where(check => check.Code != code)), DeploymentActionKind.Deploy, false);
            var failed = ManufacturingWarehouseAcceptance.Evaluate(Run(Baseline().Select(check =>
                check.Code == code ? check with
                {
                    Passed = false
                } : check)), DeploymentActionKind.Deploy, false);
            Assert.IsFalse(missing.IsReady, code);
            Assert.IsFalse(failed.IsReady, code);
        }
    }

    [TestMethod]
    public void MigrationAndRebootPoliciesRequireAdditionalEvidence()
    {
        Assert.IsFalse(ManufacturingWarehouseAcceptance.Evaluate(Run(Baseline()),
            DeploymentActionKind.LegacyMigration, false).IsReady);
        Assert.IsFalse(ManufacturingWarehouseAcceptance.Evaluate(Run(Baseline()),
            DeploymentActionKind.Update, true).IsReady);
        var complete = Baseline().Concat([
            new("legacy-disabled", true, "disabled"), new("reboot-persistence", true, "reconnected"),
        ]);
        Assert.IsTrue(ManufacturingWarehouseAcceptance.Evaluate(Run(complete),
            DeploymentActionKind.LegacyMigration, true).IsReady);
    }

    private static IEnumerable<DeploymentCheckEvidence> Baseline() => [
        new("service-active-enabled", true, "active and enabled"),
        new("nginx-ui", true, "UI responded"),
        new("openems-authenticated", true, "authenticated"),
        new("binding-policy", true, "loopback"),
        new("release-integrity", true, "hashes match"),
    ];

    private static DeploymentRunRecord Run(IEnumerable<DeploymentCheckEvidence> checks)
    {
        var at = DateTimeOffset.Parse("2026-07-22T11:00:00Z",
            System.Globalization.CultureInfo.InvariantCulture);
        return new(Guid.NewGuid(), DeploymentFinalState.Succeeded, at, at.AddMinutes(1), TimeSpan.FromMinutes(1), [], [])
        {
            Checks = checks.ToImmutableArray(),
        };
    }
}
