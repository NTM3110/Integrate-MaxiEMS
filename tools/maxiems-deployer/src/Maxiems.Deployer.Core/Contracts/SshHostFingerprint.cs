namespace Maxiems.Deployer.Core.Contracts;

public sealed record SshHostFingerprint(
    string Algorithm,
    string Sha256Value);

public enum HostTrustStatus
{
    KnownMatch,
    FirstSeen,
    Changed,
}

public sealed record HostTrustDecision(
    HostEndpoint Endpoint,
    HostTrustStatus Status,
    SshHostFingerprint ObservedFingerprint,
    SshHostFingerprint? PreviousFingerprint);
