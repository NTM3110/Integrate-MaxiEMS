using System.IO.Compression;
using System.Text;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Payload;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

public sealed class ReleasePackageMaterializer(ReleasePackageImportOptions? options = null)
{
    private readonly ReleasePackageImportOptions limits = options ?? ReleasePackageImportOptions.Default;

    public async Task<string> MaterializeAsync(
        ReleaseCatalogSelection selection,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(selection);
        if (selection.Descriptor is not { Source: LocalReleaseSource.Imported, Status: LocalReleaseStatus.ImportedVerified })
        {
            throw new ReleasePackageException("Only an ImportedVerified release can be materialized from local cache.");
        }
        if (!File.Exists(selection.PackagePath))
        {
            throw new ReleasePackageException("The verified local release cache is missing; import the package again.");
        }
        var outerHash = await ReleasePackageImporter.HashFileAsync(selection.PackagePath, cancellationToken)
            .ConfigureAwait(false);
        if (!outerHash.Equals(selection.Descriptor.ArtifactSha256, StringComparison.Ordinal))
        {
            throw new ReleasePackageException("The cached release package was modified after import.");
        }
        var directory = Path.Combine(Path.GetTempPath(), "maxiems-deployer-payloads");
        Directory.CreateDirectory(directory);
        var payloadPath = Path.Combine(directory, $"payload-{Guid.NewGuid():N}.zip");
        try
        {
            using var archive = ZipFile.OpenRead(selection.PackagePath);
            if (archive.Entries.Count != 2)
                throw new ReleasePackageException("Cached release package layout changed.");
            var metadata = archive.GetEntry("release.json")
                ?? throw new ReleasePackageException("Cached release metadata is missing.");
            var payload = archive.GetEntry("payload.zip")
                ?? throw new ReleasePackageException("Cached release payload is missing.");
            if (metadata.Length > this.limits.MaximumMetadataBytes || payload.Length > this.limits.MaximumPayloadBytes)
            {
                throw new ReleasePackageException("Cached release exceeds its verified limits.");
            }
            string metadataJson;
            await using (var stream = metadata.Open())
            {
                using var memory = new MemoryStream();
                await stream.CopyToAsync(memory, cancellationToken).ConfigureAwait(false);
                metadataJson = Encoding.UTF8.GetString(memory.ToArray());
            }
            var manifest = ReleasePackageManifestParser.Parse(metadataJson);
            if (manifest.ReleaseId != selection.Descriptor.ReleaseId
                || !manifest.PayloadSha256.Equals(selection.Descriptor.PayloadSha256, StringComparison.Ordinal))
            {
                throw new ReleasePackageException("Cached release metadata no longer matches the frozen selection.");
            }
            await using (var source = payload.Open())
            await using (var target = new FileStream(payloadPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await source.CopyToAsync(target, cancellationToken).ConfigureAwait(false);
                await target.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            var innerHash = await ReleasePackageImporter.HashFileAsync(payloadPath, cancellationToken).ConfigureAwait(false);
            var verified = PayloadVerifier.VerifyArchive(payloadPath);
            if (!innerHash.Equals(selection.Descriptor.PayloadSha256, StringComparison.Ordinal)
                || !verified.IsValid || !selection.Descriptor.ReleaseId.Value.Equals(verified.ReleaseId,
                    StringComparison.Ordinal))
            {
                throw new ReleasePackageException("Cached payload failed verification.");
            }
            return payloadPath;
        }
        catch
        {
            if (File.Exists(payloadPath))
                File.Delete(payloadPath);
            throw;
        }
    }
}
