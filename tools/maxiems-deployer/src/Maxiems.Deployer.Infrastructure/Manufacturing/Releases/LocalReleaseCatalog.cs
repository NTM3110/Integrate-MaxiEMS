using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

public sealed class LocalReleaseCatalog(SqliteManufacturingStore store)
{
    internal async Task AddAsync(
        LocalReleaseDescriptor release,
        string manifestJson,
        string packagePath,
        DateTimeOffset importedAt,
        CancellationToken cancellationToken)
    {
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			INSERT INTO local_releases(release_id, source, status, manifest_json, artifact_sha256, payload_sha256,
				compatibility_json, cache_path, imported_utc)
			VALUES($release, $source, $status, $manifest, $artifact, $payload, $compatibility, $cache, $at)
			""";
        command.Parameters.AddWithValue("$release", release.ReleaseId.Value);
        command.Parameters.AddWithValue("$source", release.Source.ToString());
        command.Parameters.AddWithValue("$status", release.Status.ToString());
        command.Parameters.AddWithValue("$manifest", manifestJson);
        command.Parameters.AddWithValue("$artifact", release.ArtifactSha256);
        command.Parameters.AddWithValue("$payload", release.PayloadSha256);
        command.Parameters.AddWithValue("$compatibility", JsonSerializer.Serialize(new
        {
            release.SupportedUbuntuVersions,
            release.SupportedArchitectures,
            release.MinimumDeployerVersion,
        }));
        command.Parameters.AddWithValue("$cache", packagePath);
        command.Parameters.AddWithValue("$at", importedAt.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<ReleaseCatalogSelection?> FindAsync(
        ReleaseId releaseId,
        CancellationToken cancellationToken = default)
    {
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT source, status, manifest_json, artifact_sha256, payload_sha256, cache_path
			FROM local_releases WHERE release_id = $release
			""";
        command.Parameters.AddWithValue("$release", releaseId.Value);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            return null;
        var manifest = ReleasePackageManifestParser.Parse(reader.GetString(2));
        var descriptor = new LocalReleaseDescriptor(releaseId, manifest.Version,
            Enum.Parse<LocalReleaseSource>(reader.GetString(0)), Enum.Parse<LocalReleaseStatus>(reader.GetString(1)),
            reader.GetString(3), reader.GetString(4), manifest.SupportedUbuntuVersions,
            manifest.SupportedArchitectures, manifest.MinimumDeployerVersion.ToString(3));
        return new(descriptor, reader.GetString(5));
    }

    internal async Task<bool> ContainsArtifactAsync(string artifactSha256, CancellationToken cancellationToken)
    {
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT count(*) FROM local_releases WHERE artifact_sha256 = $hash";
        command.Parameters.AddWithValue("$hash", artifactSha256);
        return Convert.ToInt64(await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
            System.Globalization.CultureInfo.InvariantCulture) != 0;
    }
}
