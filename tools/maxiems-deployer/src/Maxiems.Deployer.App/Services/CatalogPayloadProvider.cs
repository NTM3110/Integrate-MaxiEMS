using Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

namespace Maxiems.Deployer.App.Services;

public sealed class CatalogPayloadProvider(
    ReleasePackageMaterializer materializer,
    ReleaseCatalogSelection frozenSelection) : IDeploymentPayloadProvider
{
    public Task<string> MaterializeAsync(CancellationToken cancellationToken) =>
        materializer.MaterializeAsync(frozenSelection, cancellationToken);
}
