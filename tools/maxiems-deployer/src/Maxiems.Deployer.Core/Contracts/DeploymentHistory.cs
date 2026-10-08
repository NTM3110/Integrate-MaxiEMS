namespace Maxiems.Deployer.Core.Contracts;

public sealed record ReportReference(
    string JsonReportPath,
    string HtmlReportPath)
{
    public string? JsonSha256
    {
        get; init;
    }
    public string? HtmlSha256
    {
        get; init;
    }
    public int FormatVersion { get; init; } = 1;
}

public enum ReportIntegrityState
{
    Untracked,
    Verified,
    Missing,
    Changed,
}

public sealed record ReportIntegrityResult(
    ReportIntegrityState State,
    bool JsonPresent,
    bool HtmlPresent,
    bool JsonHashMatches,
    bool HtmlHashMatches);

public sealed record DeploymentHistorySummary(
    Guid DeploymentId,
    string TargetDisplayName,
    ReleaseId ReleaseId,
    DeploymentFinalState FinalState,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    TimeSpan Duration,
    ReportReference Reports);
