using System.Text.Json;
using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class ManufacturingProfileRepository(SqliteManufacturingStore store) : IManufacturingProfileRepository
{
    public async Task<ManufacturingProfile> AddAsync(
        string canonicalJson,
        string createdBy,
        DateTimeOffset createdAt,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(canonicalJson);
        ArgumentException.ThrowIfNullOrWhiteSpace(createdBy);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        var profile = ManufacturingProfileParser.Parse(canonicalJson);
        using var document = JsonDocument.Parse(canonicalJson);
        var normalizedJson = JsonSerializer.Serialize(document.RootElement);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			INSERT INTO manufacturing_profiles(
				profile_id, version, state, canonical_json, sha256, display_name, product_family,
				hardware_variant, change_note, created_utc, created_by)
			VALUES($id, $version, $state, $json, $hash, $name, $family, $variant, $note, $at, $by)
			""";
        command.Parameters.AddWithValue("$id", profile.Reference.ProfileId);
        command.Parameters.AddWithValue("$version", profile.Reference.Version);
        command.Parameters.AddWithValue("$state", profile.State.ToString());
        command.Parameters.AddWithValue("$json", normalizedJson);
        command.Parameters.AddWithValue("$hash", profile.Reference.Sha256);
        command.Parameters.AddWithValue("$name", profile.DisplayName);
        command.Parameters.AddWithValue("$family", profile.ProductFamily);
        command.Parameters.AddWithValue("$variant", profile.HardwareVariant);
        command.Parameters.AddWithValue("$note", profile.ChangeNote);
        command.Parameters.AddWithValue("$at", createdAt.ToString("O"));
        command.Parameters.AddWithValue("$by", createdBy);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        return profile;
    }

    public async Task<string?> FindCanonicalJsonAsync(
        ManufacturingProfileReference reference,
        CancellationToken cancellationToken = default)
    {
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT canonical_json FROM manufacturing_profiles
			WHERE profile_id = $id AND version = $version AND sha256 = $hash
			""";
        command.Parameters.AddWithValue("$id", reference.ProfileId);
        command.Parameters.AddWithValue("$version", reference.Version);
        command.Parameters.AddWithValue("$hash", reference.Sha256);
        return await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false) as string;
    }
}
