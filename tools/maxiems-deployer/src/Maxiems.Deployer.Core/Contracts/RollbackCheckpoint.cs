using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public sealed record RollbackCheckpoint(
    DateTimeOffset CapturedAt,
    ReleaseId? PreviousRelease,
    string? PreviousCurrentTarget,
    ImmutableArray<ServiceCheckpoint> Services,
    ImmutableArray<ManagedComponentSnapshot> ManagedComponents);

public sealed record ServiceCheckpoint(
    string ServiceName,
    bool WasEnabled,
    bool WasActive);

public sealed record ManagedComponentSnapshot(
    string ComponentId,
    bool Existed,
    ImmutableArray<ComponentProperty> Properties);
