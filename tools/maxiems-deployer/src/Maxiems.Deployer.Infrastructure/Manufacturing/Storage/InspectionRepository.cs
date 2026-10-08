using Maxiems.Deployer.Core.Manufacturing;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class InspectionRepository(SqliteManufacturingStore store) : IInspectionRepository
{
    public async Task AppendAsync(StoredInspection inspection, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(inspection);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        var targetInspection = inspection.Inspection;
        await using var connection = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken)
            .ConfigureAwait(false);
        await using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
				INSERT INTO hardware_inspections(inspection_id, device_id, endpoint, profile_id, profile_version,
					snapshot_json, snapshot_sha256, started_utc, completed_utc, created_by)
				VALUES($id, $device, $endpoint, $profile, $version, $json, $hash, $started, $completed, $by)
				""";
            command.Parameters.AddWithValue("$id", targetInspection.InspectionId.ToString("D"));
            command.Parameters.AddWithValue("$device", inspection.DeviceId is Guid id ? id.ToString("D") : DBNull.Value);
            command.Parameters.AddWithValue("$endpoint", SqliteValueFormatting.Endpoint(targetInspection.Endpoint));
            command.Parameters.AddWithValue("$profile", targetInspection.Profile.ProfileId);
            command.Parameters.AddWithValue("$version", targetInspection.Profile.Version);
            command.Parameters.AddWithValue("$json", inspection.SnapshotJson);
            command.Parameters.AddWithValue("$hash", targetInspection.SnapshotSha256);
            command.Parameters.AddWithValue("$started", targetInspection.StartedAt.ToString("O"));
            command.Parameters.AddWithValue("$completed", targetInspection.CompletedAt.ToString("O"));
            command.Parameters.AddWithValue("$by", inspection.Engineer.WindowsUserName);
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        for (var index = 0; index < targetInspection.Checklist.Results.Length; index++)
        {
            var result = targetInspection.Checklist.Results[index];
            await using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
				INSERT INTO inspection_checks(inspection_id, ordinal, code, category, requirement, outcome,
					observed, expected, evidence_summary)
				VALUES($inspection, $ordinal, $code, $category, $requirement, $outcome, $observed, $expected, $evidence)
				""";
            command.Parameters.AddWithValue("$inspection", targetInspection.InspectionId.ToString("D"));
            command.Parameters.AddWithValue("$ordinal", index);
            command.Parameters.AddWithValue("$code", result.Code);
            command.Parameters.AddWithValue("$category", CategoryFromCode(result.Code));
            command.Parameters.AddWithValue("$requirement", result.Requirement.ToString());
            command.Parameters.AddWithValue("$outcome", result.Outcome.ToString());
            command.Parameters.AddWithValue("$observed", result.Observed);
            command.Parameters.AddWithValue("$expected", result.Expected);
            command.Parameters.AddWithValue("$evidence", result.EvidenceSummary);
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
    }

    private static string CategoryFromCode(string code)
    {
        var separator = code.IndexOf('.', StringComparison.Ordinal);
        return separator > 0 ? code[..separator] : "general";
    }
}
