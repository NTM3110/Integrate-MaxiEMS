using System.Security.Cryptography;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Core.Reporting;

public static class ReportIntegrityVerifier
{
    public static async Task<ReportIntegrityResult> VerifyAsync(ReportReference reports,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(reports);
        var jsonPresent = File.Exists(reports.JsonReportPath);
        var htmlPresent = File.Exists(reports.HtmlReportPath);
        if (!jsonPresent || !htmlPresent)
            return new(ReportIntegrityState.Missing, jsonPresent, htmlPresent, false, false);
        if (reports.JsonSha256 is null || reports.HtmlSha256 is null)
        {
            return new(ReportIntegrityState.Untracked, true, true, false, false);
        }
        var jsonMatches = reports.JsonSha256.Equals(await HashAsync(reports.JsonReportPath, cancellationToken)
            .ConfigureAwait(false), StringComparison.OrdinalIgnoreCase);
        var htmlMatches = reports.HtmlSha256.Equals(await HashAsync(reports.HtmlReportPath, cancellationToken)
            .ConfigureAwait(false), StringComparison.OrdinalIgnoreCase);
        return new(jsonMatches && htmlMatches ? ReportIntegrityState.Verified : ReportIntegrityState.Changed,
            true, true, jsonMatches, htmlMatches);
    }

    public static async Task<string> HashAsync(string path, CancellationToken cancellationToken = default)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false))
            .ToLowerInvariant();
    }
}
