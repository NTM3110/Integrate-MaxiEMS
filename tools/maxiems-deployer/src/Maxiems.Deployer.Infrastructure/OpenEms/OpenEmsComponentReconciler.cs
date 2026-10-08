using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using static Maxiems.Deployer.Infrastructure.OpenEms.OpenEmsComponentMapping;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class OpenEmsComponentReconciler
{
    private readonly OpenEmsJsonRpcClient client;
    private readonly int readBackAttempts;
    private readonly TimeSpan readBackDelay;
    private readonly int recoveryAttempt;

    public OpenEmsComponentReconciler(
        OpenEmsJsonRpcClient client,
        int readBackAttempts = 120,
        TimeSpan? readBackDelay = null,
        int recoveryAttempt = 20)
    {
        ArgumentNullException.ThrowIfNull(client);
        ArgumentOutOfRangeException.ThrowIfLessThan(readBackAttempts, 1);
        this.client = client;
        this.readBackAttempts = readBackAttempts;
        this.readBackDelay = readBackDelay ?? TimeSpan.FromMilliseconds(500);
        this.recoveryAttempt = recoveryAttempt;
        ArgumentOutOfRangeException.ThrowIfLessThan(this.readBackDelay, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThan(recoveryAttempt, 1);
    }

    public Task<OpenEmsReconciliationResult> ReconcileAsync(
        ComponentManifest manifest,
        CancellationToken cancellationToken = default) =>
        this.ReconcileCoreAsync(manifest, null, cancellationToken);

    public Task<OpenEmsReconciliationResult> ReconcileAsync(
        ComponentManifest manifest,
        Func<CancellationToken, Task> convergenceRecovery,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(convergenceRecovery);
        return this.ReconcileCoreAsync(manifest, convergenceRecovery, cancellationToken);
    }

    public async Task<OpenEmsManagedCheckpoint> CaptureAsync(
        ComponentManifest manifest,
        CancellationToken cancellationToken = default)
    {
        ValidateManifest(manifest);
        var current = await this.client.GetEdgeConfigAsync(cancellationToken).ConfigureAwait(false);
        var entries = ImmutableArray.CreateBuilder<OpenEmsManagedCheckpointEntry>();
        foreach (var desired in manifest.Components)
        {
            var existing = FindExisting(desired, current);
            if (existing is null && desired.UpdateOnly)
            {
                continue;
            }
            entries.Add(new OpenEmsManagedCheckpointEntry(desired.Id, desired.FactoryPid, existing,
                desired.Properties.Select(property => property.Name).ToImmutableArray()));
        }
        return new(entries.ToImmutable());
    }

    public async Task RestoreAsync(
        OpenEmsManagedCheckpoint checkpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(checkpoint);
        foreach (var entry in checkpoint.Entries.Reverse())
        {
            if (entry.Before is not null)
            {
                await this.client.UpdateComponentAsync(entry.Before.Id,
                    ToRestoredPropertyArray(entry.Before.Properties, entry.ManagedProperties), cancellationToken)
                    .ConfigureAwait(false);
                continue;
            }
            var current = await this.client.GetEdgeConfigAsync(cancellationToken).ConfigureAwait(false);
            var componentId = FindCheckpointComponent(entry, current);
            if (componentId is not null)
            {
                await this.client.DeleteComponentAsync(componentId, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    public async Task VerifyAsync(
        OpenEmsManagedCheckpoint checkpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(checkpoint);
        var current = await this.client.GetEdgeConfigAsync(cancellationToken).ConfigureAwait(false);
        foreach (var entry in checkpoint.Entries)
        {
            if (entry.Before is null)
            {
                if (FindCheckpointComponent(entry, current) is not null)
                {
                    throw new OpenEmsReconciliationException(entry.ComponentId ?? entry.FactoryPid, ["presence"]);
                }
                continue;
            }
            if (!current.TryGetValue(entry.Before.Id, out var actual) || !SnapshotsEqual(entry.Before, actual))
            {
                throw new OpenEmsReconciliationException(entry.Before.Id, ["snapshot"]);
            }
        }
    }

    private async Task<OpenEmsReconciliationResult> ReconcileCoreAsync(
        ComponentManifest manifest,
        Func<CancellationToken, Task>? convergenceRecovery,
        CancellationToken cancellationToken)
    {
        ValidateManifest(manifest);

        var before = await client.GetEdgeConfigAsync(cancellationToken).ConfigureAwait(false);
        var rollback = new Stack<RollbackAction>();
        var results = ImmutableArray.CreateBuilder<ComponentUpsertResult>();
        var currentKey = manifest.Components[0].Id ?? manifest.Components[0].FactoryPid;
        var recoveryInvoked = false;
        async Task RecoverOnceAsync(CancellationToken token)
        {
            if (recoveryInvoked || convergenceRecovery is null)
                return;
            recoveryInvoked = true;
            await convergenceRecovery(token).ConfigureAwait(false);
        }
        var recovery = convergenceRecovery is null ? null : (Func<CancellationToken, Task>)RecoverOnceAsync;
        try
        {
            foreach (var desired in manifest.Components)
            {
                currentKey = desired.Id ?? desired.FactoryPid;
                var existing = FindExisting(desired, before);
                if (existing is null)
                {
                    if (desired.UpdateOnly)
                    {
                        results.Add(Result(desired, desired.Id!, ComponentUpsertAction.SkippedMissing));
                        continue;
                    }
                    rollback.Push(new(desired.Id, desired.FactoryPid, null,
                        desired.Properties.Select(property => property.Name).ToImmutableArray()));
                    await client.CreateComponentAsync(desired.FactoryPid, ToPropertyArray(desired, includeId: true),
                        cancellationToken).ConfigureAwait(false);
                    var created = await ReadBackAsync(desired, recovery, cancellationToken).ConfigureAwait(false);
                    results.Add(Result(desired, created.Id, ComponentUpsertAction.Created));
                    continue;
                }

                var differences = HasDifferences(desired, existing);
                if (differences.Count == 0)
                {
                    results.Add(Result(desired, existing.Id, ComponentUpsertAction.Unchanged));
                    continue;
                }

                rollback.Push(new(existing.Id, existing.FactoryPid, existing,
                    desired.Properties.Select(property => property.Name).ToImmutableArray()));
                await client.UpdateComponentAsync(existing.Id, ToMigratedPropertyArray(desired),
                    cancellationToken).ConfigureAwait(false);
                var updated = await ReadBackAsync(desired, existing, recovery, cancellationToken).ConfigureAwait(false);
                results.Add(Result(desired, updated.Id, ComponentUpsertAction.Updated));
            }
            return new(results.ToImmutable());
        }
        catch (Exception exception) when (exception is not OperationCanceledException
            || !cancellationToken.IsCancellationRequested)
        {
            throw await RollBackAndWrapAsync(currentKey, before, rollback, exception).ConfigureAwait(false);
        }
        catch (OperationCanceledException exception)
        {
            throw await RollBackAndWrapAsync(currentKey, before, rollback, exception).ConfigureAwait(false);
        }
    }

    private async Task<OpenEmsComponentSnapshot> ReadBackAsync(
        ComponentDefinition desired,
        Func<CancellationToken, Task>? convergenceRecovery,
        CancellationToken cancellationToken) =>
        await this.ReadBackAsync(desired, null, convergenceRecovery, cancellationToken).ConfigureAwait(false);

    private async Task<OpenEmsComponentSnapshot> ReadBackAsync(
        ComponentDefinition desired,
        OpenEmsComponentSnapshot? preserveFrom,
        Func<CancellationToken, Task>? convergenceRecovery,
        CancellationToken cancellationToken)
    {
        List<string> differences = [];
        for (var attempt = 0; attempt < this.readBackAttempts; attempt++)
        {
            var actual = FindExisting(desired,
                await client.GetEdgeConfigAsync(cancellationToken).ConfigureAwait(false));
            differences = actual is null
                ? desired.Properties.Select(property => property.Name).ToList()
                : preserveFrom is null
                    ? HasDifferences(desired, actual)
                    : HasMigrationDifferences(desired, preserveFrom, actual);
            if (actual is not null && actual.FactoryPid.Equals(desired.FactoryPid, StringComparison.Ordinal)
                && differences.Count == 0)
            {
                return actual;
            }
            if (attempt + 1 == this.recoveryAttempt && convergenceRecovery is not null)
            {
                await convergenceRecovery(cancellationToken).ConfigureAwait(false);
                continue;
            }
            if (attempt < this.readBackAttempts - 1)
            {
                await Task.Delay(this.readBackDelay, cancellationToken).ConfigureAwait(false);
            }
        }
        throw new OpenEmsReconciliationException(desired.Id ?? desired.FactoryPid, differences);
    }

    private async Task<OpenEmsReconciliationException> RollBackAndWrapAsync(
        string currentKey,
        IReadOnlyDictionary<string, OpenEmsComponentSnapshot> before,
        Stack<RollbackAction> rollback,
        Exception failure)
    {
        var rollbackFailures = new List<Exception>();
        var hadRollbackActions = rollback.Count != 0;
        while (rollback.TryPop(out var action))
        {
            try
            {
                if (action.Before is null)
                {
                    var componentId = await FindCreatedIdAsync(action.ComponentId, action.FactoryPid, before)
                        .ConfigureAwait(false);
                    if (componentId is not null)
                    {
                        await client.DeleteComponentAsync(componentId, CancellationToken.None).ConfigureAwait(false);
                    }
                }
                else
                {
                    await client.UpdateComponentAsync(action.ComponentId!,
                        ToRestoredPropertyArray(action.Before.Properties, action.ManagedProperties),
                        CancellationToken.None).ConfigureAwait(false);
                }
            }
            catch (Exception exception)
            {
                rollbackFailures.Add(exception);
            }
        }
        if (hadRollbackActions)
        {
            try
            {
                await VerifySnapshotAsync(before).ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                rollbackFailures.Add(exception);
            }
        }
        var rollbackFailure = rollbackFailures.Count == 0 ? null : new AggregateException(rollbackFailures);

        var key = failure is OpenEmsReconciliationException reconciliation
            ? reconciliation.ComponentKey
            : currentKey;
        var differences = failure is OpenEmsReconciliationException typed
            ? typed.DifferingProperties
            : Array.Empty<string>();
        return new(key, differences, failure, rollbackFailure);
    }

    private async Task VerifySnapshotAsync(IReadOnlyDictionary<string, OpenEmsComponentSnapshot> expected)
    {
        var actual = await client.GetEdgeConfigAsync(CancellationToken.None).ConfigureAwait(false);
        if (actual.Count != expected.Count)
        {
            throw new OpenEmsJsonRpcException("Rollback component count does not match the pre-run snapshot.");
        }
        foreach (var component in expected)
        {
            if (!actual.TryGetValue(component.Key, out var restored)
                || !SnapshotsEqual(component.Value, restored))
            {
                throw new OpenEmsJsonRpcException(
                    $"Rollback did not restore component '{component.Key}' to its pre-run snapshot.");
            }
        }
    }

    private async Task<string?> FindCreatedIdAsync(
        string? expectedId,
        string factoryPid,
        IReadOnlyDictionary<string, OpenEmsComponentSnapshot> before)
    {
        var current = await client.GetEdgeConfigAsync(CancellationToken.None).ConfigureAwait(false);
        if (expectedId is not null)
        {
            return !before.ContainsKey(expectedId) && current.TryGetValue(expectedId, out var expected)
                && expected.FactoryPid.Equals(factoryPid, StringComparison.Ordinal)
                    ? expectedId
                    : null;
        }
        var created = current.Values.Where(component =>
            component.FactoryPid.Equals(factoryPid, StringComparison.Ordinal) && !before.ContainsKey(component.Id)).ToArray();
        return created.Length switch
        {
            0 => null,
            1 => created[0].Id,
            _ => throw new OpenEmsJsonRpcException("Unable to identify one created component for rollback."),
        };
    }

    private sealed record RollbackAction(
        string? ComponentId,
        string FactoryPid,
        OpenEmsComponentSnapshot? Before,
        ImmutableArray<string> ManagedProperties);
}
