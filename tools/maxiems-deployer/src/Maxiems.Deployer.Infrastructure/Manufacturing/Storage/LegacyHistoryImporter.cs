using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class LegacyHistoryImporter(SqliteManufacturingStore store, TimeProvider? timeProvider = null)
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = false,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public async Task<LegacyHistoryImportResult> ImportAsync(
        string historyPath,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(historyPath);
        var fullPath = Path.GetFullPath(historyPath);
        if (!File.Exists(fullPath))
            return new(false, false, 0, null);
        var sourceKey = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(fullPath))).ToLowerInvariant();
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var existingBackup = await FindMarkerAsync(connection, sourceKey, cancellationToken).ConfigureAwait(false);
        if (existingBackup is not null)
            return new(true, true, 0, existingBackup);

        await using var stream = File.OpenRead(fullPath);
        var document = await JsonSerializer.DeserializeAsync<HistoryDocument>(stream, JsonOptions, cancellationToken)
            .ConfigureAwait(false);
        if (document is null || document.FormatVersion != 1 || document.Entries is null
            || document.Entries.GroupBy(entry => entry.DeploymentId).Any(group => group.Count() > 1))
        {
            throw new InvalidDataException("Legacy deployment history is not valid format 1 data.");
        }
        var backup = CreateSourceBackup(fullPath);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken)
            .ConfigureAwait(false);
        foreach (var entry in document.Entries)
        {
            await InsertLegacyRunAsync(connection, transaction, entry, cancellationToken).ConfigureAwait(false);
        }
        await using (var marker = connection.CreateCommand())
        {
            marker.Transaction = transaction;
            marker.CommandText = """
				INSERT INTO import_markers(source_key, completed_utc, backup_path) VALUES($key, $at, $backup)
				""";
            marker.Parameters.AddWithValue("$key", sourceKey);
            marker.Parameters.AddWithValue("$at", this.clock.GetUtcNow().ToString("O"));
            marker.Parameters.AddWithValue("$backup", backup);
            await marker.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
        return new(true, false, document.Entries.Length, backup);
    }

    private string CreateSourceBackup(string sourcePath)
    {
        var timestamp = this.clock.GetUtcNow().ToString("yyyyMMddHHmmssfff",
            System.Globalization.CultureInfo.InvariantCulture);
        var backup = $"{sourcePath}.imported-{timestamp}.bak";
        var temporary = $"{backup}.{Guid.NewGuid():N}.tmp";
        File.Copy(sourcePath, temporary, false);
        File.Move(temporary, backup, false);
        return backup;
    }

    private static async Task<string?> FindMarkerAsync(
        SqliteConnection connection, string key, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT backup_path FROM import_markers WHERE source_key = $key";
        command.Parameters.AddWithValue("$key", key);
        return await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false) as string;
    }

    private static async Task InsertLegacyRunAsync(SqliteConnection connection, SqliteTransaction transaction,
        DeploymentHistorySummary entry, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
			INSERT OR IGNORE INTO deployment_runs(run_id, device_id, inspection_id, endpoint, release_id, action,
				final_state, started_utc, completed_utc, duration_ms, json_report_path, html_report_path,
				legacy_unassigned, engineer_user, workstation)
			VALUES($run, NULL, NULL, $endpoint, $release, 'LegacyUnassigned', $state, $started, $completed,
				$duration, $json, $html, 1, NULL, NULL)
			""";
        command.Parameters.AddWithValue("$run", entry.DeploymentId.ToString("D"));
        command.Parameters.AddWithValue("$endpoint", entry.TargetDisplayName);
        command.Parameters.AddWithValue("$release", entry.ReleaseId.Value);
        command.Parameters.AddWithValue("$state", entry.FinalState.ToString());
        command.Parameters.AddWithValue("$started", entry.StartedAt.ToString("O"));
        command.Parameters.AddWithValue("$completed", entry.CompletedAt.ToString("O"));
        command.Parameters.AddWithValue("$duration", (long)entry.Duration.TotalMilliseconds);
        command.Parameters.AddWithValue("$json", entry.Reports.JsonReportPath);
        command.Parameters.AddWithValue("$html", entry.Reports.HtmlReportPath);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private sealed record HistoryDocument(int FormatVersion, DeploymentHistorySummary[] Entries);
}
