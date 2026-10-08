using Maxiems.Deployer.Core.Security;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Security;

[TestClass]
public sealed class OpenEmsManagementPasswordTests
{
    [TestMethod]
    public void ShortNonEmptyPasswordIsAccepted()
    {
        OpenEmsManagementPassword.RequireNonEmpty("x", "password");
    }

    [TestMethod]
    public void EmptyPasswordIsRejected() =>
        Assert.ThrowsExactly<ArgumentException>(() =>
            OpenEmsManagementPassword.RequireNonEmpty([], "password"));
}
