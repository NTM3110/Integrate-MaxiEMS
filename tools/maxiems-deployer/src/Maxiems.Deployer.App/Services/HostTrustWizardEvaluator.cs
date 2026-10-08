using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.App.Services;

public sealed class HostTrustWizardEvaluator(
    HostTrustCoordinator coordinator,
    TimeSpan timeout) : IWizardTrustEvaluator
{
    public Task<HostTrustDecision> EvaluateAsync(
        HostEndpoint endpoint,
        string username,
        CancellationToken cancellationToken) =>
        coordinator.EvaluateAsync(endpoint, username, timeout, cancellationToken: cancellationToken);

    public async Task ConfirmAsync(HostTrustDecision decision, CancellationToken cancellationToken) =>
        _ = await coordinator.ConfirmAsync(decision, cancellationToken).ConfigureAwait(false);
}
