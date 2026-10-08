using System.Collections.Immutable;
using Maxiems.Deployer.Core.Lifecycle;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record WarehouseAcceptanceResult(
    bool IsReady,
    ImmutableArray<InspectionCheckResult> Checks);

public static class ManufacturingWarehouseAcceptance
{
    private static readonly ImmutableArray<string> BaselineCodes = [
        "service-active-enabled", "nginx-ui", "openems-authenticated", "binding-policy", "release-integrity",
    ];

    public static WarehouseAcceptanceResult Evaluate(DeploymentRunRecord run,
        DeploymentActionKind action, bool requireReboot)
    {
        ArgumentNullException.ThrowIfNull(run);
        var required = BaselineCodes.AsEnumerable();
        if (action == DeploymentActionKind.LegacyMigration)
            required = required.Append("legacy-disabled");
        if (requireReboot)
            required = required.Append("reboot-persistence");
        var evidence = run.Checks.Concat(run.Evidence).GroupBy(check => check.Code, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.Last(), StringComparer.Ordinal);
        var checks = required.Select(code =>
        {
            var found = evidence.TryGetValue(code, out var item) && item.Passed;
            return new InspectionCheckResult($"acceptance.{code}", InspectionCheckRequirement.Required,
                found ? InspectionCheckOutcome.Passed : InspectionCheckOutcome.BlockingFailure,
                found ? item!.Summary : "Required verified evidence is missing or failed", "Passed", "deployment report");
        }).ToImmutableArray();
        return new(checks.All(check => check.Outcome == InspectionCheckOutcome.Passed), checks);
    }
}
