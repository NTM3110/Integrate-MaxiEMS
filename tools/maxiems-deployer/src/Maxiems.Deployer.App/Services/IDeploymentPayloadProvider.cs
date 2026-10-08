namespace Maxiems.Deployer.App.Services;

public interface IDeploymentPayloadProvider
{
    Task<string> MaterializeAsync(CancellationToken cancellationToken);
}
