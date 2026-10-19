using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Security;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
public sealed class DeploymentPreflightWarningTests
{
    [TestMethod]
    public void ClockWarningReachesProgressAndReportWithoutFailingPreflight()
    {
        var events = new List<DeploymentExecutionEvent>();
        var run = new DeploymentRunBuilder(
            new ReleaseId("20261013010000-123456abcdef"),
            new SecretRedactor(),
            events.Add);

        run.Warning(DeploymentPhase.Preflight, "preflight.ClockSkew",
            "ClockSkew: target clock differs by more than five minutes. Deployment continues.");
        var record = run.Finish(DeploymentFinalState.Succeeded, "payload-hash");

        Assert.AreEqual(DeploymentPhase.Preflight, events[0].Phase);
        Assert.AreEqual(DeploymentPhaseOutcome.Running, events[0].Outcome);
        StringAssert.Contains(events[0].OperatorMessage, "WARNING: ClockSkew");
        Assert.AreEqual(DeploymentLogLevel.Warning, record.Logs.Single().Level);
        Assert.AreEqual("preflight.ClockSkew", record.Logs.Single().EventCode);
        Assert.AreEqual(DeploymentFinalState.Succeeded, record.FinalState);
    }
}
