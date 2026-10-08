using System.IO;
using Maxiems.Deployer.Infrastructure.Payload;

namespace Maxiems.Deployer.App.Services;

public sealed class EmbeddedReleaseIdProvider(IDeploymentPayloadProvider payloadProvider)
{
    public async Task<string?> GetAsync(CancellationToken cancellationToken = default)
    {
        string? path = null;
        try
        {
            path = await payloadProvider.MaterializeAsync(cancellationToken).ConfigureAwait(false);
            var verification = PayloadVerifier.VerifyArchive(path);
            return verification.IsValid ? verification.ReleaseId : null;
        }
        finally
        {
            if (path is not null && File.Exists(path))
                File.Delete(path);
        }
    }
}
