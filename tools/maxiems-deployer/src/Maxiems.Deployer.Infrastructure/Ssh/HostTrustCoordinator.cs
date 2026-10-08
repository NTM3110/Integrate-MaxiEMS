using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class HostTrustCoordinator(IHostKeyProbe probe, HostTrustStore store)
{
    private readonly IHostKeyProbe probe = probe ?? throw new ArgumentNullException(nameof(probe));
    private readonly HostTrustStore store = store ?? throw new ArgumentNullException(nameof(store));

    public async Task<HostTrustDecision> EvaluateAsync(
        HostEndpoint endpoint,
        string username,
        TimeSpan timeout,
        SshHostFingerprint? suppliedFingerprint = null,
        CancellationToken cancellationToken = default)
    {
        var observed = await this.probe.ProbeAsync(endpoint, username, timeout, cancellationToken).ConfigureAwait(false);
        var stored = await this.store.FindAsync(endpoint, cancellationToken).ConfigureAwait(false);
        var previous = suppliedFingerprint ?? (stored is null
            ? null
            : new SshHostFingerprint(stored.Algorithm, stored.Fingerprint));
        var status = previous is null
            ? HostTrustStatus.FirstSeen
            : SshFingerprint.FixedEquals(previous, observed)
                ? HostTrustStatus.KnownMatch
                : HostTrustStatus.Changed;
        if (status == HostTrustStatus.KnownMatch && suppliedFingerprint is not null && stored is null)
        {
            await this.store.ConfirmAsync(endpoint, observed, cancellationToken).ConfigureAwait(false);
        }
        return new(endpoint, status, observed, previous);
    }

    public Task<TrustedHostRecord> ConfirmAsync(
        HostTrustDecision decision,
        CancellationToken cancellationToken = default) =>
        this.store.ConfirmAsync(decision.Endpoint, decision.ObservedFingerprint, cancellationToken);
}
