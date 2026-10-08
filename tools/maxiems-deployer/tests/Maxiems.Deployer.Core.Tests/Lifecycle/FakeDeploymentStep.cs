using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;

namespace Maxiems.Deployer.Core.Tests.Lifecycle;

internal sealed class FakeDeploymentStep(
    DeploymentPhase phase,
    bool mayMutateTarget,
    Func<CancellationToken, Task>? execute = null,
    Func<CancellationToken, Task>? compensate = null) : IDeploymentStep
{
    public DeploymentPhase Phase { get; } = phase;

    public bool MayMutateTarget { get; } = mayMutateTarget;

    public int ExecuteCount
    {
        get; private set;
    }

    public int CompensationCount
    {
        get; private set;
    }

    public async Task ExecuteAsync(CancellationToken cancellationToken)
    {
        this.ExecuteCount++;
        if (execute is not null)
        {
            await execute(cancellationToken);
        }
    }

    public async Task CompensateAsync(CancellationToken cancellationToken)
    {
        this.CompensationCount++;
        if (compensate is not null)
        {
            await compensate(cancellationToken);
        }
    }
}
