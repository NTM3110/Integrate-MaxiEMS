using System.IO;
using System.Windows;
using Maxiems.Deployer.App.Services;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
public sealed class AppLocalizationServiceTests
{
    [TestMethod]
    public void LanguageSelectionIsPersistedAndStatusTextSwitchesImmediately()
    {
        var path = Path.Combine(Path.GetTempPath(), $"maxiems-language-{Guid.NewGuid():N}.json");
        try
        {
            var localizer = new AppLocalizationService(path);
            Assert.AreEqual("en", localizer.LanguageCode);

            localizer.SetLanguage("vi");

            StringAssert.Contains(localizer.Text("Status.SelectProfile"), "Chọn");
            Assert.AreEqual("vi", new AppLocalizationService(path).LanguageCode);
        }
        finally
        {
            if (File.Exists(path))
                File.Delete(path);
        }
    }

    [TestMethod]
    public void UnsupportedLanguageFallsBackToEnglish()
    {
        var localizer = new AppLocalizationService();

        localizer.SetLanguage("unsupported");

        Assert.AreEqual("en", localizer.LanguageCode);
        Assert.AreEqual("Select an approved profile to begin.", localizer.Text("Status.SelectProfile"));
    }

    [STATestMethod]
    public void EnglishAndVietnameseResourceDictionariesHaveIdenticalKeys()
    {
        var english = Load("en");
        var vietnamese = Load("vi");

        CollectionAssert.AreEquivalent(english.Keys.Cast<object>().ToArray(),
            vietnamese.Keys.Cast<object>().ToArray());
        Assert.IsTrue(english.Keys.Count >= 100, "The operations UI should be localized as a complete surface.");
    }

    private static ResourceDictionary Load(string code) => new()
    {
        Source = new Uri($"pack://application:,,,/Maxiems.Deployer;component/Localization/Strings.{code}.xaml",
            UriKind.Absolute),
    };
}
