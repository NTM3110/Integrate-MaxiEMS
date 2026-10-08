using System.IO.Compression;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
public sealed class BootstrapPayloadAssetsTests
{
    [TestMethod]
    public void ProfileAwareBootstrapProtocolIsAccepted()
    {
        using var payload = CreatePayload(ProfileAwareSudoers);
        BootstrapPayloadAssets.ValidateCompatibility(payload.Path);
    }

    [TestMethod]
    public void ArgumentlessBmsSetupSudoersIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(
            "/usr/local/libexec/maxiems-deploy-helper bms-setup, \\\n");

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "build a new full release");
        StringAssert.Contains(exception.Message, "CLI-only build is insufficient");
    }

    [TestMethod]
    public void WildcardBmsSetupSudoersIsRejected()
    {
        using var payload = CreatePayload(
            ProfileAwareSudoers + "/usr/local/libexec/maxiems-deploy-helper bms-setup *\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void ProtocolSnippetsInCommentsDoNotBypassCompatibilityGate()
    {
        using var payload = CreatePayload(ProfileAwareSudoers,
            deployHelper: """
                # require_arg_count 2 "$#"
                # [[ "$2" == 'real' || "$2" == 'demo' ]] || die 'invalid BMS deployment profile'
                """);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "build a new full release");
    }

    [TestMethod]
    public void PayloadWithoutFailureDiagnosticsProtocolIsRejected()
    {
        using var payload = CreatePayload(
            ProfileAwareSudoers.Replace(
                "/usr/local/libexec/maxiems-deploy-helper diagnostics, \\\n",
                string.Empty,
                StringComparison.Ordinal));

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "failure diagnostics");
        StringAssert.Contains(exception.Message, "new full release");
    }

    private const string ProfileAwareSudoers =
        "/usr/local/libexec/maxiems-deploy-helper bms-setup real, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper bms-setup demo, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper diagnostics, \\\n";

    private static TemporaryPayload CreatePayload(string sudoers, string? deployHelper = null)
    {
        var payload = new TemporaryPayload();
        using var archive = ZipFile.Open(payload.Path, ZipArchiveMode.Create);
        Add(archive, "remote/etc/sudoers.d/maxiems-deployer", sudoers);
        Add(archive, "remote/usr/local/libexec/maxiems-deploy-helper", deployHelper ?? """
            bms-setup)
                require_arg_count 2 "$#"
                [[ "$2" == 'real' || "$2" == 'demo' ]] || die 'invalid BMS deployment profile'
                ;;
            status|diagnostics)
                require_arg_count 1 "$#"
                ;;
            """);
        Add(archive, "remote/usr/local/libexec/maxiems-deploy-ops",
            "bms-setup) /usr/local/libexec/maxiems-bms-setup setup \"$2\" ;;\n" +
            "diagnostics)\n  diagnostics\n  ;;\n");
        Add(archive, "remote/usr/local/libexec/maxiems-bms-setup",
            "if os.geteuid() != 0 or (sys.argv[1:] not in (['setup', 'real'], ['setup', 'demo'], ['ready'])):\n");
        return payload;
    }

    private static void Add(ZipArchive archive, string path, string content)
    {
        var entry = archive.CreateEntry(path);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(content);
    }

    private sealed class TemporaryPayload : IDisposable
    {
        public string Path
        {
            get;
        } = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(), $"maxiems-bootstrap-{Guid.NewGuid():N}.zip");

        public void Dispose() => File.Delete(Path);
    }
}
