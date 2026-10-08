using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

internal sealed record ManufacturingMigration(int Version, string Name, string Sql);

internal static class ManufacturingMigrations
{
    public static IReadOnlyList<ManufacturingMigration> All
    {
        get;
    } = [
        new(1, "initial-manufacturing-store", Initial),
        new(2, "release-catalog-integrity", ReleaseCatalogIntegrity),
        new(3, "deployment-action-audit", DeploymentActionAudit),
        new(4, "report-integrity-metadata", ReportIntegrityMetadata),
        new(5, "immutable-deployment-links", ImmutableDeploymentLinks),
    ];

    private const string ImmutableDeploymentLinks = """
		CREATE TRIGGER immutable_deployment_link_update BEFORE UPDATE ON deployment_links
			BEGIN SELECT RAISE(ABORT, 'deployment links are immutable'); END;
		CREATE TRIGGER immutable_deployment_link_delete BEFORE DELETE ON deployment_links
			BEGIN SELECT RAISE(ABORT, 'deployment links are immutable'); END;
		""";

    private const string ReportIntegrityMetadata = """
		ALTER TABLE deployment_runs ADD COLUMN json_report_sha256 TEXT;
		ALTER TABLE deployment_runs ADD COLUMN html_report_sha256 TEXT;
		ALTER TABLE deployment_runs ADD COLUMN report_format_version INTEGER NOT NULL DEFAULT 1;
		""";

    private const string DeploymentActionAudit = """
		ALTER TABLE deployment_runs ADD COLUMN planned_action TEXT;
		ALTER TABLE deployment_runs ADD COLUMN observed_action TEXT;
		CREATE INDEX deployment_run_planned_action ON deployment_runs(planned_action);
		CREATE INDEX deployment_run_observed_action ON deployment_runs(observed_action);
		""";

    private const string ReleaseCatalogIntegrity = """
		CREATE UNIQUE INDEX unique_local_release_artifact ON local_releases(artifact_sha256);
		CREATE UNIQUE INDEX unique_local_release_payload ON local_releases(payload_sha256);
		""";

