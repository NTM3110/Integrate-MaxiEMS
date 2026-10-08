using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Maxiems.Deployer.Infrastructure.Payload;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests;

[TestClass]
public sealed class PayloadVerifierTests
{
    private string temporaryDirectory = null!;

    [TestInitialize]
    public void Initialize()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-payload-{Guid.NewGuid():N}");
        Directory.CreateDirectory(this.temporaryDirectory);
    }

    [TestCleanup]
    public void Cleanup()
    {
        Directory.Delete(this.temporaryDirectory, true);
    }

    [TestMethod]
    public void VerifierDerivesReleaseIdFromBuildTimeAndCanonicalPayloadHash()
    {
        var archivePath = Path.Combine(this.temporaryDirectory, "payload.zip");
        var content = Encoding.UTF8.GetBytes("verified payload");
        var contentHash = Convert.ToHexString(SHA256.HashData(content)).ToLowerInvariant();
        var canonical = $"ui/index.html\0{content.Length}\0{contentHash}\n";
        var payloadHash = Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant();
        using (var archive = ZipFile.Open(archivePath, ZipArchiveMode.Create))
        {
            var file = archive.CreateEntry("ui/index.html");
            using (var stream = file.Open())
                stream.Write(content);
            var manifest = archive.CreateEntry("manifest.json");
            using var writer = new StreamWriter(manifest.Open());
            writer.Write(new JsonObject
            {
                ["formatVersion"] = 2,
                ["releaseId"] = $"20261005081656-{payloadHash[..12]}",
                ["buildTimestampUtc"] = "2026-10-05T08:16:56Z",
                ["payloadHash"] = payloadHash,
                ["files"] = new JsonArray(new JsonObject
                {
                    ["path"] = "ui/index.html",
                    ["length"] = content.Length,
                    ["sha256"] = contentHash,
                }),
            }.ToJsonString());
        }

        var archiveHash = Convert.ToHexString(
            SHA256.HashData(File.ReadAllBytes(archivePath))).ToLowerInvariant();
        var result = PayloadVerifier.VerifyArchive(archivePath);

        Assert.IsTrue(result.IsValid, FormatMismatches(result));
        Assert.AreEqual($"20261005081656-{payloadHash[..12]}", result.ReleaseId);
        Assert.AreEqual(archiveHash, result.PayloadSha256);
    }

    [TestMethod]
    public void BuilderProducesByteIdenticalVerifiedArchives()
    {
        var fixture = this.CreateFixture();
        var first = Path.Combine(this.temporaryDirectory, "first.zip");
        var second = Path.Combine(this.temporaryDirectory, "second.zip");

        RunBuilder(fixture.EdgeJar, fixture.UiDist, first);
        RunBuilder(fixture.EdgeJar, fixture.UiDist, second);

        CollectionAssert.AreEqual(File.ReadAllBytes(first), File.ReadAllBytes(second));
        using (var archive = ZipFile.OpenRead(first))
        {
            var paths = archive.Entries.Select(entry => entry.FullName).ToArray();
            CollectionAssert.AreEqual(paths.Order(StringComparer.Ordinal).ToArray(), paths);
        }
        var result = PayloadVerifier.VerifyArchive(first);
        Assert.IsTrue(result.IsValid, FormatMismatches(result));
        StringAssert.Matches(result.ReleaseId!, new("^20260719010000-[0-9a-f]{12}$"));
        Assert.IsNotNull(result.PayloadSha256);
        using var manifestArchive = ZipFile.OpenRead(first);
        using var reader = new StreamReader(manifestArchive.GetEntry("manifest.json")!.Open());
        var manifest = JsonNode.Parse(reader.ReadToEnd())!;
        Assert.IsTrue(result.ReleaseId.EndsWith(manifest["payloadHash"]!.GetValue<string>()[..12],
            StringComparison.Ordinal));
    }

    [TestMethod]
    public void VerifierRejectsAFalseDeclaredCanonicalPayloadHash()
    {
        var fixture = this.CreateFixture();
        var archive = Path.Combine(this.temporaryDirectory, "payload.zip");
        var extracted = Path.Combine(this.temporaryDirectory, "false-hash");
        RunBuilder(fixture.EdgeJar, fixture.UiDist, archive);
        ZipFile.ExtractToDirectory(archive, extracted);
        var manifestPath = Path.Combine(extracted, "manifest.json");
        var manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!;
        manifest["payloadHash"] = new string('0', 64);
        File.WriteAllText(manifestPath, manifest.ToJsonString());

        var result = PayloadVerifier.VerifyExtractedDirectory(extracted);

        Assert.IsFalse(result.IsValid);
        Assert.IsTrue(result.Mismatches.Any(item => item.Reason == "canonical payload hash mismatch"));
    }

    [TestMethod]
    public void VerifierSortsManifestInventoryForCanonicalIdentity()
    {
        var fixture = this.CreateFixture();
        var archive = Path.Combine(this.temporaryDirectory, "payload.zip");
        var extracted = Path.Combine(this.temporaryDirectory, "manifest-order");
        RunBuilder(fixture.EdgeJar, fixture.UiDist, archive);
        ZipFile.ExtractToDirectory(archive, extracted);
        var manifestPath = Path.Combine(extracted, "manifest.json");
        var manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!;
        var files = manifest["files"]!.AsArray();
        var reversed = files.Reverse().Select(item => item!.DeepClone()).ToArray();
        files.Clear();
        foreach (var file in reversed)
            files.Add(file);
        File.WriteAllText(manifestPath, manifest.ToJsonString());

        var result = PayloadVerifier.VerifyExtractedDirectory(extracted);

        Assert.IsTrue(result.IsValid, FormatMismatches(result));
    }

    [TestMethod]
    public void BuilderRejectsMissingOrEmptyRequiredInputs()
    {
        var fixture = this.CreateFixture();
        File.Delete(fixture.EdgeJar);
        var missingJar = RunBuilder(fixture.EdgeJar, fixture.UiDist, Path.Combine(this.temporaryDirectory, "missing.zip"), false);
        StringAssert.Contains(missingJar, "Edge JAR does not exist");

        File.WriteAllBytes(fixture.EdgeJar, []);
        var emptyJar = RunBuilder(fixture.EdgeJar, fixture.UiDist, Path.Combine(this.temporaryDirectory, "empty.zip"), false);
        StringAssert.Contains(emptyJar, "Edge JAR must not be empty");

        File.WriteAllBytes(fixture.EdgeJar, [1]);
        File.Delete(Path.Combine(fixture.UiDist, "index.html"));
        var missingIndex = RunBuilder(fixture.EdgeJar, fixture.UiDist, Path.Combine(this.temporaryDirectory, "index.zip"), false);
        StringAssert.Contains(missingIndex, "UI index.html does not exist");
    }

    [TestMethod]
    public void ExtractedVerifierReportsExactMutatedPath()
    {
        var fixture = this.CreateFixture();
        var archive = Path.Combine(this.temporaryDirectory, "payload.zip");
        var extracted = Path.Combine(this.temporaryDirectory, "extracted");
        RunBuilder(fixture.EdgeJar, fixture.UiDist, archive);
        ZipFile.ExtractToDirectory(archive, extracted);

        File.AppendAllText(Path.Combine(extracted, "ui", "assets", "app.js"), "mutated", Encoding.UTF8);
        var result = PayloadVerifier.VerifyExtractedDirectory(extracted);

        Assert.IsFalse(result.IsValid);
        Assert.AreEqual("ui/assets/app.js", result.Mismatches.Single().Path);
    }

    [TestMethod]
    public void VerifierRejectsUnsupportedManifestVersionAndTraversalEntry()
    {
        var fixture = this.CreateFixture();
        var archive = Path.Combine(this.temporaryDirectory, "payload.zip");
        var extracted = Path.Combine(this.temporaryDirectory, "version");
        RunBuilder(fixture.EdgeJar, fixture.UiDist, archive);
        ZipFile.ExtractToDirectory(archive, extracted);
        var manifestPath = Path.Combine(extracted, "manifest.json");
        var manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!;
        manifest["formatVersion"] = 999;
        File.WriteAllText(manifestPath, manifest.ToJsonString());
        var unsupported = PayloadVerifier.VerifyExtractedDirectory(extracted);
        StringAssert.Contains(unsupported.Mismatches.Single().Reason, "unsupported format version");

        var traversal = Path.Combine(this.temporaryDirectory, "traversal.zip");
        using (var malicious = ZipFile.Open(traversal, ZipArchiveMode.Create))
        {
            var entry = malicious.CreateEntry("../escape");
            using var writer = new StreamWriter(entry.Open());
            writer.Write("escape");
        }
        var rejected = PayloadVerifier.VerifyArchive(traversal);
        Assert.IsTrue(rejected.Mismatches.Any(item => item.Reason.Contains("unsafe archive path", StringComparison.Ordinal)));
    }

    [TestMethod]
    public void VerifierRejectsOversizedAndDuplicateManifestClaimsBeforeReadingFiles()
    {
        var fixture = this.CreateFixture();
        var archive = Path.Combine(this.temporaryDirectory, "payload.zip");
        var extracted = Path.Combine(this.temporaryDirectory, "limits");
        RunBuilder(fixture.EdgeJar, fixture.UiDist, archive);
        ZipFile.ExtractToDirectory(archive, extracted);
        var manifestPath = Path.Combine(extracted, "manifest.json");
        var manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!;
        manifest["files"]![0]!["length"] = 1_073_741_825L;
        File.WriteAllText(manifestPath, manifest.ToJsonString());

        var oversized = PayloadVerifier.VerifyExtractedDirectory(extracted);

        Assert.IsFalse(oversized.IsValid);
        StringAssert.Contains(oversized.Mismatches.Single().Reason, "outside the supported limit");

        manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!;
        manifest["files"]![0]!["length"] = 4;
        manifest["files"]!.AsArray().Add(manifest["files"]![0]!.DeepClone());
        File.WriteAllText(manifestPath, manifest.ToJsonString());
        var duplicate = PayloadVerifier.VerifyExtractedDirectory(extracted);
        Assert.IsFalse(duplicate.IsValid);
        Assert.IsTrue(duplicate.Mismatches.Any(item => item.Reason == "duplicate manifest path"));
    }

    private (string EdgeJar, string UiDist) CreateFixture()
    {
        var edge = Path.Combine(this.temporaryDirectory, "openems.jar");
        var ui = Path.Combine(this.temporaryDirectory, "dist");
        Directory.CreateDirectory(Path.Combine(ui, "assets"));
        File.WriteAllBytes(edge, [0x50, 0x4b, 0x03, 0x04]);
        File.WriteAllText(Path.Combine(ui, "index.html"), "<!doctype html><script src=\"/assets/app.js\"></script>");
        File.WriteAllText(Path.Combine(ui, "assets", "app.js"), "console.log('MAXiEMS');");
        return (edge, ui);
    }

    private static string RunBuilder(string edge, string ui, string output, bool expectSuccess = true)
    {
        var deployerRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.."));
        var startInfo = new System.Diagnostics.ProcessStartInfo
        {
            FileName = "powershell.exe",
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
        };
        string[] arguments = [
            "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", Path.Combine(deployerRoot, "build-payload.ps1"),
            "-EdgeJar", edge, "-UiDist", ui, "-Output", output,
            "-GitRevision", new string('a', 40), "-SourceTimestampUtc", "2026-07-18T02:00:00Z",
            "-BuildTimestampUtc", "2026-07-19T01:00:00Z",
        ];
        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }
        using var process = System.Diagnostics.Process.Start(startInfo)!;
        var standardOutput = process.StandardOutput.ReadToEnd();
        var standardError = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (expectSuccess)
        {
            Assert.AreEqual(0, process.ExitCode, standardError);
        }
        else
        {
            Assert.AreNotEqual(0, process.ExitCode, "Builder unexpectedly succeeded.");
        }
        return standardOutput + standardError;
    }

    private static string FormatMismatches(PayloadVerificationResult result) => string.Join(
        Environment.NewLine,
        result.Mismatches.Select(item => $"{item.Path}: {item.Reason}"));

}
