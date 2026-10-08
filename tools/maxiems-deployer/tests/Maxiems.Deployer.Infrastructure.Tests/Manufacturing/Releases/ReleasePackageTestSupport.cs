using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Releases;

internal static class ReleasePackageTestSupport
{
    public const string ReleaseId = "20260722080000-123456789abc";
    public static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = false };

    public static string CreatePackage(
        string directory,
        string fileName = "release.maxiems-release",
        Action<JsonObject>? mutateMetadata = null,
        Action<ZipArchive>? mutatePackage = null,
        string releaseId = ReleaseId,
        string payloadContent = "verified payload")
    {
        var payload = Path.Combine(directory, $"payload-{Guid.NewGuid():N}.zip");
        CreatePayload(payload, releaseId, payloadContent);
        var payloadHash = Hash(File.ReadAllBytes(payload));
        var metadata = new JsonObject
        {
            ["schemaVersion"] = 1,
            ["releaseId"] = releaseId,
            ["version"] = "1.0.0",
            ["payloadSha256"] = payloadHash,
            ["supportedUbuntuVersions"] = new JsonArray("24.04"),
            ["supportedArchitectures"] = new JsonArray("arm64"),
            ["minimumDeployerVersion"] = "1.0.0",
        };
        mutateMetadata?.Invoke(metadata);
        var package = Path.Combine(directory, fileName);
        using (var archive = ZipFile.Open(package, ZipArchiveMode.Create))
        {
            WriteEntry(archive, "release.json", metadata.ToJsonString(JsonOptions));
            var payloadEntry = archive.CreateEntry("payload.zip", CompressionLevel.NoCompression);
            using (var target = payloadEntry.Open())
            using (var source = File.OpenRead(payload))
            {
                source.CopyTo(target);
            }
        }
        if (mutatePackage is not null)
        {
            using var archive = ZipFile.Open(package, ZipArchiveMode.Update);
            mutatePackage(archive);
        }
        File.Delete(payload);
        return package;
    }

    public static void CreatePayload(string path, string releaseId, string content)
    {
        var bytes = Encoding.UTF8.GetBytes(content);
        var manifest = new
        {
            formatVersion = 1,
            releaseId,
            files = new[] { new { path = "content.txt", length = bytes.LongLength, sha256 = Hash(bytes) } },
        };
        using var archive = ZipFile.Open(path, ZipArchiveMode.Create);
        WriteEntry(archive, "content.txt", content);
        WriteEntry(archive, "manifest.json", JsonSerializer.Serialize(manifest, JsonOptions));
    }

    private static void WriteEntry(ZipArchive archive, string path, string content)
    {
        var entry = archive.CreateEntry(path, CompressionLevel.NoCompression);
        using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
        writer.Write(content);
    }

    private static string Hash(byte[] value) =>
        Convert.ToHexString(SHA256.HashData(value)).ToLowerInvariant();
}
