using System.Text.Json.Nodes;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Reporting;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.Data.Sqlite;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Storage;

[TestClass]
[TestCategory("ManufacturingStore")]
public sealed class ManufacturingRepositoryTests : ManufacturingStoreTestBase
{
    [TestMethod]
    public async Task DuplicateProfileVersionIsRejectedAndOriginalRemainsImmutable()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var repository = new ManufacturingProfileRepository(store);
        var json = await File.ReadAllTextAsync(FixturePath);

        await Assert.ThrowsExactlyAsync<SqliteException>(() => repository.AddAsync(
            json, "other-engineer", Timestamp("2026-07-22T02:00:00Z")));

        Assert.IsTrue(JsonNode.DeepEquals(JsonNode.Parse(json),
            JsonNode.Parse((await repository.FindCanonicalJsonAsync(profile.Reference))!)));
    }

    [TestMethod]
    public async Task DuplicateSerialAndActivePermanentMacAreRejectedTransactionally()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var repository = new DeviceRepository(store);
        var first = Device(profile.Reference, "00613A7D755A", "00613A7D755B");
        var duplicateSerial = Device(profile.Reference, "00613A7D755A", "00613A7D755C");

        await repository.RegisterAsync(first, "ethtool permanent address");
        await Assert.ThrowsExactlyAsync<SqliteException>(() =>
            repository.RegisterAsync(duplicateSerial, "ethtool permanent address"));

        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM devices"));
        Assert.AreEqual(2L, await ScalarInt64Async(store, "SELECT count(*) FROM device_ethernet_ports"));
    }

    [TestMethod]
    public async Task TwoWritersCannotClaimSameProfileVersion()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        await store.InitializeAsync();
        var json = await File.ReadAllTextAsync(FixturePath);
        var repository = new ManufacturingProfileRepository(store);

        var writes = new[] {
            CaptureAsync(() => repository.AddAsync(json, "engineer-a", DateTimeOffset.UtcNow)),
            CaptureAsync(() => repository.AddAsync(json, "engineer-b", DateTimeOffset.UtcNow)),
        };
        var outcomes = await Task.WhenAll(writes);

        Assert.AreEqual(1, outcomes.Count(exception => exception is null));
        Assert.AreEqual(1, outcomes.Count(exception => exception is SqliteException));
        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM manufacturing_profiles"));
    }

    [TestMethod]
    public async Task TwoWritersCannotClaimSameSerial()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var repository = new DeviceRepository(store);
        var writes = new[] {
            CaptureAsync(() => repository.RegisterAsync(
                Device(profile.Reference, "00613A7D755A", "00613A7D755B"), "writer-a")),
            CaptureAsync(() => repository.RegisterAsync(
                Device(profile.Reference, "00613A7D755A", "00613A7D755C"), "writer-b")),
        };

        var outcomes = await Task.WhenAll(writes);

        Assert.AreEqual(1, outcomes.Count(exception => exception is null));
        Assert.AreEqual(1, outcomes.Count(exception => exception is SqliteException));
        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM devices"));
        Assert.AreEqual(2L, await ScalarInt64Async(store, "SELECT count(*) FROM device_ethernet_ports"));
    }

    [TestMethod]
    public async Task ForeignKeysRestrictDeletingAProfileUsedByADevice()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        await new DeviceRepository(store).RegisterAsync(
            Device(profile.Reference, "00613A7D755A", "00613A7D755B"), "ethtool permanent address");
        await using var connection = await store.OpenConnectionAsync(CancellationToken.None);
        await using var command = connection.CreateCommand();
        command.CommandText = "DELETE FROM manufacturing_profiles WHERE profile_id = $id AND version = $version";
        command.Parameters.AddWithValue("$id", profile.Reference.ProfileId);
        command.Parameters.AddWithValue("$version", profile.Reference.Version);

        await Assert.ThrowsExactlyAsync<SqliteException>(() => command.ExecuteNonQueryAsync());
        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM manufacturing_profiles"));
    }

    [TestMethod]
    public async Task IdentityLookupBlocksOtherDeviceButAllowsCurrentDeviceUpdate()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var device = Device(profile.Reference, "00613A7D755A", "00613A7D755B");
        var repository = new DeviceRepository(store);
        await repository.RegisterAsync(device, "ethtool permanent address");

        var conflict = await repository.FindConflictAsync(device.Serial, device.Serial.SerialSourceMac);
        var sameDevice = await repository.FindConflictAsync(device.Serial, device.Serial.SerialSourceMac, device.DeviceId);

        Assert.IsTrue(conflict.SerialAlreadyRegistered);
        Assert.IsTrue(conflict.PermanentMacOwnedByAnotherDevice);
        Assert.IsFalse(sameDevice.SerialAlreadyRegistered);
        Assert.IsFalse(sameDevice.PermanentMacOwnedByAnotherDevice);
    }

    [TestMethod]
    public async Task OperationsProjectionLoadsDashboardProfilesAndSearchesBothMacs()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var device = Device(profile.Reference, "00613A7D755A", "00613A7D755B");
        await new DeviceRepository(store).RegisterAsync(device, "ethtool permanent address");
        var operations = new ManufacturingOperationsRepository(store);

        var dashboard = await operations.GetDashboardAsync();
        var profiles = await operations.ListProfilesAsync();
        var bySerial = await operations.SearchDevicesAsync(device.Serial.Value);
        var byFirstMac = await operations.SearchDevicesAsync("00613A7D755A");
        var bySecondMac = await operations.SearchDevicesAsync("00613A7D755B");

        Assert.AreEqual(1, dashboard.Profiles);
        Assert.AreEqual(1, dashboard.Devices);
        Assert.HasCount(1, profiles);
        Assert.HasCount(1, bySerial);
        Assert.HasCount(1, byFirstMac);
        Assert.HasCount(1, bySecondMac);
        Assert.HasCount(2, bySerial.Single().EthernetPorts);
    }

    [TestMethod]
    public async Task InspectionAndDeploymentRunRowsCannotBeChangedOrDeleted()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var device = Device(profile.Reference, "00613A7D755A", "00613A7D755B");
        await new DeviceRepository(store).RegisterAsync(device, "ethtool permanent address");
        var inspectionId = Guid.NewGuid();
        var runId = Guid.NewGuid();
        await using var connection = await store.OpenConnectionAsync(CancellationToken.None);
        await InsertInspectionAndRunAsync(connection, profile.Reference, device.DeviceId, inspectionId, runId);

        await AssertSqlRejectedAsync(connection,
            $"UPDATE hardware_inspections SET endpoint='changed' WHERE inspection_id='{inspectionId:D}'");
        await AssertSqlRejectedAsync(connection,
            $"DELETE FROM hardware_inspections WHERE inspection_id='{inspectionId:D}'");
        await AssertSqlRejectedAsync(connection,
            $"UPDATE deployment_runs SET endpoint='changed' WHERE run_id='{runId:D}'");
        await AssertSqlRejectedAsync(connection,
            $"DELETE FROM deployment_runs WHERE run_id='{runId:D}'");
    }

    [TestMethod]
    public async Task RetryCreatesNewRunAndPersistsPlannedVersusObservedAction()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var device = Device(profile.Reference, "00613A7D755A", "00613A7D755B");
        await new DeviceRepository(store).RegisterAsync(device, "ethtool permanent address");
        var inspectionId = Guid.NewGuid();
        var previousRunId = Guid.NewGuid();
        await using (var connection = await store.OpenConnectionAsync(CancellationToken.None))
        {
            await InsertInspectionAndRunAsync(connection, profile.Reference, device.DeviceId, inspectionId, previousRunId);
        }
        var retryId = Guid.NewGuid();
        var at = Timestamp("2026-07-22T05:00:00Z");
        var jsonPath = Path.Combine(this.TemporaryDirectory, "retry.json");
        var htmlPath = Path.Combine(this.TemporaryDirectory, "retry.html");
        await File.WriteAllTextAsync(jsonPath, "{}");
        await File.WriteAllTextAsync(htmlPath, "<html></html>");
        var reports = new ReportReference(jsonPath, htmlPath)
        {
            JsonSha256 = await ReportIntegrityVerifier.HashAsync(jsonPath),
            HtmlSha256 = await ReportIntegrityVerifier.HashAsync(htmlPath),
            FormatVersion = 2,
        };
        var retry = new StoredDeploymentRun(retryId, device.DeviceId, inspectionId, new("target", 22),
            new("release-2"), DeploymentActionKind.Update, DeploymentFinalState.Succeeded, at, at.AddMinutes(1),
            reports, new("engineer", "station"))
        {
            PlannedAction = DeploymentActionKind.Update,
            ObservedAction = DeploymentActionKind.NoChange,
            RetryOfRunId = previousRunId,
        };

        await new DeploymentRunRepository(store).AppendAsync(retry);

        Assert.AreEqual(2L, await ScalarInt64Async(store, "SELECT count(*) FROM deployment_runs"));
        Assert.AreEqual(1L, await ScalarInt64Async(store,
            "SELECT count(*) FROM deployment_links WHERE relation='Retry'"));
        await using var read = await store.OpenConnectionAsync(CancellationToken.None);
        await using var command = read.CreateCommand();
        command.CommandText = "SELECT planned_action, observed_action FROM deployment_runs WHERE run_id=$run";
        command.Parameters.AddWithValue("$run", retryId.ToString("D"));
        await using var reader = await command.ExecuteReaderAsync();
        Assert.IsTrue(await reader.ReadAsync());
        Assert.AreEqual("Update", reader.GetString(0));
        Assert.AreEqual("NoChange", reader.GetString(1));
        await reader.DisposeAsync();
        await read.DisposeAsync();
        var bySerial = await new DeploymentRunRepository(store).SearchAsync(device.Serial.Value);
        var byMac = await new DeploymentRunRepository(store).SearchAsync("00613A7D755B");
        var byRelease = await new DeploymentRunRepository(store).SearchAsync("release-2");
        Assert.HasCount(2, bySerial);
        Assert.HasCount(2, byMac);
        Assert.HasCount(1, byRelease);
        Assert.AreEqual(ReportIntegrityState.Verified, byRelease.Single().ReportIntegrity);
        Assert.AreEqual(previousRunId, byRelease.Single().RelatedRunId);
        await using var immutable = await store.OpenConnectionAsync(CancellationToken.None);
        await AssertSqlRejectedAsync(immutable,
            $"DELETE FROM deployment_links WHERE from_run_id='{previousRunId:D}' AND to_run_id='{retryId:D}'");
    }

    private static async Task<Exception?> CaptureAsync(Func<Task<ManufacturingProfile>> operation)
    {
        try
        {
            await operation();
            return null;
        }
        catch (Exception exception) when (exception is SqliteException)
        {
            return exception;
        }
    }

    private static async Task<Exception?> CaptureAsync(Func<Task> operation)
    {
        try
        {
            await operation();
            return null;
        }
        catch (Exception exception) when (exception is SqliteException)
        {
            return exception;
        }
    }

    private static async Task InsertInspectionAndRunAsync(SqliteConnection connection,
        ManufacturingProfileReference profile, Guid deviceId, Guid inspectionId, Guid runId)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = """
			INSERT INTO hardware_inspections(inspection_id, device_id, endpoint, profile_id, profile_version,
				snapshot_json, snapshot_sha256, started_utc, completed_utc, created_by)
			VALUES($inspection, $device, 'target:22', $profile, $version, '{}', $hash, $at, $at, 'engineer');
			INSERT INTO deployment_runs(run_id, device_id, inspection_id, endpoint, release_id, action,
				final_state, started_utc, completed_utc, duration_ms, legacy_unassigned)
			VALUES($run, $device, $inspection, 'target:22', '20260722080000-123456789abc', 'Deploy',
				'Succeeded', $at, $at, 0, 0);
			""";
        command.Parameters.AddWithValue("$inspection", inspectionId.ToString("D"));
        command.Parameters.AddWithValue("$run", runId.ToString("D"));
        command.Parameters.AddWithValue("$device", deviceId.ToString("D"));
        command.Parameters.AddWithValue("$profile", profile.ProfileId);
        command.Parameters.AddWithValue("$version", profile.Version);
        command.Parameters.AddWithValue("$hash", new string('a', 64));
        command.Parameters.AddWithValue("$at", Timestamp("2026-07-22T04:00:00Z").ToString("O"));
        await command.ExecuteNonQueryAsync();
    }

    private static async Task AssertSqlRejectedAsync(SqliteConnection connection, string sql)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        await Assert.ThrowsExactlyAsync<SqliteException>(() => command.ExecuteNonQueryAsync());
    }
}
