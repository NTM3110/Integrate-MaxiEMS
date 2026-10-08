using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Manufacturing;

public enum TargetServiceOwner
{
    None,
    MaxiemsManaged,
    LegacyOpenEms,
    External,
}

public sealed record TargetPortState(int Port, TargetServiceOwner Owner, string Listener);

public sealed record TargetInstallationState(
    ReleaseId? CurrentReleaseId,
    string? CurrentVersion,
    bool ManagedServiceActive,
    bool ManagedServiceEnabled,
    bool LegacyServiceActive,
    bool LegacyServiceEnabled,
    bool ConfigurationPresent,
    bool RollbackAvailable,
    ImmutableArray<TargetPortState> Ports);

public sealed record DeploymentPlanningRequest(
    TargetInstallationState Target,
    LocalReleaseDescriptor DesiredRelease,
    bool ReleaseCompatible,
    bool IsDowngrade,
    bool AdvancedDowngradeEnabled,
    bool DowngradeConfirmed,
    string? DowngradeReason);

public static class ManufacturingDeploymentActionPlanner
{
    public static DeploymentActionPlan Plan(DeploymentPlanningRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        var blockers = ImmutableArray.CreateBuilder<string>();
        if (!request.ReleaseCompatible)
            blockers.Add("Selected release is incompatible with the inspected target.");
        foreach (var port in request.Target.Ports.Where(port => port.Owner == TargetServiceOwner.External)
            .OrderBy(port => port.Port))
        {
            blockers.Add($"Required port {port.Port} is owned by an external service.");
        }
        if (request.IsDowngrade)
        {
            if (!request.AdvancedDowngradeEnabled)
                blockers.Add("Downgrade is available only in Advanced actions.");
            if (!request.DowngradeConfirmed)
                blockers.Add("Downgrade requires explicit confirmation.");
            if (string.IsNullOrWhiteSpace(request.DowngradeReason))
                blockers.Add("Downgrade requires a recorded reason.");
        }
        if (blockers.Count > 0)
            return Build(DeploymentActionKind.BlockedConflict, request, blockers.ToImmutable());

        var action = SelectAction(request);
        return Build(action, request, []);
    }

    private static DeploymentActionKind SelectAction(DeploymentPlanningRequest request)
    {
        if (request.Target.LegacyServiceActive || request.Target.LegacyServiceEnabled)
        {
            return DeploymentActionKind.LegacyMigration;
        }
        if (request.Target.CurrentReleaseId is null)
            return DeploymentActionKind.Deploy;
        if (request.Target.CurrentReleaseId == request.DesiredRelease.ReleaseId)
            return DeploymentActionKind.NoChange;
        return request.IsDowngrade ? DeploymentActionKind.Downgrade : DeploymentActionKind.Update;
    }

    private static DeploymentActionPlan Build(DeploymentActionKind action, DeploymentPlanningRequest request,
        ImmutableArray<string> blockers)
    {
        var changes = ImmutableArray.CreateBuilder<string>();
        if (action == DeploymentActionKind.NoChange)
        {
            changes.Add("Verify the installed payload and service readiness without replacing the release.");
        }
        else if (action != DeploymentActionKind.BlockedConflict)
        {
            changes.Add($"Activate release {request.DesiredRelease.ReleaseId.Value}.");
            if (request.Target.ConfigurationPresent)
                changes.Add("Preserve the existing OpenEMS configuration.");
            if (action == DeploymentActionKind.LegacyMigration)
            {
                changes.Add("Stop and disable the legacy openems.service before activating the managed service.");
            }
            if (action == DeploymentActionKind.Downgrade)
                changes.Add("Record the confirmed downgrade reason.");
        }
        return new(action, request.Target.CurrentReleaseId, request.DesiredRelease.ReleaseId,
            request.Target.RollbackAvailable, blockers, changes.ToImmutable());
    }
}

public sealed record TargetIdentityExpectation(
    string InspectionSha256,
    ImmutableArray<MacAddress> PermanentMacAddresses);
