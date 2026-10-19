using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Preflight;

public sealed class UbuntuPreflightEvaluator
{
    public const long MinimumDiskBytes = 2L * 1024 * 1024 * 1024;
    public const long MinimumMemoryBytes = 512L * 1024 * 1024;
    public static readonly TimeSpan MaximumClockSkew = TimeSpan.FromMinutes(5);

    private static readonly Dictionary<int, Func<UbuntuPreflightSnapshot, bool>> AllowedExistingServices = new()
    {
        [80] = snapshot => snapshot.NginxActive,
        [443] = snapshot => snapshot.NginxActive,
        [8080] = snapshot => snapshot.OpenEmsActive || snapshot.LegacyOpenEmsActive,
        [8084] = snapshot => snapshot.OpenEmsActive || snapshot.LegacyOpenEmsActive,
        [8085] = snapshot => snapshot.OpenEmsActive || snapshot.LegacyOpenEmsActive,
    };

    public static UbuntuPreflightResult Evaluate(
        UbuntuPreflightSnapshot snapshot,
        DateTimeOffset localTime,
        bool requiresModbusPort = true)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        var issues = ImmutableArray.CreateBuilder<PreflightIssue>();
        var warnings = ImmutableArray.CreateBuilder<PreflightIssue>();
        if (!snapshot.OperatingSystemId.Equals("ubuntu", StringComparison.OrdinalIgnoreCase))
        {
            issues.Add(new(PreflightIssueCode.UnsupportedOperatingSystem, snapshot.OperatingSystemId));
        }
        if (!snapshot.VersionId.Equals("24.04", StringComparison.Ordinal))
        {
            issues.Add(new(PreflightIssueCode.UnsupportedVersion, snapshot.VersionId));
        }
        var architecture = ParseArchitecture(snapshot.Architecture);
        if (architecture is null)
        {
            issues.Add(new(PreflightIssueCode.UnsupportedArchitecture, snapshot.Architecture));
        }
        if (snapshot.FreeDiskBytes < MinimumDiskBytes)
        {
            issues.Add(new(PreflightIssueCode.InsufficientDisk, snapshot.FreeDiskBytes.ToString(
                System.Globalization.CultureInfo.InvariantCulture)));
        }
        if (snapshot.TotalMemoryBytes < MinimumMemoryBytes)
        {
            issues.Add(new(PreflightIssueCode.InsufficientMemory, snapshot.TotalMemoryBytes.ToString(
                System.Globalization.CultureInfo.InvariantCulture)));
        }
        if ((snapshot.RemoteTime - localTime).Duration() > MaximumClockSkew)
        {
            warnings.Add(new(PreflightIssueCode.ClockSkew, snapshot.RemoteTime.ToString("O")));
        }
        if (!snapshot.NonInteractiveSudoAvailable)
        {
            issues.Add(new(PreflightIssueCode.NonInteractiveSudoUnavailable, "sudo -n"));
        }
        foreach (var listener in snapshot.Listeners.Where(listener =>
            AllowedExistingServices.ContainsKey(listener.Port) || requiresModbusPort && listener.Port == 502))
        {
            var owned = listener.Port == 502
                ? snapshot.OpenEmsActive || snapshot.LegacyOpenEmsActive
                : AllowedExistingServices[listener.Port](snapshot);
            if (!owned)
            {
                issues.Add(new(PreflightIssueCode.RequiredPortOccupied, listener.Port.ToString(
                    System.Globalization.CultureInfo.InvariantCulture)));
            }
        }
        EvaluateAccount(snapshot.ExistingAccounts, "maxiems-deploy", "/var/lib/maxiems-deploy", "/bin/bash", issues);
        EvaluateAccount(snapshot.ExistingAccounts, "openems", "/var/lib/openems", "/usr/sbin/nologin", issues);
        return new(issues.Count == 0, architecture, issues.ToImmutable())
        {
            Warnings = warnings.ToImmutable()
        };
    }

    private static SupportedArchitecture? ParseArchitecture(string value) => value switch
    {
        "amd64" => SupportedArchitecture.Amd64,
        "arm64" => SupportedArchitecture.Arm64,
        _ => null,
    };

    private static void EvaluateAccount(
        ImmutableArray<LinuxAccount> accounts,
        string name,
        string home,
        string shell,
        ImmutableArray<PreflightIssue>.Builder issues)
    {
        var account = accounts.SingleOrDefault(account => account.Name.Equals(name, StringComparison.Ordinal));
        if (account is not null && (account.UserId is <= 0 or >= 1000
            || !(account.HomeDirectory.Equals(home, StringComparison.Ordinal)
                || name == "openems" && account.HomeDirectory == "/var/lib/maxiems")
            || !account.Shell.Equals(shell, StringComparison.Ordinal)))
        {
            issues.Add(new(PreflightIssueCode.IncompatibleAccount, name));
        }
    }
}
