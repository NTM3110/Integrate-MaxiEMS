using System.Collections.Immutable;
using System.Globalization;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Maxiems.Deployer.Infrastructure.Payload;

public static class PayloadVerifier
{
    private const int SupportedFormatVersion = 2;
    private const int MaximumPayloadFiles = 10_000;
    private const long MaximumArchiveBytes = 2L * 1024 * 1024 * 1024;
    private const long MaximumFileBytes = 1024L * 1024 * 1024;
    private const long MaximumExtractedBytes = 4L * 1024 * 1024 * 1024;
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public static PayloadVerificationResult VerifyArchive(string archivePath)
    {
        if (new FileInfo(archivePath).Length > MaximumArchiveBytes)
        {
            var oversized = ImmutableArray.CreateBuilder<PayloadMismatch>();
            oversized.Add(new(Path.GetFileName(archivePath), "archive exceeds the 2 GiB limit"));
            return Invalid(oversized);
        }
        string archiveSha256;
        using (var archiveStream = File.OpenRead(archivePath))
        {
            archiveSha256 = Convert.ToHexString(SHA256.HashData(archiveStream)).ToLowerInvariant();
        }
        using var archive = ZipFile.OpenRead(archivePath);
        var mismatches = ImmutableArray.CreateBuilder<PayloadMismatch>();
        if (archive.Entries.Count > MaximumPayloadFiles + 1)
        {
            mismatches.Add(new("manifest.json", "archive contains too many entries"));
            return Invalid(mismatches);
        }
        var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
        foreach (var entry in archive.Entries)
        {
            if (!IsSafeRelativePath(entry.FullName))
            {
                mismatches.Add(new(entry.FullName, "unsafe archive path"));
                continue;
            }
            if (!entries.TryAdd(entry.FullName, entry))
            {
                mismatches.Add(new(entry.FullName, "duplicate archive entry"));
            }
        }

        if (!entries.TryGetValue("manifest.json", out var manifestEntry))
        {
            mismatches.Add(new("manifest.json", "missing"));
            return Invalid(mismatches);
        }

        PayloadManifest? manifest;
        try
        {
            using var manifestStream = manifestEntry.Open();
            manifest = JsonSerializer.Deserialize<PayloadManifest>(manifestStream, SerializerOptions);
        }
        catch (JsonException exception)
        {
            mismatches.Add(new("manifest.json", $"invalid JSON: {exception.Message}"));
            return Invalid(mismatches);
        }

        if (manifest is null)
        {
            mismatches.Add(new("manifest.json", "empty manifest"));
            return Invalid(mismatches);
        }
        if (manifest.FormatVersion != SupportedFormatVersion)
        {
            mismatches.Add(new("manifest.json", $"unsupported format version {manifest.FormatVersion}"));
            return Invalid(mismatches);
        }
        if (!ValidateLimits(manifest, mismatches))
        {
            return Invalid(mismatches);
        }
        var payloadHash = CanonicalPayloadHash(manifest.Files);
        ValidateDeclaredPayloadHash(manifest, payloadHash, mismatches);
        var releaseId = DeriveAndValidateReleaseId(manifest, payloadHash, mismatches);

        var expected = new HashSet<string>(StringComparer.Ordinal) { "manifest.json" };
        foreach (var file in manifest.Files)
        {
            if (!expected.Add(file.Path))
            {
                mismatches.Add(new(file.Path, "duplicate manifest path"));
                continue;
            }
            if (!IsSafeRelativePath(file.Path))
            {
                mismatches.Add(new(file.Path, "unsafe manifest path"));
                continue;
            }
            if (!entries.TryGetValue(file.Path, out var entry))
            {
                mismatches.Add(new(file.Path, "missing"));
                continue;
            }
            if (entry.Length != file.Length)
            {
                mismatches.Add(new(file.Path, $"length mismatch: expected {file.Length}, got {entry.Length}"));
                continue;
            }
            using var stream = entry.Open();
            var actualHash = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            if (!actualHash.Equals(file.Sha256, StringComparison.Ordinal))
            {
                mismatches.Add(new(file.Path, "SHA-256 mismatch"));
            }
        }

        foreach (var extra in entries.Keys.Except(expected, StringComparer.Ordinal))
        {
            mismatches.Add(new(extra, "unexpected archive entry"));
        }

        return new(mismatches.Count == 0, releaseId, mismatches.ToImmutable(), archiveSha256);
    }

