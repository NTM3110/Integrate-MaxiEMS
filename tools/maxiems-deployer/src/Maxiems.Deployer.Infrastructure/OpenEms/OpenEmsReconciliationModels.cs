using System.Collections.Immutable;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class OpenEmsReconciliationException(
    string componentKey,
    IReadOnlyList<string> differingProperties,
    Exception? innerException = null,
    Exception? rollbackException = null)
    : Exception($"OpenEMS reconciliation failed for '{componentKey}'"
        + (differingProperties.Count == 0 ? "." : $"; differing properties: {string.Join(", ", differingProperties)}."),
        innerException)
{
    public string ComponentKey { get; } = componentKey;
    public IReadOnlyList<string> DifferingProperties { get; } = differingProperties;
    public Exception? RollbackException { get; } = rollbackException;
}

public sealed record OpenEmsManagedCheckpointEntry(
    string? ComponentId,
    string FactoryPid,
    OpenEmsComponentSnapshot? Before,
    ImmutableArray<string> ManagedProperties);

public sealed record OpenEmsManagedCheckpoint(
    ImmutableArray<OpenEmsManagedCheckpointEntry> Entries);
