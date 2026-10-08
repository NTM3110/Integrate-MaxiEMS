using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Maxiems.Deployer.Infrastructure.Payload;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

public sealed class ReleasePackageImporter
{
    private readonly string cacheDirectory;
    private readonly LocalReleaseCatalog repository;
    private readonly ReleasePackageImportOptions options;
    private readonly TimeProvider clock;

    public ReleasePackageImporter(SqliteManufacturingStore store, string cacheDirectory,
        ReleasePackageImportOptions? options = null, TimeProvider? timeProvider = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(cacheDirectory);
        this.cacheDirectory = Path.GetFullPath(cacheDirectory);
        this.repository = new(store);
        this.options = options ?? ReleasePackageImportOptions.Default;
        this.clock = timeProvider ?? TimeProvider.System;
    }

    public async Task<ReleaseCatalogSelection> ImportAsync(
        string packagePath,
        ReleaseCompatibilityContext compatibility,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(packagePath);
        ArgumentNullException.ThrowIfNull(compatibility);
        Directory.CreateDirectory(this.cacheDirectory);
        var temporaryPackage = Path.Combine(this.cacheDirectory, $".import-{Guid.NewGuid():N}.tmp");
        var temporaryPayload = Path.Combine(this.cacheDirectory, $".payload-{Guid.NewGuid():N}.tmp");
        string? finalPath = null;
        var finalCreated = false;
        try
        {
            await CopyCappedAsync(Path.GetFullPath(packagePath), temporaryPackage, this.options.MaximumPackageBytes,
                cancellationToken).ConfigureAwait(false);
            var artifactHash = await HashFileAsync(temporaryPackage, cancellationToken).ConfigureAwait(false);
            if (await this.repository.ContainsArtifactAsync(artifactHash, cancellationToken).ConfigureAwait(false))
            {
                throw new ReleasePackageException("This exact release package is already in the local catalog.");
            }
            var parsed = await ReadAndVerifyAsync(temporaryPackage, temporaryPayload, this.options, cancellationToken)
                .ConfigureAwait(false);
            EnsureCompatible(parsed.Manifest, compatibility);
            var descriptor = new LocalReleaseDescriptor(parsed.Manifest.ReleaseId, parsed.Manifest.Version,
                LocalReleaseSource.Imported, LocalReleaseStatus.ImportedVerified, artifactHash,
                parsed.Manifest.PayloadSha256, parsed.Manifest.SupportedUbuntuVersions,
                parsed.Manifest.SupportedArchitectures, parsed.Manifest.MinimumDeployerVersion.ToString(3));
            finalPath = Path.Combine(this.cacheDirectory,
                $"{descriptor.ReleaseId.Value}-{artifactHash[..12]}.maxiems-release");
            File.Move(temporaryPackage, finalPath, false);
            finalCreated = true;
            try
            {
                await this.repository.AddAsync(descriptor, parsed.ManifestJson, finalPath, this.clock.GetUtcNow(),
                    cancellationToken).ConfigureAwait(false);
            }
            catch (SqliteException exception)
            {
                throw new ReleasePackageException("Release conflicts with an existing catalog entry.", exception);
            }
            return new(descriptor, finalPath);
        }
        catch (ReleasePackageException)
        {
            if (finalCreated && finalPath is not null && File.Exists(finalPath))
                File.Delete(finalPath);
            throw;
        }
        catch (Exception exception) when (exception is InvalidDataException or IOException or UnauthorizedAccessException)
        {
            if (finalCreated && finalPath is not null && File.Exists(finalPath))
                File.Delete(finalPath);
            throw new ReleasePackageException("Release package import failed safely.", exception);
        }
        catch
        {
            if (finalCreated && finalPath is not null && File.Exists(finalPath))
                File.Delete(finalPath);
            throw;
        }
        finally
        {
            if (File.Exists(temporaryPackage))
                File.Delete(temporaryPackage);
            if (File.Exists(temporaryPayload))
                File.Delete(temporaryPayload);
        }
    }

