using System.IO;
using Maxiems.Deployer.Infrastructure.Payload;

namespace Maxiems.Deployer.App.Services;

public static class EmbeddedPayloadVerifier
{
    public static async Task<int> RunAsync(CancellationToken cancellationToken)
    {
        string? path = null;
        try
        {
            path = await new EmbeddedPayloadProvider().MaterializeAsync(cancellationToken).ConfigureAwait(false);
            return PayloadVerifier.VerifyArchive(path).IsValid ? 0 : 2;
        }
        catch
        {
            return 2;
        }
        finally
        {
            if (path is not null && File.Exists(path))
                File.Delete(path);
        }
    }
}
