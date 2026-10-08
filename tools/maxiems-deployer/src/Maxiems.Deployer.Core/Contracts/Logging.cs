using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public enum DeploymentLogLevel
{
    Trace,
    Information,
    Warning,
    Error,
}

public sealed record RedactedLogProperty(
    string Name,
    string DisplayValue,
    bool WasRedacted);

public sealed record RedactedLogEvent(
    DateTimeOffset Timestamp,
    DeploymentLogLevel Level,
    string EventCode,
    string Message,
    ImmutableArray<RedactedLogProperty> Properties);
