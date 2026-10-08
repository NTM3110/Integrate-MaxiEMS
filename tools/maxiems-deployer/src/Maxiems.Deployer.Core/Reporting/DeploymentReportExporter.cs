using Maxiems.Deployer.Core.Lifecycle;

namespace Maxiems.Deployer.Core.Reporting;

public sealed class DeploymentReportExporter(DeploymentReportSerializer serializer)
{
    public async Task<(string JsonPath, string HtmlPath)> ExportAsync(
        DeploymentRunRecord record,
        string directory,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(record);
        Directory.CreateDirectory(directory);
        var json = serializer.Serialize(record);
        var stem = $"deployment-{record.DeploymentId:N}";
        var jsonPath = Path.Combine(directory, $"{stem}.json");
        var htmlPath = Path.Combine(directory, $"{stem}.html");
        await WriteAtomicAsync(jsonPath, json, cancellationToken).ConfigureAwait(false);
        var html = DeploymentReportHtmlRenderer.Render(json);
        await WriteAtomicAsync(htmlPath, html, cancellationToken).ConfigureAwait(false);
        return (jsonPath, htmlPath);
    }

    private static async Task WriteAtomicAsync(string path, string content, CancellationToken cancellationToken)
    {
        var temporary = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            await File.WriteAllTextAsync(temporary, content, cancellationToken).ConfigureAwait(false);
            File.Move(temporary, path, true);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }
}
