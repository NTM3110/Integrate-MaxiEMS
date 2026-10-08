using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
public sealed class ApplicationShellTests
{
    [TestMethod]
    public void AppAssemblyIsWindowsDesktopApplication()
    {
        Assert.AreEqual("Maxiems.Deployer", typeof(App).Assembly.GetName().Name);
        Assert.IsTrue(typeof(System.Windows.Application).IsAssignableFrom(typeof(App)));
    }
}
