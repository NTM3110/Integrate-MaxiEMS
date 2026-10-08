using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Reporting;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class DeploymentRunRepository(SqliteManufacturingStore store) : IDeploymentRunRepository
{
    public async Task AppendAsync(StoredDeploymentRun run, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(run);
        if (run.CompletedAt < run.StartedAt)
        {
            throw new ArgumentException("Deployment completion cannot precede its start.", nameof(run));
        }
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (Microsoft.Data.Sqlite.SqliteTransaction)await connection
            .BeginTransactionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
			INSERT INTO deployment_runs(run_id, device_id, inspection_id, endpoint, release_id, action,
				final_state, started_utc, completed_utc, duration_ms, json_report_path, html_report_path,
				legacy_unassigned, engineer_user, workstation, planned_action, observed_action,
				json_report_sha256, html_report_sha256, report_format_version)
			VALUES($run, $device, $inspection, $endpoint, $release, $action, $state, $started, $completed,
				$duration, $json, $html, 0, $engineer, $workstation, $planned, $observed, $jsonHash, $htmlHash, $format)
			""";
        command.Parameters.AddWithValue("$run", run.RunId.ToString("D"));
        command.Parameters.AddWithValue("$device", run.DeviceId.ToString("D"));
        command.Parameters.AddWithValue("$inspection", run.InspectionId.ToString("D"));
        command.Parameters.AddWithValue("$endpoint", SqliteValueFormatting.Endpoint(run.Endpoint));
        command.Parameters.AddWithValue("$release", run.ReleaseId.Value);
        command.Parameters.AddWithValue("$action", run.Action.ToString());
        command.Parameters.AddWithValue("$state", run.FinalState.ToString());
        command.Parameters.AddWithValue("$started", run.StartedAt.ToString("O"));
        command.Parameters.AddWithValue("$completed", run.CompletedAt.ToString("O"));
        command.Parameters.AddWithValue("$duration", (long)(run.CompletedAt - run.StartedAt).TotalMilliseconds);
        command.Parameters.AddWithValue("$json", run.Reports.JsonReportPath);
        command.Parameters.AddWithValue("$html", run.Reports.HtmlReportPath);
        command.Parameters.AddWithValue("$engineer", run.Engineer.WindowsUserName);
        command.Parameters.AddWithValue("$workstation", run.Engineer.WorkstationName);
        command.Parameters.AddWithValue("$planned", run.PlannedAction.ToString());
        command.Parameters.AddWithValue("$observed", run.ObservedAction.ToString());
        command.Parameters.AddWithValue("$jsonHash", (object?)run.Reports.JsonSha256 ?? DBNull.Value);
        command.Parameters.AddWithValue("$htmlHash", (object?)run.Reports.HtmlSha256 ?? DBNull.Value);
        command.Parameters.AddWithValue("$format", run.Reports.FormatVersion);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        if (run.RetryOfRunId is Guid previous)
        {
            await using var link = connection.CreateCommand();
            link.Transaction = transaction;
            link.CommandText = """
				INSERT INTO deployment_links(from_run_id, to_run_id, relation)
				VALUES($previous, $current, 'Retry')
				""";
            link.Parameters.AddWithValue("$previous", previous.ToString("D"));
            link.Parameters.AddWithValue("$current", run.RunId.ToString("D"));
            await link.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task<IReadOnlyList<ManufacturingHistoryEntry>> SearchAsync(string? query, int limit = 100,
        CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > 1000)
            throw new ArgumentOutOfRangeException(nameof(limit));
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = """
			SELECT r.run_id, r.device_id, d.serial, r.endpoint, r.release_id,
			 COALESCE(r.planned_action, r.action), COALESCE(r.observed_action, r.action), r.final_state,
			 r.started_utc, r.json_report_path, r.html_report_path, r.json_report_sha256,
			 r.html_report_sha256, r.report_format_version, p.display_name,
			 (SELECT group_concat(e.permanent_mac, ',') FROM device_ethernet_ports e
				WHERE e.device_id=r.device_id AND e.retired_utc IS NULL),
			 (SELECT CASE WHEN l.from_run_id=r.run_id THEN l.to_run_id ELSE l.from_run_id END
				FROM deployment_links l WHERE l.from_run_id=r.run_id OR l.to_run_id=r.run_id LIMIT 1),
			 (SELECT l.relation FROM deployment_links l
				WHERE l.from_run_id=r.run_id OR l.to_run_id=r.run_id LIMIT 1)
			FROM deployment_runs r
			LEFT JOIN devices d ON d.device_id=r.device_id
			LEFT JOIN manufacturing_profiles p ON p.profile_id=d.profile_id AND p.version=d.profile_version
			WHERE $query='' OR lower(COALESCE(d.serial,'')) LIKE $like
			 OR lower(COALESCE(r.endpoint,'')) LIKE $like OR lower(COALESCE(r.release_id,'')) LIKE $like
			 OR lower(COALESCE(p.display_name,'')) LIKE $like
			 OR EXISTS(SELECT 1 FROM device_ethernet_ports e WHERE e.device_id=r.device_id
				AND lower(e.permanent_mac) LIKE $like)
			ORDER BY r.started_utc DESC LIMIT $limit
			""";
        var normalized = query?.Trim().ToLowerInvariant() ?? string.Empty;
        command.Parameters.AddWithValue("$query", normalized);
        command.Parameters.AddWithValue("$like", $"%{normalized}%");
        command.Parameters.AddWithValue("$limit", limit);
        var entries = new List<ManufacturingHistoryEntry>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            var reports = new ReportReference(reader.IsDBNull(9) ? string.Empty : reader.GetString(9),
                reader.IsDBNull(10) ? string.Empty : reader.GetString(10))
            {
                JsonSha256 = reader.IsDBNull(11) ? null : reader.GetString(11),
                HtmlSha256 = reader.IsDBNull(12) ? null : reader.GetString(12),
                FormatVersion = reader.GetInt32(13),
            };
            var integrity = string.IsNullOrWhiteSpace(reports.JsonReportPath)
                || string.IsNullOrWhiteSpace(reports.HtmlReportPath) ? ReportIntegrityState.Missing
                : (await ReportIntegrityVerifier.VerifyAsync(reports, cancellationToken).ConfigureAwait(false)).State;
            entries.Add(new(Guid.Parse(reader.GetString(0)), reader.IsDBNull(1) ? null : Guid.Parse(reader.GetString(1)),
                reader.IsDBNull(2) ? null : new DeviceSerial(reader.GetString(2)), ParseMacs(reader, 15),
                reader.IsDBNull(14) ? null : reader.GetString(14), ParseEndpoint(reader.GetString(3)),
                new(reader.GetString(4)), ParseAction(reader.GetString(5)), ParseAction(reader.GetString(6)),
                Enum.Parse<DeploymentFinalState>(reader.GetString(7)), DateTimeOffset.Parse(reader.GetString(8),
                    System.Globalization.CultureInfo.InvariantCulture), reports, integrity,
                reader.IsDBNull(16) ? null : Guid.Parse(reader.GetString(16)),
                reader.IsDBNull(17) ? null : reader.GetString(17)));
        }
        return entries;
    }

    private static ImmutableArray<MacAddress> ParseMacs(Microsoft.Data.Sqlite.SqliteDataReader reader, int ordinal) =>
        reader.IsDBNull(ordinal) ? [] : reader.GetString(ordinal).Split(',', StringSplitOptions.RemoveEmptyEntries)
            .Select(value => new MacAddress(value)).OrderBy(mac => mac.Normalized, StringComparer.Ordinal).ToImmutableArray();
    private static DeploymentActionKind ParseAction(string value) => Enum.TryParse<DeploymentActionKind>(value, out var action)
        ? action : DeploymentActionKind.BlockedConflict;
    private static HostEndpoint ParseEndpoint(string value)
    {
        var separator = value.LastIndexOf(':');
        return separator > 0 && int.TryParse(value[(separator + 1)..], out var port)
            ? new(value[..separator], port) : new(value, 22);
    }
}
