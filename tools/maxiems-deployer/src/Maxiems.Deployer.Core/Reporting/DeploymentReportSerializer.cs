using System.Collections.Immutable;
using System.Text.Json;
using System.Text.Json.Serialization;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Security;

namespace Maxiems.Deployer.Core.Reporting;

public sealed class DeploymentReportSerializer(SecretRedactor redactor)
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
    };

    private readonly SecretRedactor redactor = redactor ?? throw new ArgumentNullException(nameof(redactor));

    public string Serialize(DeploymentRunRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        var safeRecord = record with
        {
            Phases = record.Phases.Select(this.Redact).ToImmutableArray(),
            Logs = record.Logs.Select(this.Redact).ToImmutableArray(),
            Checks = record.Checks.Select(this.Redact).ToImmutableArray(),
            Rollback = record.Rollback is { } rollback
                ? rollback with
                {
                    Summary = this.redactor.RedactFreeText(rollback.Summary)
                }
                : null,
            Evidence = record.Evidence.Select(this.Redact).ToImmutableArray(),
            Manufacturing = record.Manufacturing is null ? null : this.Redact(record.Manufacturing),
        };
        var serialized = JsonSerializer.Serialize(safeRecord, Options);
        return this.redactor.RedactFreeText(serialized);
    }

    public static DeploymentRunRecord Deserialize(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        var record = JsonSerializer.Deserialize<DeploymentRunRecord>(json, Options)
            ?? throw new JsonException("Deployment report is empty.");
        if (record.ReportFormatVersion is not (1 or 2))
        {
            throw new JsonException($"Unsupported deployment report format {record.ReportFormatVersion}.");
        }
        return record;
    }

    private DeploymentPhaseResult Redact(DeploymentPhaseResult result) => result with
    {
        OperatorMessage = this.redactor.RedactFreeText(result.OperatorMessage),
    };

    private DeploymentCheckEvidence Redact(DeploymentCheckEvidence evidence) => evidence with
    {
        Summary = Bound(this.redactor.RedactFreeText(evidence.Summary)),
    };

    private ManufacturingReportContext Redact(ManufacturingReportContext context) =>
        context with
        {
            Checklist = context.Checklist with
            {
                Results = context.Checklist.Results.Select(result => result with
                {
                    Observed = Bound(this.redactor.RedactFreeText(result.Observed)),
                    Expected = Bound(this.redactor.RedactFreeText(result.Expected)),
                    EvidenceSummary = Bound(this.redactor.RedactFreeText(result.EvidenceSummary)),
                }).ToImmutableArray()
            },
            DeployerVersion = Bound(this.redactor.RedactFreeText(context.DeployerVersion)),
            GitCommit = context.GitCommit is null ? null : Bound(this.redactor.RedactFreeText(context.GitCommit)),
            LocalTimeZoneId = Bound(this.redactor.RedactFreeText(context.LocalTimeZoneId)),
            RunRelation = context.RunRelation is null ? null : Bound(this.redactor.RedactFreeText(context.RunRelation)),
        };

    private RedactedLogEvent Redact(RedactedLogEvent logEvent) => logEvent with
    {
        Message = this.redactor.RedactFreeText(logEvent.Message),
        Properties = logEvent.Properties.Select(property => property with
        {
            DisplayValue = this.redactor.RedactValue(property.Name, property.DisplayValue),
            WasRedacted = property.WasRedacted
                || this.redactor.RedactValue(property.Name, property.DisplayValue) != property.DisplayValue,
        }).ToImmutableArray(),
    };
    private static string Bound(string value) => value.Length <= 4096 ? value : value[..4096] + " [TRUNCATED]";
}
