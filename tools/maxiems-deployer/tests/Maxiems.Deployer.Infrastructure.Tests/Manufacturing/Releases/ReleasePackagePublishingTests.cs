using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Manufacturing.Releases;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Releases;

[TestClass]
[TestCategory("ReleaseCatalog")]
public sealed class ReleasePackagePublishingTests
{
    [TestMethod]
    public async Task PublishingScriptProducesImportablePackage()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"maxiems-release-publish-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            var payload = Path.Combine(directory, "payload.zip");
            var package = Path.Combine(directory, "published.maxiems-release");
            ReleasePackageTestSupport.CreatePayload(payload, ReleasePackageTestSupport.ReleaseId, "published payload");
            var deployerRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.."));
            var startInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "powershell.exe",
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            foreach (var argument in new[] {
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                Path.Combine(deployerRoot, "build-release-package.ps1"), "-PayloadPath", payload,
                "-Output", package, "-Version", "1.0.0", "-SupportedUbuntuVersions", "24.04",
                "-SupportedArchitectures", "arm64", "-MinimumDeployerVersion", "1.0.0",
            })
                startInfo.ArgumentList.Add(argument);
            using var process = System.Diagnostics.Process.Start(startInfo)!;
            var error = await process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync();
            Assert.AreEqual(0, process.ExitCode, error);

            var store = new SqliteManufacturingStore(Path.Combine(directory, "manufacturing.db"));
            await store.InitializeAsync();
            var imported = await new ReleasePackageImporter(store, Path.Combine(directory, "cache")).ImportAsync(package,
                new(new Version(1, 0, 0), "24.04", SupportedArchitecture.Arm64));
            Assert.AreEqual(ReleasePackageTestSupport.ReleaseId, imported.Descriptor.ReleaseId.Value);
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            Directory.Delete(directory, true);
        }
    }
}
