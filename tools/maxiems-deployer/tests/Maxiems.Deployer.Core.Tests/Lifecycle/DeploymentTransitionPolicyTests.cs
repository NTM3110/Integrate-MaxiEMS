using Maxiems.Deployer.Core.Lifecycle;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Lifecycle;

[TestClass]
[TestCategory("StateMachine")]
public sealed class DeploymentTransitionPolicyTests
{
    [TestMethod]
    public void EveryAllowedAndForbiddenTransitionMatchesTheFrozenPolicy()
    {
        var allowed = new HashSet<(DeploymentState From, DeploymentState To)> {
            (DeploymentState.ValidateInput, DeploymentState.ConnectBootstrap),
            (DeploymentState.ValidateInput, DeploymentState.Cancelled),
            (DeploymentState.ValidateInput, DeploymentState.Failed),
            (DeploymentState.ConnectBootstrap, DeploymentState.Preflight),
            (DeploymentState.ConnectBootstrap, DeploymentState.Cancelled),
            (DeploymentState.ConnectBootstrap, DeploymentState.Failed),
            (DeploymentState.Preflight, DeploymentState.ProvisionIdentity),
            (DeploymentState.Preflight, DeploymentState.Cancelled),
            (DeploymentState.Preflight, DeploymentState.Failed),
            (DeploymentState.ProvisionIdentity, DeploymentState.ReconnectRestricted),
            (DeploymentState.ReconnectRestricted, DeploymentState.InstallPrerequisites),
            (DeploymentState.InstallPrerequisites, DeploymentState.StageRelease),
            (DeploymentState.StageRelease, DeploymentState.VerifyRemotePayload),
            (DeploymentState.VerifyRemotePayload, DeploymentState.ActivateRelease),
            (DeploymentState.ActivateRelease, DeploymentState.StartServices),
            (DeploymentState.StartServices, DeploymentState.ConfigureTailscale),
            (DeploymentState.ConfigureTailscale, DeploymentState.ReconcileComponents),
            (DeploymentState.ReconcileComponents, DeploymentState.HealthCheck),
            (DeploymentState.HealthCheck, DeploymentState.Complete),
            (DeploymentState.Rollback, DeploymentState.RolledBack),
            (DeploymentState.Rollback, DeploymentState.Failed),
        };
        foreach (var state in Enum.GetValues<DeploymentState>())
        {
            if (state is >= DeploymentState.ProvisionIdentity and <= DeploymentState.HealthCheck)
            {
                allowed.Add((state, DeploymentState.Rollback));
            }
        }

        foreach (var from in Enum.GetValues<DeploymentState>())
        {
            foreach (var to in Enum.GetValues<DeploymentState>())
            {
                Assert.AreEqual(
                    allowed.Contains((from, to)),
                    DeploymentTransitionPolicy.CanTransition(from, to),
                    $"Unexpected policy for {from} -> {to}.");
            }
        }
    }

    [TestMethod]
    public void ForbiddenTransitionThrows()
    {
        var exception = Assert.ThrowsExactly<InvalidOperationException>(
            () => DeploymentTransitionPolicy.EnsureTransition(DeploymentState.Preflight, DeploymentState.Complete));
        StringAssert.Contains(exception.Message, "Preflight -> Complete");
    }
}
