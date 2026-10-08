using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed record HostKeyReplacementAudit(
    string PreviousAlgorithm,
    string PreviousFingerprint,
    DateTimeOffset ReplacedAt);

public sealed record TrustedHostRecord(
    string EndpointKey,
    string Algorithm,
    string Fingerprint,
    DateTimeOffset FirstSeenAt,
    DateTimeOffset LastConfirmedAt,
    HostKeyReplacementAudit? Replacement);

public static class SshEndpointNormalizer
{
    public static string Normalize(HostEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        var host = endpoint.Host.Trim().TrimStart('[').TrimEnd(']').ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(host) || endpoint.Port is < 1 or > 65535)
        {
            throw new ArgumentException("SSH endpoint host and port are invalid.", nameof(endpoint));
        }
        return $"[{host}]:{endpoint.Port}";
    }
}
