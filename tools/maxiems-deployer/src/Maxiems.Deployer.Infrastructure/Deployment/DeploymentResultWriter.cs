using System.Security.Cryptography;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Reporting;
using Maxiems.Deployer.Core.Security;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal static class DeploymentResultWriter
{
    public static async Task<string> HashAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false))
            .ToLowerInvariant();
    }

    public static async Task<DeploymentExecutionResult> FinishAsync(
        DeploymentRunBuilder run,
        DeploymentFinalState state,
        string payloadHash,
        string directory,
        RollbackAudit? audit = null,
        ManufacturingReportContext? manufacturing = null)
    {
        var record = run.Finish(state, payloadHash, audit, manufacturing);
        var exporter = new DeploymentReportExporter(new DeploymentReportSerializer(new SecretRedactor()));
        var reports = await exporter.ExportAsync(record, directory, CancellationToken.None).ConfigureAwait(false);
        var reference = new ReportReference(reports.JsonPath, reports.HtmlPath)
        {
            JsonSha256 = await ReportIntegrityVerifier.HashAsync(reports.JsonPath).ConfigureAwait(false),
            HtmlSha256 = await ReportIntegrityVerifier.HashAsync(reports.HtmlPath).ConfigureAwait(false),
            FormatVersion = record.ReportFormatVersion,
        };
        return new(record, reports.JsonPath, reports.HtmlPath)
        {
            Reports = reference
        };
    }
}
