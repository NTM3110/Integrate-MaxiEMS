using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Lifecycle;

public static class DeploymentTransitionPolicy
{
    private static readonly ImmutableDictionary<DeploymentState, ImmutableHashSet<DeploymentState>> Allowed =
        new Dictionary<DeploymentState, DeploymentState[]>
        {
            [DeploymentState.ValidateInput] = [DeploymentState.ConnectBootstrap, DeploymentState.Cancelled, DeploymentState.Failed],
            [DeploymentState.ConnectBootstrap] = [DeploymentState.Preflight, DeploymentState.Cancelled, DeploymentState.Failed],
            [DeploymentState.Preflight] = [DeploymentState.ProvisionIdentity, DeploymentState.Cancelled, DeploymentState.Failed],
            [DeploymentState.ProvisionIdentity] = [DeploymentState.ReconnectRestricted, DeploymentState.Rollback],
            [DeploymentState.ReconnectRestricted] = [DeploymentState.InstallPrerequisites, DeploymentState.Rollback],
            [DeploymentState.InstallPrerequisites] = [DeploymentState.StageRelease, DeploymentState.Rollback],
            [DeploymentState.StageRelease] = [DeploymentState.VerifyRemotePayload, DeploymentState.Rollback],
            [DeploymentState.VerifyRemotePayload] = [DeploymentState.ActivateRelease, DeploymentState.Rollback],
            [DeploymentState.ActivateRelease] = [DeploymentState.StartServices, DeploymentState.Rollback],
            [DeploymentState.StartServices] = [DeploymentState.ConfigureTailscale, DeploymentState.Rollback],
            [DeploymentState.ConfigureTailscale] = [DeploymentState.ReconcileComponents, DeploymentState.Rollback],
            [DeploymentState.ReconcileComponents] = [DeploymentState.HealthCheck, DeploymentState.Rollback],
            [DeploymentState.HealthCheck] = [DeploymentState.Complete, DeploymentState.Rollback],
            [DeploymentState.Rollback] = [DeploymentState.RolledBack, DeploymentState.Failed],
        }.ToImmutableDictionary(
            entry => entry.Key,
            entry => entry.Value.ToImmutableHashSet());

    public static bool CanTransition(DeploymentState from, DeploymentState to) =>
        Allowed.TryGetValue(from, out var targets) && targets.Contains(to);

    public static void EnsureTransition(DeploymentState from, DeploymentState to)
    {
        if (!CanTransition(from, to))
        {
            throw new InvalidOperationException($"Deployment transition {from} -> {to} is forbidden.");
        }
    }
}
