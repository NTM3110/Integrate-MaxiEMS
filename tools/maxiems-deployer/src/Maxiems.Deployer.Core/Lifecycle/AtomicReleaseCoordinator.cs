using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Lifecycle;

public enum AtomicReleaseStage
{
    Begin,
    Upload,
    Extraction,
    NginxValidation,
    SymlinkSwitch,
    EdgeStart,
    RestReadiness,
    ComponentMutation,
    ModbusBind,
    FinalHealth,
    Commit,
}

public sealed record ReleaseRollbackProof(
    bool PreviousReleaseHealthy,
    bool CleanFirstInstall,
    bool SharedStatePreserved,
    bool LockReleased,
    bool StagingRemoved)
{
    public bool IsValid(bool firstInstall) =>
        (firstInstall ? this.CleanFirstInstall : this.PreviousReleaseHealthy)
        && this.SharedStatePreserved
        && this.LockReleased
        && this.StagingRemoved;
}

public sealed record AtomicReleaseResult(
    bool Succeeded,
    AtomicReleaseStage? FailedStage,
    ReleaseRollbackProof? RollbackProof,
    ImmutableArray<AtomicReleaseStage> CompletedStages);

public interface IAtomicReleaseOperations
{
    Task ExecuteAsync(AtomicReleaseStage stage, CancellationToken cancellationToken);

    Task RollBackAsync(CancellationToken cancellationToken);

    Task<ReleaseRollbackProof> VerifyRollbackAsync(CancellationToken cancellationToken);
}

public sealed class AtomicReleaseException(
    string message,
    AtomicReleaseStage failedStage,
    Exception innerException) : Exception(message, innerException)
{
    public AtomicReleaseStage FailedStage { get; } = failedStage;
}

public sealed class AtomicReleaseCoordinator(IAtomicReleaseOperations operations)
{
    private static readonly ImmutableArray<AtomicReleaseStage> Stages =
    [
        AtomicReleaseStage.Begin,
        AtomicReleaseStage.Upload,
        AtomicReleaseStage.Extraction,
        AtomicReleaseStage.NginxValidation,
        AtomicReleaseStage.SymlinkSwitch,
        AtomicReleaseStage.EdgeStart,
        AtomicReleaseStage.RestReadiness,
        AtomicReleaseStage.ComponentMutation,
        AtomicReleaseStage.ModbusBind,
        AtomicReleaseStage.FinalHealth,
        AtomicReleaseStage.Commit,
    ];

    public async Task<AtomicReleaseResult> RunAsync(
        bool firstInstall,
        CancellationToken cancellationToken = default)
    {
        var completed = ImmutableArray.CreateBuilder<AtomicReleaseStage>();
        foreach (var stage in Stages)
        {
            try
            {
                await operations.ExecuteAsync(stage, cancellationToken).ConfigureAwait(false);
                completed.Add(stage);
            }
            catch (Exception exception) when (stage != AtomicReleaseStage.Begin)
            {
                try
                {
                    await operations.RollBackAsync(CancellationToken.None).ConfigureAwait(false);
                    var proof = await operations.VerifyRollbackAsync(CancellationToken.None).ConfigureAwait(false);
                    if (!proof.IsValid(firstInstall))
                    {
                        throw new InvalidDataException("Release rollback invariants were not proven.");
                    }
                    return new(false, stage, proof, completed.ToImmutable());
                }
                catch (Exception rollbackException)
                {
                    throw new AtomicReleaseException(
                        $"Release deployment failed at {stage} and rollback proof failed.",
                        stage,
                        new AggregateException(exception, rollbackException));
                }
            }
        }
        return new(true, null, null, completed.ToImmutable());
    }
}
