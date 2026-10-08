using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed class HostTrustStore
{
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> Gates = new(StringComparer.OrdinalIgnoreCase);
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };

    private readonly string path;
    private readonly TimeProvider timeProvider;

    public HostTrustStore(string path, TimeProvider? timeProvider = null)
    {
        this.path = Path.GetFullPath(path);
        this.timeProvider = timeProvider ?? TimeProvider.System;
    }

    public async Task<TrustedHostRecord?> FindAsync(HostEndpoint endpoint, CancellationToken cancellationToken = default)
    {
        var endpointKey = SshEndpointNormalizer.Normalize(endpoint);
        var gate = Gates.GetOrAdd(this.path, _ => new(1, 1));
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var records = await this.LoadAsync(cancellationToken).ConfigureAwait(false);
            return records.SingleOrDefault(record => record.EndpointKey.Equals(endpointKey, StringComparison.Ordinal));
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task<TrustedHostRecord> ConfirmAsync(
        HostEndpoint endpoint,
        SshHostFingerprint fingerprint,
        CancellationToken cancellationToken = default)
    {
        var endpointKey = SshEndpointNormalizer.Normalize(endpoint);
        var gate = Gates.GetOrAdd(this.path, _ => new(1, 1));
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var records = (await this.LoadAsync(cancellationToken).ConfigureAwait(false)).ToList();
            var previous = records.SingleOrDefault(record => record.EndpointKey.Equals(endpointKey, StringComparison.Ordinal));
            var now = this.timeProvider.GetUtcNow();
            var replacement = previous is not null &&
                (!previous.Algorithm.Equals(fingerprint.Algorithm, StringComparison.Ordinal)
                    || !previous.Fingerprint.Equals(fingerprint.Sha256Value, StringComparison.Ordinal))
                ? new HostKeyReplacementAudit(previous.Algorithm, previous.Fingerprint, now)
                : previous?.Replacement;
            var confirmed = new TrustedHostRecord(
                endpointKey,
                fingerprint.Algorithm,
                fingerprint.Sha256Value,
                previous?.FirstSeenAt ?? now,
                now,
                replacement);
            records.RemoveAll(record => record.EndpointKey.Equals(endpointKey, StringComparison.Ordinal));
            records.Add(confirmed);
            await this.SaveAsync(records.OrderBy(record => record.EndpointKey), cancellationToken).ConfigureAwait(false);
            return confirmed;
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<ImmutableArray<TrustedHostRecord>> LoadAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(this.path))
        {
            return [];
        }
        try
        {
            await using var stream = File.OpenRead(this.path);
            var document = await JsonSerializer.DeserializeAsync<TrustStoreDocument>(
                stream, SerializerOptions, cancellationToken).ConfigureAwait(false);
            if (document is null || document.FormatVersion != 1 || document.Hosts is null)
            {
                throw new JsonException("Unsupported or empty SSH trust store.");
            }
            if (document.Hosts.Any(record => string.IsNullOrWhiteSpace(record.EndpointKey)
                    || string.IsNullOrWhiteSpace(record.Algorithm)
                    || string.IsNullOrWhiteSpace(record.Fingerprint))
                || document.Hosts.GroupBy(record => record.EndpointKey, StringComparer.Ordinal).Any(group => group.Count() > 1))
            {
                throw new JsonException("SSH trust store contains malformed or duplicate records.");
            }
            return document.Hosts.ToImmutableArray();
        }
        catch (JsonException)
        {
            var quarantine = $"{this.path}.corrupt-{this.timeProvider.GetUtcNow():yyyyMMddHHmmssfff}";
            File.Move(this.path, quarantine, false);
            return [];
        }
    }

    private async Task SaveAsync(IEnumerable<TrustedHostRecord> records, CancellationToken cancellationToken)
    {
        var directory = Path.GetDirectoryName(this.path)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(this.path)}.{Guid.NewGuid():N}.tmp");
        try
        {
            await using (var stream = new FileStream(
                temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(
                    stream,
                    new TrustStoreDocument(1, records.ToArray()),
                    SerializerOptions,
                    cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            File.Move(temporaryPath, this.path, true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private sealed record TrustStoreDocument(int FormatVersion, TrustedHostRecord[] Hosts);
}
