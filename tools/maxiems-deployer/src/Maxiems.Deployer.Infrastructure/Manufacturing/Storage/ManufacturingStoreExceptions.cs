namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class ManufacturingStoreCorruptException(string message, string quarantinePath, Exception? inner = null)
    : Exception(message, inner)
{
    public string QuarantinePath { get; } = quarantinePath;
}

public sealed record ManufacturingStoreStartup(
    string DatabasePath,
    string? PreMigrationBackupPath,
    int SchemaVersion);

public sealed record LegacyHistoryImportResult(
    bool SourceFound,
    bool AlreadyImported,
    int ImportedRunCount,
    string? SourceBackupPath);
