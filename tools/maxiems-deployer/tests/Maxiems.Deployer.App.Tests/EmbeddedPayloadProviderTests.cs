using System.IO;
using Maxiems.Deployer.App.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
[TestCategory("ReleaseCatalog")]
public sealed class EmbeddedPayloadProviderTests
{
    [TestMethod]
    public async Task AdapterPreservesEmbeddedPayloadBytesAndUsesCommonAbstraction()
    {
        byte[] expected = [0x50, 0x4b, 0x03, 0x04, 0x01, 0x02, 0x03];
        var path = await MaterializeThroughAbstractionAsync(
            new EmbeddedPayloadProvider(() => new MemoryStream(expected, false)));
        try
        {
            CollectionAssert.AreEqual(expected, await File.ReadAllBytesAsync(path));
        }
        finally
        {
            File.Delete(path);
        }
    }

#pragma warning disable CA1859 // The interface dispatch is the behavior under test.
    private static Task<string> MaterializeThroughAbstractionAsync(IDeploymentPayloadProvider provider) =>
        provider.MaterializeAsync(CancellationToken.None);
#pragma warning restore CA1859
}
