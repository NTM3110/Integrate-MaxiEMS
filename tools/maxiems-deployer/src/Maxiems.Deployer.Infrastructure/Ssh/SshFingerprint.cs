using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public static class SshFingerprint
{
    private static readonly string[] AllowedAlgorithms =
    [
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "rsa-sha2-256",
        "rsa-sha2-512",
        "ssh-rsa",
    ];

    public static SshHostFingerprint Create(string algorithm, string sha256WithoutPrefix)
    {
        if (!AllowedAlgorithms.Contains(algorithm, StringComparer.Ordinal))
        {
            throw new InvalidOperationException($"Unsupported SSH host-key algorithm: {algorithm}");
        }
        var value = sha256WithoutPrefix.StartsWith("SHA256:", StringComparison.Ordinal)
            ? sha256WithoutPrefix
            : $"SHA256:{sha256WithoutPrefix}";
        var encoded = value["SHA256:".Length..];
        if (string.IsNullOrWhiteSpace(encoded) || encoded.Any(character =>
            !(char.IsLetterOrDigit(character) || character is '+' or '/' or '_' or '-')))
        {
            throw new FormatException("SSH SHA-256 fingerprint is malformed.");
        }
        return new(algorithm, value);
    }

    public static bool FixedEquals(SshHostFingerprint left, SshHostFingerprint right)
    {
        var algorithmMatches = System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.UTF8.GetBytes(left.Algorithm),
            System.Text.Encoding.UTF8.GetBytes(right.Algorithm));
        var fingerprintMatches = System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.UTF8.GetBytes(left.Sha256Value),
            System.Text.Encoding.UTF8.GetBytes(right.Sha256Value));
        return algorithmMatches && fingerprintMatches;
    }
}
