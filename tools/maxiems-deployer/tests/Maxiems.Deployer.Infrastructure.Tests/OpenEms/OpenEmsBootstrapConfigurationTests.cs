using System.Text;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class OpenEmsBootstrapConfigurationTests
{
    private const string AdministratorPassword = "fixture-admin-password";
    private static readonly string[] BootstrapFileNames = [
        "Core/ComponentManager.config",
        "Core/User.config",
        "Controller/Api/Rest/ReadWrite/maxiems.config",
        "Controller/Api/Websocket/maxiems.config",
    ];

    [TestMethod]
    public void DotNetPasswordHashMatchesRepositoryJavaCompatibilityVector()
    {
        var salt = Enumerable.Range(0, 16).Select(value => (byte)value).ToArray();

        var actual = Convert.ToBase64String(OpenEmsPasswordHasher.Derive(
            "correct horse battery staple", salt));

        Assert.AreEqual("KJO73UbHuTKFTeEDNHHpnnZD3lF/reqnckEDr6HpgIE=", actual);
    }

    [TestMethod]
    public void FreshConfigurationKeepsManagementPrivateAndPublishesBmsWebsocket()
    {
        var configuration = OpenEmsBootstrapConfiguration.Create(
            AdministratorPassword,
            new IncrementingEntropy());

        CollectionAssert.AreEquivalent(BootstrapFileNames, configuration.Files.Select(file => file.Name).ToArray());
        var rest = Text(configuration, "Controller/Api/Rest/ReadWrite/maxiems.config");
        var websocket = Text(configuration, "Controller/Api/Websocket/maxiems.config");
        StringAssert.Contains(rest, "bindAddress=\"127.0.0.1\"");
        StringAssert.Contains(rest, "port=I\"8084\"");
        StringAssert.Contains(websocket, "bindAddress=\"0.0.0.0\"");
        StringAssert.Contains(websocket, "port=I\"8085\"");
        Assert.AreEqual(5, Lines(Text(configuration, "Core/ComponentManager.config")));
        Assert.AreEqual(12, Lines(Text(configuration, "Core/User.config")));
        Assert.AreEqual(12, Lines(rest));
        Assert.AreEqual(11, Lines(websocket));

        var user = Properties(Text(configuration, "Core/User.config"));
        StringAssert.Contains(Text(configuration, "Core/User.config"), "\\=");
        var defaults = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["admin"] = "admin",
            ["installer"] = "installer",
            ["owner"] = "owner",
            ["guest"] = "user",
        };
        var salts = new HashSet<string>(StringComparer.Ordinal);
        foreach (var role in defaults)
        {
            var salt = Convert.FromBase64String(user[$"{role.Key}Salt"]);
            var stored = Convert.FromBase64String(user[$"{role.Key}Password"]);
            Assert.AreEqual(OpenEmsPasswordHasher.SaltLengthBytes, salt.Length);
            Assert.IsTrue(salts.Add(Convert.ToBase64String(salt)));
            Assert.IsFalse(stored.SequenceEqual(OpenEmsPasswordHasher.Derive(role.Value, salt)));
        }
        var adminSalt = Convert.FromBase64String(user["adminSalt"]);
        CollectionAssert.AreEqual(
            Convert.FromBase64String(user["adminPassword"]),
            OpenEmsPasswordHasher.Derive(AdministratorPassword, adminSalt));
    }

    [TestMethod]
    public void ShortNonEmptyAdministratorPasswordIsAccepted()
    {
        var configuration = OpenEmsBootstrapConfiguration.Create("x", new IncrementingEntropy());

        var user = Properties(Text(configuration, "Core/User.config"));
        var adminSalt = Convert.FromBase64String(user["adminSalt"]);
        CollectionAssert.AreEqual(
            Convert.FromBase64String(user["adminPassword"]),
            OpenEmsPasswordHasher.Derive("x", adminSalt));
    }

    [TestMethod]
    public void EmptyAdministratorPasswordIsRejected() =>
        Assert.ThrowsExactly<ArgumentException>(() =>
            OpenEmsBootstrapConfiguration.Create([], new IncrementingEntropy()));

    [TestMethod]
    [DataRow("admin")]
    [DataRow("installer")]
    [DataRow("owner")]
    [DataRow("user")]
    public void RepositoryDefaultAdministratorPasswordsAreRejected(string password)
    {
        Assert.ThrowsExactly<ArgumentException>(() =>
            OpenEmsBootstrapConfiguration.Create(password, new IncrementingEntropy()));
    }

    [TestMethod]
    public async Task ExistingConfigurationIsPreservedWithoutGeneratingOrWritingCredentials()
    {
        var store = new RecordingStore(exists: true);
        var entropy = new CountingEntropy();
        var coordinator = new OpenEmsBootstrapCoordinator(store, entropy);
        var password = "current-api-password".ToCharArray();
        try
        {
            var result = await coordinator.EnsureAsync(password, CancellationToken.None);

            Assert.AreEqual(OpenEmsBootstrapDisposition.PreservedExisting, result);
            Assert.AreEqual(0, entropy.CallCount);
            Assert.AreEqual(0, store.InstallCount);
        }
        finally
        {
            Array.Clear(password);
        }
    }

    [TestMethod]
    public async Task FreshConfigurationIsInstalledExactlyOnce()
    {
        var store = new RecordingStore(exists: false);
        var coordinator = new OpenEmsBootstrapCoordinator(store, new IncrementingEntropy());
        var password = AdministratorPassword.ToCharArray();
        try
        {
            var result = await coordinator.EnsureAsync(password, CancellationToken.None);

            Assert.AreEqual(OpenEmsBootstrapDisposition.Created, result);
            Assert.AreEqual(1, store.InstallCount);
            Assert.AreEqual(4, store.InstalledFiles.Count);
        }
        finally
        {
            Array.Clear(password);
        }
    }

    private static string Text(OpenEmsBootstrapConfiguration configuration, string name) =>
        Encoding.UTF8.GetString(configuration.Files.Single(file => file.Name == name).Content);

    private static Dictionary<string, string> Properties(string text) => text.Split('\n')
        .Where(line => line.Contains('=') && !line.StartsWith(':'))
        .Select(line => line.Split('=', 2))
        .ToDictionary(parts => parts[0], parts => parts[1].Trim('"').Replace("\\=", "=", StringComparison.Ordinal),
            StringComparer.Ordinal);

    private static int Lines(string text) => text.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length;

    private sealed class IncrementingEntropy : IBootstrapEntropy
    {
        private byte next;

        public byte[] GetBytes(int count)
        {
            var bytes = new byte[count];
            for (var index = 0; index < count; index++)
            {
                bytes[index] = this.next++;
            }
            return bytes;
        }
    }

    private sealed class CountingEntropy : IBootstrapEntropy
    {
        public int CallCount
        {
            get; private set;
        }

        public byte[] GetBytes(int count)
        {
            this.CallCount++;
            return new byte[count];
        }
    }

    private sealed class RecordingStore(bool exists) : IOpenEmsBootstrapStore
    {
        public int InstallCount
        {
            get; private set;
        }
        public IReadOnlyCollection<OpenEmsBootstrapFile> InstalledFiles { get; private set; } = [];

        public Task<bool> ConfigurationExistsAsync(CancellationToken cancellationToken) => Task.FromResult(exists);

        public Task InstallFreshAsync(
            IReadOnlyCollection<OpenEmsBootstrapFile> files,
            CancellationToken cancellationToken)
        {
            this.InstallCount++;
            this.InstalledFiles = files;
            return Task.CompletedTask;
        }
    }
}
