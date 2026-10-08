using Maxiems.Deployer.Core.Contracts;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure
{
    public sealed class ArchitectureTestDouble
    {
    }
}

namespace Maxiems.Deployer.Core.Tests
{
    [TestClass]
    [TestCategory("Architecture")]
    public sealed class ArchitectureTests
    {
        [TestMethod]
        public void CoreAssemblyHasNoForbiddenLayerOrImplementationReferences()
        {
            var violations = ArchitecturePolicy.FindAssemblyViolations(typeof(DeploymentTargetInput).Assembly);

            Assert.AreEqual(0, violations.Count, string.Join(Environment.NewLine, violations));
        }

        [TestMethod]
        public void PolicyRejectsInfrastructureTypeExposedByFixtureContract()
        {
            var violations = ArchitecturePolicy.FindContractViolations(typeof(InfrastructureReferenceFixture));

            Assert.IsTrue(violations.Count > 0, "The negative fixture must be rejected.");
            StringAssert.Contains(violations[0], "Maxiems.Deployer.Infrastructure");
        }

        private sealed record InfrastructureReferenceFixture(
            Maxiems.Deployer.Infrastructure.ArchitectureTestDouble Value);
    }
}
