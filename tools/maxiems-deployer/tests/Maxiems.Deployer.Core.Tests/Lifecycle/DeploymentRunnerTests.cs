using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Security;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Lifecycle;

[TestClass]
[TestCategory("StateMachine")]
public sealed class DeploymentRunnerTests
{
    public static IEnumerable<object[]> OperationalPhases => Enum.GetValues<DeploymentPhase>()
        .Where(phase => phase is not DeploymentPhase.Rollback and not DeploymentPhase.Complete)
        .Select(phase => new object[] { phase });

    [TestMethod]
    public async Task HappyPathReachesCompleteInExactOrder()
    {
        var steps = CreateSteps();
        var result = await new DeploymentRunner(steps, new()).RunAsync();

        Assert.AreEqual(DeploymentFinalState.Succeeded, result.FinalState);
        var expected = steps.Select(step => step.Phase).Append(DeploymentPhase.Complete).ToArray();
        CollectionAssert.AreEqual(expected, result.Phases.Select(phase => phase.Phase).ToArray());
        Assert.IsTrue(result.Phases.All(phase => phase.Outcome == DeploymentPhaseOutcome.Passed));
    }

    [TestMethod]
    [DynamicData(nameof(OperationalPhases))]
    public async Task FailureAtEveryPhaseUsesTheCorrectTerminalPath(DeploymentPhase failingPhase)
    {
        var steps = CreateSteps(failingPhase: failingPhase);
        var result = await new DeploymentRunner(steps, new()).RunAsync();
        var mutationStarted = failingPhase >= DeploymentPhase.ProvisionIdentity;

        Assert.AreEqual(
            mutationStarted ? DeploymentFinalState.RolledBack : DeploymentFinalState.Failed,
            result.FinalState);
        Assert.AreEqual(mutationStarted, result.Phases.Any(phase => phase.Phase == DeploymentPhase.Rollback));
    }

    [TestMethod]
    public async Task CancellationBeforeMutationDoesNotRollback()
    {
        using var cancellation = new CancellationTokenSource();
        var steps = CreateSteps(cancelAt: DeploymentPhase.ConnectBootstrap, cancellation: cancellation);
        var result = await new DeploymentRunner(steps, new()).RunAsync(cancellation.Token);

        Assert.AreEqual(DeploymentFinalState.Cancelled, result.FinalState);
        Assert.IsFalse(result.Phases.Any(phase => phase.Phase == DeploymentPhase.Rollback));
    }

    [TestMethod]
    public async Task CancellationAfterMutationRollsBackIgnoringTheCancelledToken()
    {
        using var cancellation = new CancellationTokenSource();
        var steps = CreateSteps(cancelAt: DeploymentPhase.StartServices, cancellation: cancellation);
        var result = await new DeploymentRunner(steps, new()).RunAsync(cancellation.Token);

        Assert.AreEqual(DeploymentFinalState.RolledBack, result.FinalState);
        Assert.AreEqual(DeploymentPhaseOutcome.RolledBack, result.Phases.Last().Outcome);
    }

    [TestMethod]
    public async Task ServiceFailureCompensatesExactlyOnceInReverseOrder()
    {
        var compensated = new List<DeploymentPhase>();
        var steps = CreateSteps(
            failingPhase: DeploymentPhase.StartServices,
            compensated: compensated);
        var result = await new DeploymentRunner(steps, new()).RunAsync();
        var expected = steps
            .Where(step => step.MayMutateTarget && step.Phase <= DeploymentPhase.StartServices)
            .Select(step => step.Phase)
            .Reverse()
            .ToArray();

        Assert.AreEqual(DeploymentFinalState.RolledBack, result.FinalState);
        CollectionAssert.AreEqual(expected, compensated);
        Assert.IsTrue(steps.All(step => step.CompensationCount <= 1));
    }

    [TestMethod]
    public async Task JournalCompensationIsIdempotent()
    {
        var step = new FakeDeploymentStep(DeploymentPhase.StageRelease, true);
        var journal = new DeploymentJournal();
        journal.Register(step);
        journal.Register(step);

        await journal.CompensateAsync();
        await journal.CompensateAsync();

        Assert.AreEqual(1, journal.Count);
        Assert.AreEqual(1, step.CompensationCount);
    }

    private static List<FakeDeploymentStep> CreateSteps(
        DeploymentPhase? failingPhase = null,
        DeploymentPhase? cancelAt = null,
        CancellationTokenSource? cancellation = null,
        List<DeploymentPhase>? compensated = null)
    {
        return OperationalPhases.Select(data => (DeploymentPhase)data[0]).Select(phase => new FakeDeploymentStep(
            phase,
            phase >= DeploymentPhase.ProvisionIdentity,
            _ =>
            {
                if (phase == cancelAt)
                {
                    cancellation!.Cancel();
                    throw new OperationCanceledException(cancellation.Token);
                }
                return phase == failingPhase
                    ? Task.FromException(new InvalidOperationException("phase failed with password=hunter2"))
                    : Task.CompletedTask;
            },
            _ =>
            {
                compensated?.Add(phase);
                return Task.CompletedTask;
            })).ToList();
    }
}
