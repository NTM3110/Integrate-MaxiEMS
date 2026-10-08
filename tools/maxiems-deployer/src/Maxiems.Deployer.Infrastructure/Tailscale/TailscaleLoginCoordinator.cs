using System.Text.Json;
using System.Text.RegularExpressions;

namespace Maxiems.Deployer.Infrastructure.Tailscale;

public interface ITailscaleNodeClient
{
    Task<string> StartLoginAsync(CancellationToken cancellationToken);

    Task<string> GetStatusJsonAsync(CancellationToken cancellationToken);
}

public sealed record TailscaleLoginResult(bool IsAuthenticated, Uri? LoginUri, TimeSpan Duration);

public sealed partial class TailscaleLoginCoordinator(
    ITailscaleNodeClient client,
    TimeProvider? timeProvider = null)
{
    private readonly TimeProvider timeProvider = timeProvider ?? TimeProvider.System;

    public async Task<TailscaleLoginResult> LoginAsync(
        TimeSpan timeout,
        TimeSpan pollInterval,
        Action<Uri>? loginUriAvailable = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(timeout, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(pollInterval, TimeSpan.Zero);
        var started = this.timeProvider.GetUtcNow();
        var output = await client.StartLoginAsync(cancellationToken).ConfigureAwait(false);
        var match = LoginUrlRegex().Match(output);
        var loginUri = match.Success && Uri.TryCreate(match.Value, UriKind.Absolute, out var parsed) ? parsed : null;
        if (loginUri is not null)
            loginUriAvailable?.Invoke(loginUri);
        var deadline = started + timeout;
        do
        {
            if (IsAuthenticated(await client.GetStatusJsonAsync(cancellationToken).ConfigureAwait(false)))
            {
                return new(true, loginUri, this.timeProvider.GetUtcNow() - started);
            }
            var remaining = deadline - this.timeProvider.GetUtcNow();
            if (remaining <= TimeSpan.Zero)
                break;
            await Task.Delay(pollInterval < remaining ? pollInterval : remaining, this.timeProvider, cancellationToken)
                .ConfigureAwait(false);
        } while (this.timeProvider.GetUtcNow() < deadline);
        return new(false, loginUri, this.timeProvider.GetUtcNow() - started);
    }

    private static bool IsAuthenticated(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            return root.TryGetProperty("BackendState", out var backend)
                && backend.ValueKind == JsonValueKind.String
                && backend.GetString() == "Running"
                && root.TryGetProperty("Self", out var self)
                && self.TryGetProperty("Online", out var online)
                && online.ValueKind is JsonValueKind.True;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    [GeneratedRegex(@"https://login\.tailscale\.com/[A-Za-z0-9_/-]+", RegexOptions.CultureInvariant)]
    private static partial Regex LoginUrlRegex();
}
