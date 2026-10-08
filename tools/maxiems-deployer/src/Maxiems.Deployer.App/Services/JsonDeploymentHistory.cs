using System.Collections.Concurrent;
using System.IO;
using System.Text.Json;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App.Services;

public sealed class JsonDeploymentHistory(string path, TimeProvider? timeProvider = null) : IWizardHistory
{
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> Gates = new(StringComparer.OrdinalIgnoreCase);
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };
    private readonly string path = Path.GetFullPath(path);
    private readonly TimeProvider timeProvider = timeProvider ?? TimeProvider.System;

    public async Task<IReadOnlyList<DeploymentHistorySummary>> LoadAsync(CancellationToken cancellationToken)
    {
        var gate = Gates.GetOrAdd(this.path, _ => new(1, 1));
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return await this.LoadUnsafeAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task AppendAsync(DeploymentHistorySummary summary, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(summary);
        var gate = Gates.GetOrAdd(this.path, _ => new(1, 1));
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var entries = (await this.LoadUnsafeAsync(cancellationToken).ConfigureAwait(false)).ToList();
            entries.RemoveAll(item => item.DeploymentId == summary.DeploymentId);
            entries.Add(summary);
            await this.SaveUnsafeAsync(entries.OrderByDescending(item => item.StartedAt), cancellationToken)
                .ConfigureAwait(false);
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<IReadOnlyList<DeploymentHistorySummary>> LoadUnsafeAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(this.path))
            return [];
        try
        {
            await using var stream = File.OpenRead(this.path);
            var document = await JsonSerializer.DeserializeAsync<HistoryDocument>(stream, Options, cancellationToken)
                .ConfigureAwait(false);
            if (document is null || document.FormatVersion != 1 || document.Entries is null
                || document.Entries.GroupBy(item => item.DeploymentId).Any(group => group.Count() != 1))
            {
                throw new JsonException("Deployment history is malformed.");
            }
            return document.Entries.OrderByDescending(item => item.StartedAt).ToArray();
        }
        catch (JsonException)
        {
            var quarantine = $"{this.path}.corrupt-{this.timeProvider.GetUtcNow():yyyyMMddHHmmssfff}";
            File.Move(this.path, quarantine, false);
            return [];
        }
    }

    private async Task SaveUnsafeAsync(
        IEnumerable<DeploymentHistorySummary> entries,
        CancellationToken cancellationToken)
    {
        var directory = Path.GetDirectoryName(this.path)!;
        Directory.CreateDirectory(directory);
        var temporary = Path.Combine(directory, $".{Path.GetFileName(this.path)}.{Guid.NewGuid():N}.tmp");
        try
        {
            await using (var stream = new FileStream(
                temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, new HistoryDocument(1, entries.ToArray()), Options,
                    cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            File.Move(temporary, this.path, true);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }

    private sealed record HistoryDocument(int FormatVersion, DeploymentHistorySummary[] Entries);
}