    public static PayloadVerificationResult VerifyExtractedDirectory(string rootPath)
    {
        var root = Path.GetFullPath(rootPath);
        var manifestPath = Path.Combine(root, "manifest.json");
        if (!File.Exists(manifestPath))
        {
            return Invalid(ImmutableArray.CreateBuilder<PayloadMismatch>().AddAndReturn(
                new("manifest.json", "missing")));
        }

        PayloadManifest? manifest;
        try
        {
            manifest = JsonSerializer.Deserialize<PayloadManifest>(File.ReadAllText(manifestPath), SerializerOptions);
        }
        catch (JsonException exception)
        {
            var invalidJson = ImmutableArray.CreateBuilder<PayloadMismatch>();
            invalidJson.Add(new("manifest.json", $"invalid JSON: {exception.Message}"));
            return Invalid(invalidJson);
        }
        var mismatches = ImmutableArray.CreateBuilder<PayloadMismatch>();
        if (manifest is null || manifest.FormatVersion != SupportedFormatVersion)
        {
            mismatches.Add(new("manifest.json", manifest is null
                ? "empty manifest"
                : $"unsupported format version {manifest.FormatVersion}"));
            return Invalid(mismatches);
        }
        if (!ValidateLimits(manifest, mismatches))
        {
            return Invalid(mismatches);
        }
        var payloadHash = CanonicalPayloadHash(manifest.Files);
        ValidateDeclaredPayloadHash(manifest, payloadHash, mismatches);
        var releaseId = DeriveAndValidateReleaseId(manifest, payloadHash, mismatches);

        foreach (var file in manifest.Files)
        {
            if (!TryResolveUnderRoot(root, file.Path, out var path))
            {
                mismatches.Add(new(file.Path, "unsafe manifest path"));
                continue;
            }
            if (!File.Exists(path))
            {
                mismatches.Add(new(file.Path, "missing"));
                continue;
            }
            var info = new FileInfo(path);
            if (info.Length != file.Length)
            {
                mismatches.Add(new(file.Path, $"length mismatch: expected {file.Length}, got {info.Length}"));
                continue;
            }
            using var stream = File.OpenRead(path);
            var actualHash = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            if (!actualHash.Equals(file.Sha256, StringComparison.Ordinal))
            {
                mismatches.Add(new(file.Path, "SHA-256 mismatch"));
            }
        }

        return new(mismatches.Count == 0, releaseId, mismatches.ToImmutable());
    }

    private static string? DeriveAndValidateReleaseId(
        PayloadManifest manifest,
        string payloadHash,
        ImmutableArray<PayloadMismatch>.Builder mismatches)
    {
        const string timestampFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";
        if (!DateTimeOffset.TryParseExact(manifest.BuildTimestampUtc, timestampFormat,
                CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var buildTimestamp))
        {
            mismatches.Add(new("manifest.json", "buildTimestampUtc must be a whole-second UTC timestamp"));
            return null;
        }
        var derived = $"{buildTimestamp:yyyyMMddHHmmss}-{payloadHash[..12]}";
        if (!string.Equals(manifest.ReleaseId, derived, StringComparison.Ordinal))
        {
            mismatches.Add(new("manifest.json", "releaseId does not match build timestamp and canonical payload hash"));
        }
        return derived;
    }

    private static string CanonicalPayloadHash(PayloadFile[] files)
    {
        var canonical = new StringBuilder();
        foreach (var file in files.OrderBy(file => file.Path, StringComparer.Ordinal))
        {
            canonical.Append(file.Path).Append('\0').Append(file.Length).Append('\0')
                .Append(file.Sha256).Append('\n');
        }
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical.ToString()))).ToLowerInvariant();
    }

    private static void ValidateDeclaredPayloadHash(PayloadManifest manifest, string computed,
        ImmutableArray<PayloadMismatch>.Builder mismatches)
    {
        if (manifest.PayloadHash is null || !manifest.PayloadHash.Equals(computed, StringComparison.Ordinal))
        {
            mismatches.Add(new("manifest.json", "canonical payload hash mismatch"));
        }
    }

    private static bool TryResolveUnderRoot(string root, string relativePath, out string resolved)
    {
        resolved = string.Empty;
        if (!IsSafeRelativePath(relativePath))
        {
            return false;
        }
        resolved = Path.GetFullPath(Path.Combine(root, relativePath.Replace('/', Path.DirectorySeparatorChar)));
        return resolved.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsSafeRelativePath(string path) => !string.IsNullOrWhiteSpace(path)
        && !Path.IsPathRooted(path)
        && !path.Contains('\\')
        && !path.Split('/').Contains("..", StringComparer.Ordinal)
        && !path.EndsWith('/');

    private static bool ValidateLimits(
        PayloadManifest manifest,
        ImmutableArray<PayloadMismatch>.Builder mismatches)
    {
        if (manifest.Files is null)
        {
            mismatches.Add(new("manifest.json", "manifest files array is missing"));
            return false;
        }
        if (manifest.Files.Length > MaximumPayloadFiles)
        {
            mismatches.Add(new("manifest.json", "manifest contains too many files"));
            return false;
        }
        long total = 0;
        var paths = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in manifest.Files)
        {
            if (!paths.Add(file.Path))
            {
                mismatches.Add(new(file.Path, "duplicate manifest path"));
                return false;
            }
            if (file.Length < 0 || file.Length > MaximumFileBytes)
            {
                mismatches.Add(new(file.Path, "file length is outside the supported limit"));
                return false;
            }
            if (total > MaximumExtractedBytes - file.Length)
            {
                mismatches.Add(new("manifest.json", "expanded payload exceeds the 4 GiB limit"));
                return false;
            }
            total += file.Length;
        }
        return true;
    }

    private static PayloadVerificationResult Invalid(
        ImmutableArray<PayloadMismatch>.Builder mismatches,
        string? releaseId = null) => new(false, releaseId, mismatches.ToImmutable());

    private sealed record PayloadManifest(
        int FormatVersion,
        string? ReleaseId,
        string? BuildTimestampUtc,
        string? PayloadHash,
        PayloadFile[] Files);

    private sealed record PayloadFile(string Path, long Length, string Sha256);

    private static ImmutableArray<T>.Builder AddAndReturn<T>(
        this ImmutableArray<T>.Builder builder,
        T item)
    {
        builder.Add(item);
        return builder;
    }
}
