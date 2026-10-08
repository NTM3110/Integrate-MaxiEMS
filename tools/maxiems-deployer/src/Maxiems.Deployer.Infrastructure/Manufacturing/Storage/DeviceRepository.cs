using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class DeviceRepository(SqliteManufacturingStore store) : IDeviceRepository, IDeviceIdentityRegistry
{
    public async Task<DeviceIdentityConflict> FindConflictAsync(DeviceSerial serial, MacAddress permanentMac,
        Guid? currentDeviceId = null, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(serial);
        ArgumentNullException.ThrowIfNull(permanentMac);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT
			 EXISTS(SELECT 1 FROM devices WHERE serial = $serial AND ($current IS NULL OR device_id != $current)),
			 EXISTS(SELECT 1 FROM device_ethernet_ports
				WHERE permanent_mac = $mac AND retired_utc IS NULL AND ($current IS NULL OR device_id != $current))
			""";
        command.Parameters.AddWithValue("$serial", serial.Value);
        command.Parameters.AddWithValue("$mac", permanentMac.Normalized);
        command.Parameters.AddWithValue("$current", currentDeviceId is Guid id ? id.ToString("D") : DBNull.Value);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            throw new InvalidDataException("Identity conflict query returned no row.");
        }
        return new(reader.GetBoolean(0), reader.GetBoolean(1));
    }

    public async Task RegisterAsync(
        DeviceRecord device,
        string evidenceSummary,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);
        ArgumentException.ThrowIfNullOrWhiteSpace(evidenceSummary);
        if (device.EthernetPorts.Length != 2 || device.EthernetPorts.Count(port => port.IsSerialSource) != 1)
        {
            throw new ArgumentException("A device must contain exactly two Ethernet ports and one serial source.",
                nameof(device));
        }
        var serialPort = device.EthernetPorts.Single(port => port.IsSerialSource);
        if (device.Serial != DeviceSerial.FromSerialSource(serialPort.PermanentMac))
        {
            throw new ArgumentException("Device serial must be derived from the serial-source permanent MAC.", nameof(device));
        }
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken)
            .ConfigureAwait(false);
        await InsertDeviceAsync(connection, transaction, device, cancellationToken).ConfigureAwait(false);
        foreach (var port in device.EthernetPorts)
        {
            await InsertPortAsync(connection, transaction, device.DeviceId, port, evidenceSummary, cancellationToken)
                .ConfigureAwait(false);
        }
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<DeviceRecord?> FindBySerialAsync(DeviceSerial serial,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(serial);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT device_id, lifecycle, profile_id, profile_version, asset_id, project_code, customer, site,
			 created_utc, updated_utc FROM devices WHERE serial=$serial
			""";
        command.Parameters.AddWithValue("$serial", serial.Value);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            return null;
        var id = Guid.Parse(reader.GetString(0));
        var state = Enum.Parse<DeviceLifecycleState>(reader.GetString(1));
        var profileId = reader.GetString(2);
        var profileVersion = reader.GetInt32(3);
        var asset = reader.IsDBNull(4) ? null : reader.GetString(4);
        var project = reader.IsDBNull(5) ? null : reader.GetString(5);
        var customer = reader.IsDBNull(6) ? null : reader.GetString(6);
        var site = reader.IsDBNull(7) ? null : reader.GetString(7);
        var created = DateTimeOffset.Parse(reader.GetString(8), System.Globalization.CultureInfo.InvariantCulture);
        var updated = DateTimeOffset.Parse(reader.GetString(9), System.Globalization.CultureInfo.InvariantCulture);
        await reader.DisposeAsync().ConfigureAwait(false);
        await using var hash = connection.CreateCommand();
        hash.CommandText = "SELECT sha256 FROM manufacturing_profiles WHERE profile_id=$id AND version=$version";
        hash.Parameters.AddWithValue("$id", profileId);
        hash.Parameters.AddWithValue("$version", profileVersion);
        var profileHash = (string)(await hash.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false))!;
        return new(id, serial, state, new(profileId, profileVersion, profileHash),
            await ReadPortsAsync(connection, id, cancellationToken).ConfigureAwait(false), asset, project, customer, site,
            created, updated);
    }

    public async Task UpdateLifecycleAsync(Guid deviceId, DeviceLifecycleState expected, DeviceLifecycleState targetState,
        DateTimeOffset updatedAt, CancellationToken cancellationToken = default)
    {
        DeviceLifecyclePolicy.EnsureTransition(expected, targetState);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			UPDATE devices SET lifecycle=$next, updated_utc=$updated
			WHERE device_id=$device AND lifecycle=$expected
			""";
        command.Parameters.AddWithValue("$next", targetState.ToString());
        command.Parameters.AddWithValue("$updated", updatedAt.ToString("O"));
        command.Parameters.AddWithValue("$device", deviceId.ToString("D"));
        command.Parameters.AddWithValue("$expected", expected.ToString());
        if (await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) != 1)
        {
            throw new InvalidOperationException("Device lifecycle changed concurrently; reload the device.");
        }
    }

    private static async Task<System.Collections.Immutable.ImmutableArray<DeviceEthernetPortRecord>> ReadPortsAsync(
        SqliteConnection connection, Guid deviceId, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT physical_label, linux_name, permanent_mac, current_mac, is_serial_source, observed_utc
			FROM device_ethernet_ports WHERE device_id=$device AND retired_utc IS NULL ORDER BY physical_label
			""";
        command.Parameters.AddWithValue("$device", deviceId.ToString("D"));
        var ports = System.Collections.Immutable.ImmutableArray.CreateBuilder<DeviceEthernetPortRecord>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            ports.Add(new(reader.GetString(0),
            reader.GetString(1), new(reader.GetString(2)), new(reader.GetString(3)), reader.GetBoolean(4),
            DateTimeOffset.Parse(reader.GetString(5), System.Globalization.CultureInfo.InvariantCulture)));
        return ports.ToImmutable();
    }

    private static async Task InsertDeviceAsync(SqliteConnection connection, SqliteTransaction transaction,
        DeviceRecord device, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
			INSERT INTO devices(device_id, serial, lifecycle, profile_id, profile_version, asset_id,
				project_code, customer, site, created_utc, updated_utc)
			VALUES($device, $serial, $lifecycle, $profile, $version, $asset, $project, $customer, $site, $created, $updated)
			""";
        command.Parameters.AddWithValue("$device", device.DeviceId.ToString("D"));
        command.Parameters.AddWithValue("$serial", device.Serial.Value);
        command.Parameters.AddWithValue("$lifecycle", device.State.ToString());
        command.Parameters.AddWithValue("$profile", device.Profile.ProfileId);
        command.Parameters.AddWithValue("$version", device.Profile.Version);
        command.Parameters.AddWithValue("$asset", (object?)device.AssetId ?? DBNull.Value);
        command.Parameters.AddWithValue("$project", (object?)device.ProjectCode ?? DBNull.Value);
        command.Parameters.AddWithValue("$customer", (object?)device.Customer ?? DBNull.Value);
        command.Parameters.AddWithValue("$site", (object?)device.Site ?? DBNull.Value);
        command.Parameters.AddWithValue("$created", device.CreatedAt.ToString("O"));
        command.Parameters.AddWithValue("$updated", device.UpdatedAt.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task InsertPortAsync(SqliteConnection connection, SqliteTransaction transaction, Guid deviceId,
        DeviceEthernetPortRecord port, string evidenceSummary, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
			INSERT INTO device_ethernet_ports(device_id, physical_label, linux_name, permanent_mac,
				current_mac, evidence_summary, is_serial_source, observed_utc)
			VALUES($device, $label, $linux, $permanent, $current, $evidence, $source, $observed)
			""";
        command.Parameters.AddWithValue("$device", deviceId.ToString("D"));
        command.Parameters.AddWithValue("$label", port.PhysicalLabel);
        command.Parameters.AddWithValue("$linux", port.LinuxName);
        command.Parameters.AddWithValue("$permanent", port.PermanentMac.Normalized);
        command.Parameters.AddWithValue("$current", port.CurrentMac.Normalized);
        command.Parameters.AddWithValue("$evidence", evidenceSummary);
        command.Parameters.AddWithValue("$source", port.IsSerialSource ? 1 : 0);
        command.Parameters.AddWithValue("$observed", port.ObservedAt.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }
}
