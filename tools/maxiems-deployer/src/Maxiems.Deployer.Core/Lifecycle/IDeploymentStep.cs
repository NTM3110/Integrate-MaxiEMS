using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Lifecycle;

public interface IDeploymentStep
{
    DeploymentPhase Phase
    {
        get;
    }

    bool MayMutateTarget
    {
        get;
    }

    Task ExecuteAsync(CancellationToken cancellationToken);

    Task CompensateAsync(CancellationToken cancellationToken);
}
