using System.IO;
using System.Reflection;

namespace Maxiems.Deployer.App.Services;

public sealed class EmbeddedPayloadProvider : IDeploymentPayloadProvider
{
    private const string ResourceName = "Maxiems.Deployer.Payload.zip";
    private readonly Func<Stream> openResource;

    public EmbeddedPayloadProvider(Assembly? assembly = null)
    {
        var owner = assembly ?? Assembly.GetExecutingAssembly();
        this.openResource = () => owner.GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException(
                "This build has no embedded deployment payload. Use an artifact produced by publish.ps1.");
    }

    public EmbeddedPayloadProvider(Func<Stream> openResource)
    {
        ArgumentNullException.ThrowIfNull(openResource);
        this.openResource = openResource;
    }

    public async Task<string> MaterializeAsync(CancellationToken cancellationToken)
    {
        await using var source = this.openResource();
        var directory = Path.Combine(Path.GetTempPath(), "maxiems-deployer-payloads");
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, $"payload-{Guid.NewGuid():N}.zip");
        try
        {
            await using var destination = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                81920, FileOptions.Asynchronous | FileOptions.WriteThrough);
            await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            return path;
        }
        catch
        {
            if (File.Exists(path))
                File.Delete(path);
            throw;
        }
    }
}
