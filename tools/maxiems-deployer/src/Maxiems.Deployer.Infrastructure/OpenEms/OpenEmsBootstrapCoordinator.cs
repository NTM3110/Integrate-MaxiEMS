namespace Maxiems.Deployer.Infrastructure.OpenEms;

public interface IOpenEmsBootstrapStore
{
    Task<bool> ConfigurationExistsAsync(CancellationToken cancellationToken);

    Task InstallFreshAsync(
        IReadOnlyCollection<OpenEmsBootstrapFile> files,
        CancellationToken cancellationToken);
}

public enum OpenEmsBootstrapDisposition
{
    Created,
    PreservedExisting,
}

public sealed class OpenEmsBootstrapCoordinator(
    IOpenEmsBootstrapStore store,
    IBootstrapEntropy? entropy = null)
{
    public async Task<OpenEmsBootstrapDisposition> EnsureAsync(
        ReadOnlyMemory<char> deploymentAdministratorPassword,
        CancellationToken cancellationToken = default)
    {
        if (await store.ConfigurationExistsAsync(cancellationToken).ConfigureAwait(false))
        {
            return OpenEmsBootstrapDisposition.PreservedExisting;
        }

        var configuration = OpenEmsBootstrapConfiguration.Create(
            deploymentAdministratorPassword.Span,
            entropy);
        await store.InstallFreshAsync(configuration.Files, cancellationToken).ConfigureAwait(false);
        return OpenEmsBootstrapDisposition.Created;
    }
}
