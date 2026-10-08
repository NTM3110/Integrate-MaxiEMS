using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public enum ComponentUpsertAction
{
    Unchanged,
    Created,
    Updated,
    SkippedMissing,
}

public sealed record ComponentUpsertResult(
    string ComponentKey,
    ComponentUpsertAction Action,
    ImmutableArray<string> VerifiedProperties);

public sealed record OpenEmsReconciliationResult(
    ImmutableArray<ComponentUpsertResult> Components);
