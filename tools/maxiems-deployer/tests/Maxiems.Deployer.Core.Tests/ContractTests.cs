using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests;

[TestClass]
public sealed class ContractTests
{
    [TestMethod]
    public void DeploymentPhaseResultCarriesExplicitTimingAndOutcome()
    {
        var startedAt = new DateTimeOffset(2026, 7, 19, 8, 0, 0, TimeSpan.FromHours(7));
        var completedAt = startedAt.AddSeconds(12);
        var result = new DeploymentPhaseResult(
            DeploymentPhase.Preflight,
            DeploymentPhaseOutcome.Passed,
            startedAt,
            completedAt,
            TimeSpan.FromSeconds(12),
            null,
            "Ubuntu 24.04 amd64 verified.");

        Assert.AreEqual(TimeSpan.FromSeconds(12), result.Duration);
        Assert.AreEqual(DeploymentPhaseOutcome.Passed, result.Outcome);
    }

    [TestMethod]
    public void ComponentManifestUsesTypedValuesInsteadOfObjectPayloads()
    {
        var manifest = new ComponentManifest(
            1,
            [
                new ComponentDefinition(
                    "Controller.Api.Modbus.Tcp.ReadOnly",
                    "ctrlApiModbus0",
                    [ new ComponentProperty("port", new ComponentNumberValue(502)) ]),
            ]);

        Assert.IsInstanceOfType<ComponentNumberValue>(manifest.Components[0].Properties[0].Value);
        Assert.AreEqual(502m, ((ComponentNumberValue)manifest.Components[0].Properties[0].Value).Value);
    }

    [TestMethod]
    public void DeploymentHistorySummaryHasValueSemantics()
    {
        var start = DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z",
            System.Globalization.CultureInfo.InvariantCulture);
        var id = Guid.Parse("4269c468-359d-4555-9b14-8916743ca028");
        var first = new DeploymentHistorySummary(
            id,
            "edge-01:22",
            new ReleaseId("20260719010000-0123456789ab"),
            DeploymentFinalState.Succeeded,
            start,
            start.AddMinutes(2),
            TimeSpan.FromMinutes(2),
            new ReportReference("report.json", "report.html"));

        Assert.AreEqual(first, first with
        {
        });
    }
}
