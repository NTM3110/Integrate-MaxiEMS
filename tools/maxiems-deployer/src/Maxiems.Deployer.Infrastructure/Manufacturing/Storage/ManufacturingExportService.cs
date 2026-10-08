using System.Globalization;
using System.Text;
using System.Text.Json;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed record ManufacturingExportResult(string JsonPath, string CsvPath, int DeviceCount, int RunCount);

public sealed class ManufacturingExportService(SqliteManufacturingStore store, TimeProvider? timeProvider = null)
{
    private static readonly JsonSerializerOptions ExportJsonOptions = new() { WriteIndented = true };
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public async Task<IReadOnlyList<string>> ExportProfilesAsync(string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        var directory = RequireDirectory(destinationDirectory);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT profile_id, version, canonical_json FROM manufacturing_profiles ORDER BY profile_id, version";
        var paths = new List<string>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var id = SafeFileName(reader.GetString(0));
            var path = Path.Combine(directory, $"profile-{id}-v{reader.GetInt32(1)}.json");
            await WriteAtomicAsync(path, reader.GetString(2), cancellationToken).ConfigureAwait(false);
            paths.Add(path);
        }
        return paths;
    }

    public async Task<ManufacturingExportResult> ExportDeviceHistoryAsync(string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        var directory = RequireDirectory(destinationDirectory);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var devices = await ReadAsync(connection, """
			SELECT d.device_id, d.serial, d.lifecycle, d.profile_id, d.profile_version, d.created_utc, d.updated_utc,
			 group_concat(e.physical_label || '=' || e.linux_name || ':' || e.permanent_mac, ';') AS ethernet
			FROM devices d LEFT JOIN device_ethernet_ports e ON e.device_id=d.device_id AND e.retired_utc IS NULL
			GROUP BY d.device_id ORDER BY d.serial
			""", cancellationToken).ConfigureAwait(false);
        var runs = await ReadAsync(connection, """
			SELECT run_id, device_id, inspection_id, endpoint, release_id, planned_action, observed_action,
			 final_state, started_utc, completed_utc, json_report_sha256, html_report_sha256
			FROM deployment_runs ORDER BY started_utc
			""", cancellationToken).ConfigureAwait(false);
        var stamp = this.clock.GetUtcNow().ToString("yyyyMMdd-HHmmssfff", CultureInfo.InvariantCulture);
        var jsonPath = Path.Combine(directory, $"maxiems-device-history-{stamp}.json");
        var csvPath = Path.Combine(directory, $"maxiems-devices-{stamp}.csv");
        var json = JsonSerializer.Serialize(new
        {
            formatVersion = 1,
            exportedAt = this.clock.GetUtcNow(),
            devices,
            runs
        },
            ExportJsonOptions);
        await WriteAtomicAsync(jsonPath, json, cancellationToken).ConfigureAwait(false);
        await WriteAtomicAsync(csvPath, Csv(devices), cancellationToken).ConfigureAwait(false);
        return new(jsonPath, csvPath, devices.Count, runs.Count);
    }

    private static async Task<List<Dictionary<string, object?>>> ReadAsync(
        Microsoft.Data.Sqlite.SqliteConnection connection, string sql, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        var rows = new List<Dictionary<string, object?>>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var row = new Dictionary<string, object?>(StringComparer.Ordinal);
            for (var index = 0; index < reader.FieldCount; index++)
            {
                row[reader.GetName(index)] = reader.IsDBNull(index) ? null : reader.GetValue(index);
            }
            rows.Add(row);
        }
        return rows;
    }

    private static string Csv(List<Dictionary<string, object?>> rows)
    {
        if (rows.Count == 0)
            return "device_id,serial,lifecycle,profile_id,profile_version,created_utc,updated_utc,ethernet\r\n";
        var columns = rows[0].Keys.ToArray();
        var result = new StringBuilder().AppendLine(string.Join(',', columns.Select(Escape)));
        foreach (var row in rows)
            result.AppendLine(string.Join(',', columns.Select(column =>
            Escape(Convert.ToString(row[column], CultureInfo.InvariantCulture) ?? string.Empty))));
        return result.ToString();
    }

    private static string Escape(string value) => value.IndexOfAny([',', '"', '\r', '\n']) >= 0
        ? $"\"{value.Replace("\"", "\"\"", StringComparison.Ordinal)}\"" : value;
    private static string RequireDirectory(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        var directory = Path.GetFullPath(value);
        Directory.CreateDirectory(directory);
        return directory;
    }
    private static string SafeFileName(string value) => string.Concat(value.Select(character =>
        Path.GetInvalidFileNameChars().Contains(character) ? '_' : character));
    private static async Task WriteAtomicAsync(string path, string value, CancellationToken cancellationToken)
    {
        var temporary = path + $".{Guid.NewGuid():N}.tmp";
        try
        {
            await File.WriteAllTextAsync(temporary, value, new UTF8Encoding(false), cancellationToken).ConfigureAwait(false);
            File.Move(temporary, path, true);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }
}
