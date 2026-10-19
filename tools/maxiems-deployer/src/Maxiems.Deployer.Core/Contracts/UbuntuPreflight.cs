using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public sealed record PortListener(int Port, string LocalAddress);

public sealed record LinuxAccount(
    string Name,
    int UserId,
    string HomeDirectory,
    string Shell);

public sealed record UbuntuPreflightSnapshot(
    string OperatingSystemId,
    string VersionId,
    string Architecture,
    long FreeDiskBytes,
    long TotalMemoryBytes,
    DateTimeOffset RemoteTime,
    bool DnsAvailable,
    bool NonInteractiveSudoAvailable,
    ImmutableArray<PortListener> Listeners,
    bool OpenEmsActive,
    bool NginxActive,
    bool LegacyOpenEmsActive,
    bool LegacyOpenEmsEnabled,
    ImmutableArray<LinuxAccount> ExistingAccounts);

public enum PreflightIssueCode
{
    UnsupportedOperatingSystem,
    UnsupportedVersion,
    UnsupportedArchitecture,
    InsufficientDisk,
    InsufficientMemory,
    ClockSkew,
    DnsUnavailable,
    NonInteractiveSudoUnavailable,
    RequiredPortOccupied,
    IncompatibleAccount,
}

public sealed record PreflightIssue(PreflightIssueCode Code, string Subject);

public sealed record UbuntuPreflightResult(
    bool IsSupported,
    SupportedArchitecture? Architecture,
    ImmutableArray<PreflightIssue> Issues)
{
    public ImmutableArray<PreflightIssue> Warnings { get; init; } = [];
}
