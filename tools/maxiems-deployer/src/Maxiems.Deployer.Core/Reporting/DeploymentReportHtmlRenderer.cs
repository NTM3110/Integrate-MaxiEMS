using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Maxiems.Deployer.Core.Reporting;

internal static class DeploymentReportHtmlRenderer
{
    public static string Render(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        var body = new StringBuilder();
        body.Append("<h1>MAXiEMS deployment report</h1>");
        Section(body, "Summary", Rows(root, "deploymentId", "finalState", "startedAt", "completedAt", "duration"));
        if (root.TryGetProperty("manufacturing", out var manufacturing)
            && manufacturing.ValueKind == JsonValueKind.Object)
        {
            Section(body, "Device", Rows(manufacturing, "deviceId", "serial", "endpoint", "inspectionId",
                "inspectionSha256", "hardwareSnapshotSha256"));
            Section(body, "Profile and action", Rows(manufacturing, "profile", "plannedAction", "observedAction",
                "engineer", "deployerVersion", "gitCommit", "localTimeZoneId", "relatedRunId", "runRelation"));
            if (manufacturing.TryGetProperty("release", out var release))
                Section(body, "Release", ObjectRows(release));
            if (manufacturing.TryGetProperty("ethernetPorts", out var ethernet))
                ArrayTable(body, "Ethernet ports", ethernet);
            if (manufacturing.TryGetProperty("checklist", out var checklist)
                && checklist.TryGetProperty("results", out var results))
                ArrayTable(body, "Hardware checklist", results);
        }
        if (root.TryGetProperty("phases", out var phases))
            ArrayTable(body, "Deployment phases", phases);
        if (root.TryGetProperty("logs", out var logs) && logs.ValueKind == JsonValueKind.Array)
        {
            var warnings = logs.EnumerateArray().Where(item => item.ValueKind == JsonValueKind.Object
                && item.TryGetProperty("level", out var level) && level.GetString() == "warning"
                && item.TryGetProperty("eventCode", out var code)
                && code.GetString()?.StartsWith("preflight.", StringComparison.Ordinal) == true).ToArray();
            if (warnings.Length > 0)
                ArrayTable(body, "Preflight warnings", warnings);
        }
        if (root.TryGetProperty("checks", out var checks))
            ArrayTable(body, "Automated acceptance", checks);
        if (root.TryGetProperty("rollback", out var rollback) && rollback.ValueKind == JsonValueKind.Object)
        {
            Section(body, "Rollback", ObjectRows(rollback));
        }
        return $$"""
			<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
			<title>MAXiEMS deployment report</title><style>
			body{font:14px Segoe UI,sans-serif;margin:2rem;color:#18212b;background:#f7f9fb}main{max-width:1180px;margin:auto}
			h1{color:#123b5d}section{background:white;padding:1rem 1.25rem;margin:1rem 0;border-radius:10px;box-shadow:0 1px 4px #0002}
			table{width:100%;border-collapse:collapse}th,td{text-align:left;vertical-align:top;padding:.55rem;border-bottom:1px solid #dde4ea;overflow-wrap:anywhere}
			th{color:#31556f;background:#eef4f8}.key{width:24%}</style></head><body><main>{{body}}</main></body></html>
			""";
    }

    private static IEnumerable<(string Key, string Value)> Rows(JsonElement value, params string[] names) => names
        .Where(name => value.TryGetProperty(name, out _)).Select(name => (name, Display(value.GetProperty(name))));
    private static IEnumerable<(string Key, string Value)> ObjectRows(JsonElement value) => value.EnumerateObject()
        .Select(property => (property.Name, Display(property.Value)));
    private static void Section(StringBuilder html, string title, IEnumerable<(string Key, string Value)> rows)
    {
        html.Append("<section><h2>").Append(Encode(title)).Append("</h2><table><tbody>");
        foreach (var (key, value) in rows)
            html.Append("<tr><th class=\"key\">").Append(Encode(key))
            .Append("</th><td>").Append(Encode(value)).Append("</td></tr>");
        html.Append("</tbody></table></section>");
    }
    private static void ArrayTable(StringBuilder html, string title, JsonElement array)
    {
        if (array.ValueKind != JsonValueKind.Array)
            return;
        ArrayTable(html, title, array.EnumerateArray().Where(item => item.ValueKind == JsonValueKind.Object).ToArray());
    }
    private static void ArrayTable(StringBuilder html, string title, IReadOnlyList<JsonElement> rows)
    {
        var columns = rows.SelectMany(row => row.EnumerateObject().Select(property => property.Name))
            .Distinct(StringComparer.Ordinal).ToArray();
        html.Append("<section><h2>").Append(Encode(title)).Append("</h2><table><thead><tr>");
        foreach (var column in columns)
            html.Append("<th>").Append(Encode(column)).Append("</th>");
        html.Append("</tr></thead><tbody>");
        foreach (var row in rows)
        {
            html.Append("<tr>");
            foreach (var column in columns)
                html.Append("<td>").Append(Encode(row.TryGetProperty(column, out var cell)
                ? Display(cell) : string.Empty)).Append("</td>");
            html.Append("</tr>");
        }
        html.Append("</tbody></table></section>");
    }
    private static string Display(JsonElement value) => value.ValueKind == JsonValueKind.String
        ? value.GetString() ?? string.Empty : value.GetRawText();
    private static string Encode(string value) => HtmlEncoder.Default.Encode(value);
}
