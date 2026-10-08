using System.Collections.Immutable;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class ManufacturingOperationsRepository(SqliteManufacturingStore store)
    : IManufacturingOperationsRepository
{
    public async Task<ManufacturingDashboardCounts> GetDashboardAsync(
        CancellationToken cancellationToken = default)
    {
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT
			 (SELECT count(*) FROM manufacturing_profiles),
			 (SELECT count(*) FROM manufacturing_profiles WHERE state='Approved'),
			 (SELECT count(*) FROM devices),
			 (SELECT count(*) FROM devices WHERE lifecycle='ReadyForWarehouse'),
			 (SELECT count(*) FROM devices WHERE lifecycle='ReworkRequired'),
			 (SELECT count(*) FROM deployment_runs WHERE date(started_utc)=date('now'))
			""";
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            throw new InvalidDataException("Manufacturing dashboard query returned no row.");
        }
        return new(reader.GetInt32(0), reader.GetInt32(1), reader.GetInt32(2), reader.GetInt32(3),
            reader.GetInt32(4), reader.GetInt32(5));
    }

    public async Task<IReadOnlyList<ManufacturingProfile>> ListProfilesAsync(
        CancellationToken cancellationToken = default)
    {
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT canonical_json FROM manufacturing_profiles ORDER BY display_name, version DESC";
        var profiles = new List<ManufacturingProfile>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            profiles.Add(ManufacturingProfileParser.Parse(reader.GetString(0)));
        }
        return profiles;
    }

    public async Task<IReadOnlyList<ManufacturingDeviceSummary>> SearchDevicesAsync(string? query, int limit = 100,
        CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > 1000)
            throw new ArgumentOutOfRangeException(nameof(limit));
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT d.device_id, d.serial, d.lifecycle, d.profile_id, d.profile_version, p.sha256, d.updated_utc
			FROM devices d JOIN manufacturing_profiles p
			 ON p.profile_id=d.profile_id AND p.version=d.profile_version
			WHERE $query='' OR lower(d.serial) LIKE $like OR EXISTS(
			 SELECT 1 FROM device_ethernet_ports e WHERE e.device_id=d.device_id
			 AND (lower(e.permanent_mac) LIKE $like OR lower(e.current_mac) LIKE $like))
			ORDER BY d.updated_utc DESC LIMIT $limit
			""";
        var normalized = query?.Trim().ToLowerInvariant() ?? string.Empty;
        command.Parameters.AddWithValue("$query", normalized);
        command.Parameters.AddWithValue("$like", $"%{normalized}%");
        command.Parameters.AddWithValue("$limit", limit);
        var rows = new List<DeviceRow>();
        await using (var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false))
        {
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
                rows.Add(new(
                Guid.Parse(reader.GetString(0)), new(reader.GetString(1)), Enum.Parse<DeviceLifecycleState>(reader.GetString(2)),
                new(reader.GetString(3), reader.GetInt32(4), reader.GetString(5)),
                DateTimeOffset.Parse(reader.GetString(6), System.Globalization.CultureInfo.InvariantCulture)));
        }
        var result = new List<ManufacturingDeviceSummary>();
        foreach (var row in rows)
            result.Add(new(row.Id, row.Serial, row.State, row.Profile,
            await ReadPortsAsync(connection, row.Id, cancellationToken).ConfigureAwait(false), row.UpdatedAt));
        return result;
    }

    private static async Task<ImmutableArray<DeviceEthernetPortRecord>> ReadPortsAsync(
        Microsoft.Data.Sqlite.SqliteConnection connection, Guid deviceId, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT physical_label, linux_name, permanent_mac, current_mac, is_serial_source, observed_utc
			FROM device_ethernet_ports WHERE device_id=$device AND retired_utc IS NULL ORDER BY physical_label
			""";
        command.Parameters.AddWithValue("$device", deviceId.ToString("D"));
        var ports = ImmutableArray.CreateBuilder<DeviceEthernetPortRecord>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            ports.Add(new(reader.GetString(0),
            reader.GetString(1), new(reader.GetString(2)), new(reader.GetString(3)), reader.GetBoolean(4),
            DateTimeOffset.Parse(reader.GetString(5), System.Globalization.CultureInfo.InvariantCulture)));
        return ports.ToImmutable();
    }

    private sealed record DeviceRow(Guid Id, DeviceSerial Serial, DeviceLifecycleState State,
        ManufacturingProfileReference Profile, DateTimeOffset UpdatedAt);
}