    private static async Task<ParsedPackage> ReadAndVerifyAsync(string packagePath, string payloadPath,
        ReleasePackageImportOptions limits, CancellationToken cancellationToken)
    {
        using var archive = ZipFile.OpenRead(packagePath);
        var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
        foreach (var entry in archive.Entries)
        {
            if (!IsSafeEntry(entry) || !entries.TryAdd(entry.FullName, entry))
            {
                throw new ReleasePackageException($"Unsafe or duplicate package entry '{entry.FullName}'.");
            }
        }
        if (entries.Count != 2 || !entries.TryGetValue("release.json", out var metadata)
            || !entries.TryGetValue("payload.zip", out var payload))
        {
            throw new ReleasePackageException("Package must contain only release.json and payload.zip.");
        }
        if (metadata.Length > limits.MaximumMetadataBytes || payload.Length > limits.MaximumPayloadBytes)
        {
            throw new ReleasePackageException("Release metadata or payload exceeds the configured size limit.");
        }
        var manifestJson = await ReadEntryAsync(metadata, limits.MaximumMetadataBytes, cancellationToken)
            .ConfigureAwait(false);
        var manifest = ReleasePackageManifestParser.Parse(manifestJson);
        await CopyEntryAsync(payload, payloadPath, limits.MaximumPayloadBytes, cancellationToken).ConfigureAwait(false);
        var payloadHash = await HashFileAsync(payloadPath, cancellationToken).ConfigureAwait(false);
        if (!payloadHash.Equals(manifest.PayloadSha256, StringComparison.Ordinal))
        {
            throw new ReleasePackageException("payload.zip SHA-256 does not match release metadata.");
        }
        var verification = PayloadVerifier.VerifyArchive(payloadPath);
        if (!verification.IsValid || !manifest.ReleaseId.Value.Equals(verification.ReleaseId, StringComparison.Ordinal))
        {
            throw new ReleasePackageException("The inner payload failed verification or has a different release ID.");
        }
        return new(manifest, manifestJson);
    }

    private static void EnsureCompatible(ReleasePackageManifest manifest, ReleaseCompatibilityContext context)
    {
        if (context.DeployerVersion < manifest.MinimumDeployerVersion)
        {
            throw new ReleasePackageException("This package requires a newer MAXiEMS Deployer version.");
        }
        if (!manifest.SupportedUbuntuVersions.Contains(context.UbuntuVersion, StringComparer.Ordinal))
        {
            throw new ReleasePackageException("This package does not support the selected Ubuntu version.");
        }
        if (!manifest.SupportedArchitectures.Contains(context.Architecture))
        {
            throw new ReleasePackageException("This package does not support the selected CPU architecture.");
        }
    }

    private static bool IsSafeEntry(ZipArchiveEntry entry)
    {
        var path = entry.FullName;
        var unixType = ((uint)entry.ExternalAttributes >> 16) & 0xF000;
        return path is "release.json" or "payload.zip" && entry.Name == path && unixType != 0xA000;
    }

    private static async Task<string> ReadEntryAsync(
        ZipArchiveEntry entry, long limit, CancellationToken cancellationToken)
    {
        await using var stream = entry.Open();
        using var memory = new MemoryStream(entry.Length <= int.MaxValue ? (int)entry.Length : 0);
        await CopyCappedAsync(stream, memory, limit, cancellationToken).ConfigureAwait(false);
        return Encoding.UTF8.GetString(memory.ToArray());
    }

    private static async Task CopyEntryAsync(
        ZipArchiveEntry entry, string destination, long limit, CancellationToken cancellationToken)
    {
        await using var source = entry.Open();
        await using var target = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920,
            FileOptions.Asynchronous | FileOptions.WriteThrough);
        await CopyCappedAsync(source, target, limit, cancellationToken).ConfigureAwait(false);
    }

    private static async Task CopyCappedAsync(
        string sourcePath, string destinationPath, long limit, CancellationToken cancellationToken)
    {
        await using var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read, 81920,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        await using var destination = new FileStream(destinationPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
            81920, FileOptions.Asynchronous | FileOptions.WriteThrough);
        await CopyCappedAsync(source, destination, limit, cancellationToken).ConfigureAwait(false);
    }

    private static async Task CopyCappedAsync(
        Stream source, Stream destination, long limit, CancellationToken cancellationToken)
    {
        var buffer = new byte[81920];
        long total = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) != 0)
        {
            if (total > limit - read)
                throw new ReleasePackageException("Release package exceeds the configured size limit.");
            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            total += read;
        }
        await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    internal static async Task<string> HashFileAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false))
            .ToLowerInvariant();
    }

    private sealed record ParsedPackage(ReleasePackageManifest Manifest, string ManifestJson);
}
