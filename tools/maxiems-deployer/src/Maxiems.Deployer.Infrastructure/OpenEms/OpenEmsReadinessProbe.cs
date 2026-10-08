using System.Net;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class OpenEmsReadinessProbe(
    OpenEmsJsonRpcClient client,
    TimeProvider? timeProvider = null)
{
    private readonly TimeProvider timeProvider = timeProvider ?? TimeProvider.System;

    public async Task<IReadOnlyDictionary<string, OpenEmsComponentSnapshot>> WaitAsync(
        TimeSpan timeout,
        TimeSpan pollInterval,
        CancellationToken cancellationToken = default)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(timeout, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(pollInterval, TimeSpan.Zero);
        var deadline = this.timeProvider.GetUtcNow() + timeout;
        OpenEmsJsonRpcException? lastFailure = null;
        do
        {
            var remaining = deadline - this.timeProvider.GetUtcNow();
            using var attempt = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            attempt.CancelAfter(remaining);
            try
            {
                return await client.GetEdgeConfigAsync(attempt.Token).ConfigureAwait(false);
            }
            catch (OpenEmsJsonRpcException exception)
                when (exception.StatusCode is not HttpStatusCode.Unauthorized and not HttpStatusCode.Forbidden)
            {
                lastFailure = exception;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            remaining = deadline - this.timeProvider.GetUtcNow();
            if (remaining <= TimeSpan.Zero)
            {
                break;
            }
            await Task.Delay(pollInterval < remaining ? pollInterval : remaining, this.timeProvider, cancellationToken)
                .ConfigureAwait(false);
        } while (this.timeProvider.GetUtcNow() < deadline);

        var detail = lastFailure is null ? string.Empty : $" Last response: {lastFailure.Message}";
        throw new TimeoutException("Authenticated OpenEMS getEdgeConfig readiness timed out." + detail, lastFailure);
    }
}
