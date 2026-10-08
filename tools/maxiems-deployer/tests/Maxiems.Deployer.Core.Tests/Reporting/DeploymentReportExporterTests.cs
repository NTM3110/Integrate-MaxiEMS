using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Reporting;
using Maxiems.Deployer.Core.Security;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Reporting;

[TestClass]
[TestCategory("Wizard")]
public sealed class DeploymentReportExporterTests
{
    private string temporaryDirectory = null!;

    [TestInitialize]
    public void Initialize()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-report-{Guid.NewGuid():N}");
    }

    [TestCleanup]
    public void Cleanup()
    {
        if (Directory.Exists(this.temporaryDirectory))
            Directory.Delete(this.temporaryDirectory, true);
    }

    [TestMethod]
    public async Task ExportsSchemaReadableAtomicJsonAndSelfContainedHtmlWithoutSecrets()
    {
        const string privateKey = "seeded-private-key-value";
        const string passphrase = "seeded-passphrase-value";
        const string password = "seeded-openems-password";
        const string authorization = "Basic c2VlZGVkOmhlYWRlcg==";
        const string tailscaleUrl = "https://login.tailscale.com/seeded-url-token";
        var started = DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var record = new DeploymentRunRecord(
            Guid.Parse("f6862446-f07c-4077-b103-4b0350be0458"),
            DeploymentFinalState.RolledBack,
            started,
            started.AddSeconds(9),
            TimeSpan.FromSeconds(9),
            [
                new(DeploymentPhase.StageRelease, DeploymentPhaseOutcome.Passed, started,
                    started.AddSeconds(2), TimeSpan.FromSeconds(2), null,
                    $"release 20260719010000-0123456789ab sha256 abc; {privateKey}"),
                new(DeploymentPhase.Rollback, DeploymentPhaseOutcome.RolledBack, started.AddSeconds(4),
                    started.AddSeconds(9), TimeSpan.FromSeconds(5), "edge-health-failed",
                    $"rollback verified; {tailscaleUrl}"),
            ],
            [
                new(started, DeploymentLogLevel.Information, "trust-replaced",
                    $"old SHA256:old new SHA256:new {authorization}",
                    [
                        new("privateKey", privateKey, false),
                        new("passphrase", passphrase, false),
                        new("openemsPassword", password, false),
                        new("tailscaleUrl", tailscaleUrl, false),
                    ]),
            ])
        {
            ReleaseId = new("20260719010000-0123456789ab"),
            ArtifactHashes = ImmutableDictionary<string, string>.Empty.Add("edge/openems.jar", "abc123"),
            Versions = ImmutableDictionary<string, string>.Empty.Add("java", "21.0.8"),
            Checks = [new("rest-ready", true, $"REST ready; {password}")],
            HostTrust = new(new("edge.fixture.test", 22), HostTrustStatus.Changed, "ssh-ed25519",
                "SHA256:old", "SHA256:new", started),
            Rollback = new(true, true, "20260718010000-fedcba987654", $"old release healthy; {passphrase}"),
            Evidence = [new("systemd-user", true, $"User=openems; {privateKey}")],
        };
        var redactor = new SecretRedactor([
            new("privateKey", privateKey), new("passphrase", passphrase), new("password", password),
        ]);
        var exporter = new DeploymentReportExporter(new(redactor));

        var paths = await exporter.ExportAsync(record, this.temporaryDirectory);
        var json = await File.ReadAllTextAsync(paths.JsonPath);
        var html = await File.ReadAllTextAsync(paths.HtmlPath);

        using var document = JsonDocument.Parse(json);
        Assert.AreEqual(record.DeploymentId, document.RootElement.GetProperty("deploymentId").GetGuid());
        StringAssert.Contains(json, "edge-health-failed");
        StringAssert.Contains(json, "20260719010000-0123456789ab");
        StringAssert.Contains(json, "edge/openems.jar");
        StringAssert.Contains(json, "trust-replaced");
        StringAssert.Contains(json, "priorReleaseRestored");
        StringAssert.Contains(json, "[REDACTED]");
        StringAssert.Contains(html, "<!doctype html>");
        StringAssert.Contains(html, "deploymentId");
        Assert.IsFalse(html.Contains("<script", StringComparison.OrdinalIgnoreCase));
        foreach (var secret in new[] { privateKey, passphrase, password, authorization, tailscaleUrl })
        {
            Assert.IsFalse(json.Contains(secret, StringComparison.Ordinal), $"JSON leaked: {secret}");
            Assert.IsFalse(html.Contains(secret, StringComparison.Ordinal), $"HTML leaked: {secret}");
        }
        Assert.IsFalse(Directory.EnumerateFiles(this.temporaryDirectory, "*.tmp").Any());
    }

    [TestMethod]
    public async Task FormatTwoRendersStructuredManufacturingTablesUnicodeAndTimezoneSafely()
    {
        const string secret = "atenergy-secret-value";
        var started = DateTimeOffset.Parse("2026-07-22T08:00:00+07:00",
            System.Globalization.CultureInfo.InvariantCulture);
        var serialMac = new MacAddress("00:61:3A:7D:75:5A");
        var context = new ManufacturingReportContext(Guid.NewGuid(), DeviceSerial.FromSerialSource(serialMac), [
            new($"LAN1-{secret}", $"eth0-{secret}", new("00:61:3A:7D:75:5B"), new("00:61:3A:7D:75:5B"), false, started),
            new($"LAN2-{secret}", $"eth1-{secret}", serialMac, serialMac, true, started),
        ], new($"maxicom-edge-{secret}", 3, new('c', 64)), Guid.NewGuid(), new('d', 64), new('e', 64),
            new($"profile-checklist-{secret}", 3, [new($"cpu.model-{secret}", InspectionCheckRequirement.Required,
                InspectionCheckOutcome.Passed, $"Bộ xử lý <x86> {secret}", $"Đã duyệt {secret}", secret)]),
            DeploymentActionKind.Update, DeploymentActionKind.Update,
            new(new($"release-2-{secret}"), $"2.0.0-{secret}", LocalReleaseSource.Imported,
                LocalReleaseStatus.ImportedVerified, new('a', 64), new('b', 64), [$"24.04-{secret}"],
                [SupportedArchitecture.Amd64], $"1.0.0-{secret}"),
            new($"kỹ-sư-{secret}", $"MÁY-01-{secret}"), new($"{secret}.local", 22),
            $"1.0.0-{secret}", $"abc123-{secret}", $"SE Asia Standard Time-{secret}", Guid.NewGuid(),
            $"Retry-{secret}");
        var record = Record(started) with
        {
            ReportFormatVersion = 2,
            Manufacturing = context
        };
        var exporter = new DeploymentReportExporter(new(new SecretRedactor([new("password", secret)])));

        var paths = await exporter.ExportAsync(record, this.temporaryDirectory);
        var json = await File.ReadAllTextAsync(paths.JsonPath);
        var html = await File.ReadAllTextAsync(paths.HtmlPath);

        Assert.AreEqual(2, DeploymentReportSerializer.Deserialize(json).ReportFormatVersion);
        StringAssert.Contains(html, "Ethernet ports");
        StringAssert.Contains(html, "Hardware checklist");
        StringAssert.Contains(html, "SE Asia Standard Time");
        StringAssert.Contains(html, "&lt;x86&gt;");
        Assert.IsFalse(html.Contains(secret, StringComparison.Ordinal));
        Assert.IsFalse(json.Contains(secret, StringComparison.Ordinal));
    }

    [TestMethod]
    public void DeserializeKeepsFormatOneCompatibility()
    {
        var record = Record(DateTimeOffset.Parse("2026-07-22T01:00:00Z",
            System.Globalization.CultureInfo.InvariantCulture));
        var json = new DeploymentReportSerializer(new SecretRedactor()).Serialize(record);

        var restored = DeploymentReportSerializer.Deserialize(json);

        Assert.AreEqual(1, restored.ReportFormatVersion);
        Assert.IsNull(restored.Manufacturing);
    }

    [TestMethod]
    public async Task ReportIntegrityDetectsVerifiedChangedAndMissingFiles()
    {
        Directory.CreateDirectory(this.temporaryDirectory);
        var jsonPath = Path.Combine(this.temporaryDirectory, "report.json");
        var htmlPath = Path.Combine(this.temporaryDirectory, "report.html");
        await File.WriteAllTextAsync(jsonPath, "{}", System.Text.Encoding.UTF8);
        await File.WriteAllTextAsync(htmlPath, "<html></html>", System.Text.Encoding.UTF8);
        var reports = new ReportReference(jsonPath, htmlPath)
        {
            JsonSha256 = await ReportIntegrityVerifier.HashAsync(jsonPath),
            HtmlSha256 = await ReportIntegrityVerifier.HashAsync(htmlPath),
            FormatVersion = 2,
        };

        Assert.AreEqual(ReportIntegrityState.Verified, (await ReportIntegrityVerifier.VerifyAsync(reports)).State);
        await File.AppendAllTextAsync(htmlPath, "tampered");
        Assert.AreEqual(ReportIntegrityState.Changed, (await ReportIntegrityVerifier.VerifyAsync(reports)).State);
        File.Delete(jsonPath);
        Assert.AreEqual(ReportIntegrityState.Missing, (await ReportIntegrityVerifier.VerifyAsync(reports)).State);
    }

    private static DeploymentRunRecord Record(DateTimeOffset started) => new(Guid.NewGuid(),
        DeploymentFinalState.Succeeded, started, started.AddSeconds(1), TimeSpan.FromSeconds(1), [], [])
    {
        ReleaseId = new("release-2"),
    };
}
