using Microsoft.VisualStudio.TestTools.UnitTesting;
using Renci.SshNet;

namespace Maxiems.Deployer.Infrastructure.Tests;

[TestClass]
public sealed class DependencyContractTests
{
    [TestMethod]
    public void SshNetDependencyIsAvailableToInfrastructure()
    {
        Assert.AreEqual("Renci.SshNet", typeof(SshClient).Namespace);
    }
}
