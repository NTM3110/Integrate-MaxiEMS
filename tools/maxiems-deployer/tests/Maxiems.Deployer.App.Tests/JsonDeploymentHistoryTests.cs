using System.IO;
using Maxiems.Deployer.App.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
[TestCategory("Wizard")]
public sealed class JsonDeploymentHistoryTests
{
    private string temporaryDirectory = null!;
    private string historyPath = null!;

    [TestInitialize]
    public void Initialize()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-history-{Guid.NewGuid():N}");
        this.historyPath = Path.Combine(this.temporaryDirectory, "history.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        if (Directory.Exists(this.temporaryDirectory))
            Directory.Delete(this.temporaryDirectory, true);
    }

    [TestMethod]
    public async Task AppendIsAtomicDeduplicatedAndReloadable()
    {
        var store = new JsonDeploymentHistory(this.historyPath);
        var original = WizardTestSupport.History();
        var replacement = original with
        {
            FinalState = Maxiems.Deployer.Core.Contracts.DeploymentFinalState.RolledBack
        };

        await store.AppendAsync(original, CancellationToken.None);
        await store.AppendAsync(replacement, CancellationToken.None);
        var loaded = await new JsonDeploymentHistory(this.historyPath).LoadAsync(CancellationToken.None);

        Assert.AreEqual(1, loaded.Count);
        Assert.AreEqual(replacement.FinalState, loaded.Single().FinalState);
        Assert.IsFalse(Directory.EnumerateFiles(this.temporaryDirectory, "*.tmp").Any());
    }

    [TestMethod]
    public async Task CorruptHistoryIsQuarantinedWithoutBlockingStartup()
    {
        Directory.CreateDirectory(this.temporaryDirectory);
        await File.WriteAllTextAsync(this.historyPath, "{ definitely-not-json }");
        var clock = new ManualTimeProvider(DateTimeOffset.Parse(
            "2026-07-19T01:02:03Z", System.Globalization.CultureInfo.InvariantCulture));

        var loaded = await new JsonDeploymentHistory(this.historyPath, clock).LoadAsync(CancellationToken.None);

        Assert.AreEqual(0, loaded.Count);
        Assert.IsFalse(File.Exists(this.historyPath));
        Assert.AreEqual(1, Directory.EnumerateFiles(this.temporaryDirectory, "history.json.corrupt-*").Count());
    }
}
