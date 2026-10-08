using System.Security.Cryptography;

namespace Maxiems.Deployer.Infrastructure.Preflight;

public static class BootstrapSignatureVerifier
{
    public static bool Verify(ReadOnlySpan<byte> manifest, ReadOnlySpan<byte> signature, string publicKeyPem)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(publicKeyPem);
        try
        {
            using var rsa = RSA.Create();
            rsa.ImportFromPem(publicKeyPem);
            return rsa.VerifyData(manifest, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pss);
        }
        catch (Exception exception) when (exception is CryptographicException or ArgumentException)
        {
            return false;
        }
    }
}