    private const string Initial = """
		CREATE TABLE manufacturing_profiles (
			profile_id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0), state TEXT NOT NULL,
			canonical_json TEXT NOT NULL, sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
			display_name TEXT NOT NULL, product_family TEXT NOT NULL, hardware_variant TEXT NOT NULL,
			change_note TEXT NOT NULL, created_utc TEXT NOT NULL, created_by TEXT NOT NULL,
			PRIMARY KEY(profile_id, version), UNIQUE(sha256)
		) STRICT;
		CREATE TABLE devices (
			device_id TEXT PRIMARY KEY, serial TEXT NOT NULL UNIQUE, lifecycle TEXT NOT NULL,
			profile_id TEXT NOT NULL, profile_version INTEGER NOT NULL,
			asset_id TEXT, project_code TEXT, customer TEXT, site TEXT,
			created_utc TEXT NOT NULL, updated_utc TEXT NOT NULL,
			FOREIGN KEY(profile_id, profile_version) REFERENCES manufacturing_profiles(profile_id, version) ON DELETE RESTRICT
		) STRICT;
		CREATE TABLE device_ethernet_ports (
			device_id TEXT NOT NULL, physical_label TEXT NOT NULL COLLATE NOCASE, linux_name TEXT NOT NULL,
			permanent_mac TEXT NOT NULL, current_mac TEXT NOT NULL, evidence_summary TEXT NOT NULL,
			is_serial_source INTEGER NOT NULL CHECK(is_serial_source IN (0, 1)), observed_utc TEXT NOT NULL,
			retired_utc TEXT, PRIMARY KEY(device_id, physical_label),
			FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE RESTRICT
		) STRICT;
		CREATE UNIQUE INDEX active_permanent_mac_owner
			ON device_ethernet_ports(permanent_mac) WHERE retired_utc IS NULL;
		CREATE UNIQUE INDEX one_serial_source_per_device
			ON device_ethernet_ports(device_id) WHERE is_serial_source = 1 AND retired_utc IS NULL;
		CREATE TABLE hardware_inspections (
			inspection_id TEXT PRIMARY KEY, device_id TEXT, endpoint TEXT NOT NULL,
			profile_id TEXT NOT NULL, profile_version INTEGER NOT NULL,
			snapshot_json TEXT NOT NULL, snapshot_sha256 TEXT NOT NULL CHECK(length(snapshot_sha256) = 64),
			started_utc TEXT NOT NULL, completed_utc TEXT NOT NULL, created_by TEXT NOT NULL,
			FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE RESTRICT,
			FOREIGN KEY(profile_id, profile_version) REFERENCES manufacturing_profiles(profile_id, version) ON DELETE RESTRICT
		) STRICT;
		CREATE TABLE inspection_checks (
			inspection_id TEXT NOT NULL, ordinal INTEGER NOT NULL, code TEXT NOT NULL, category TEXT NOT NULL,
			requirement TEXT NOT NULL, outcome TEXT NOT NULL, observed TEXT NOT NULL, expected TEXT NOT NULL,
			evidence_summary TEXT NOT NULL, PRIMARY KEY(inspection_id, ordinal),
			FOREIGN KEY(inspection_id) REFERENCES hardware_inspections(inspection_id) ON DELETE RESTRICT
		) STRICT;
		CREATE TABLE deployment_runs (
			run_id TEXT PRIMARY KEY, device_id TEXT, inspection_id TEXT, endpoint TEXT NOT NULL,
			release_id TEXT NOT NULL, action TEXT NOT NULL, final_state TEXT NOT NULL,
			started_utc TEXT NOT NULL, completed_utc TEXT NOT NULL, duration_ms INTEGER NOT NULL CHECK(duration_ms >= 0),
			json_report_path TEXT, html_report_path TEXT, legacy_unassigned INTEGER NOT NULL DEFAULT 0
				CHECK(legacy_unassigned IN (0, 1)), engineer_user TEXT, workstation TEXT,
			CHECK((legacy_unassigned = 1 AND device_id IS NULL AND inspection_id IS NULL)
				OR (legacy_unassigned = 0 AND device_id IS NOT NULL AND inspection_id IS NOT NULL)),
			FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE RESTRICT,
			FOREIGN KEY(inspection_id) REFERENCES hardware_inspections(inspection_id) ON DELETE RESTRICT
		) STRICT;
		CREATE TABLE deployment_links (
			from_run_id TEXT NOT NULL, to_run_id TEXT NOT NULL, relation TEXT NOT NULL,
			PRIMARY KEY(from_run_id, to_run_id, relation),
			FOREIGN KEY(from_run_id) REFERENCES deployment_runs(run_id) ON DELETE RESTRICT,
			FOREIGN KEY(to_run_id) REFERENCES deployment_runs(run_id) ON DELETE RESTRICT
		) STRICT;
		CREATE TABLE local_releases (
			release_id TEXT PRIMARY KEY, source TEXT NOT NULL, status TEXT NOT NULL,
			manifest_json TEXT NOT NULL, artifact_sha256 TEXT NOT NULL, payload_sha256 TEXT NOT NULL,
			compatibility_json TEXT NOT NULL, cache_path TEXT, imported_utc TEXT NOT NULL
		) STRICT;
		CREATE TABLE import_markers (
			source_key TEXT PRIMARY KEY, completed_utc TEXT NOT NULL, backup_path TEXT NOT NULL
		) STRICT;
		CREATE TRIGGER immutable_inspection_update BEFORE UPDATE ON hardware_inspections
			BEGIN SELECT RAISE(ABORT, 'hardware inspections are immutable'); END;
		CREATE TRIGGER immutable_inspection_delete BEFORE DELETE ON hardware_inspections
			BEGIN SELECT RAISE(ABORT, 'hardware inspections are immutable'); END;
		CREATE TRIGGER immutable_run_update BEFORE UPDATE ON deployment_runs
			BEGIN SELECT RAISE(ABORT, 'deployment runs are immutable'); END;
		CREATE TRIGGER immutable_run_delete BEFORE DELETE ON deployment_runs
			BEGIN SELECT RAISE(ABORT, 'deployment runs are immutable'); END;
		""";
}

internal static class ManufacturingMigrationRunner
{
    public static async Task ApplyAsync(
        SqliteConnection connection,
        IReadOnlyList<ManufacturingMigration> migrations,
        CancellationToken cancellationToken)
    {
        await using var transaction = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken)
            .ConfigureAwait(false);
        await ExecuteAsync(connection, transaction, """
			CREATE TABLE IF NOT EXISTS schema_migrations (
				version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_utc TEXT NOT NULL
			) STRICT;
			""", cancellationToken).ConfigureAwait(false);
        var applied = new HashSet<int>();
        await using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = "SELECT version FROM schema_migrations";
            await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
                applied.Add(reader.GetInt32(0));
        }
        foreach (var migration in migrations.Where(item => !applied.Contains(item.Version)).OrderBy(item => item.Version))
        {
            await ExecuteAsync(connection, transaction, migration.Sql, cancellationToken).ConfigureAwait(false);
            await using var insert = connection.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText = "INSERT INTO schema_migrations(version, name, applied_utc) VALUES($v, $n, $at)";
            insert.Parameters.AddWithValue("$v", migration.Version);
            insert.Parameters.AddWithValue("$n", migration.Name);
            insert.Parameters.AddWithValue("$at", DateTimeOffset.UtcNow.ToString("O"));
            await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task ExecuteAsync(SqliteConnection connection, System.Data.Common.DbTransaction transaction,
        string sql, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }
}
