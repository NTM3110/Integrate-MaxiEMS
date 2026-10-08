using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public sealed class SshReleaseTransactionClient(
    IRemoteCommandTransport commands,
    IRemoteFileTransport files,
    TimeSpan operationTimeout)
{
    public Task BeginAsync(ReleaseId releaseId, CancellationToken cancellationToken = default) =>
        ExecuteAsync("begin", releaseId, [], cancellationToken);

    public async Task StageAsync(
        ReleaseId releaseId,
        Stream payload,
        CancellationToken cancellationToken = default)
    {
        var upload = await files.UploadToRandomStagingAsync(payload, cancellationToken).ConfigureAwait(false);
        var token = Path.GetFileName(upload.RemoteStagingPath);
        if (!IsUploadToken(token))
        {
            throw new InvalidDataException("The release upload did not return a valid staging token.");
        }
        await ExecuteAsync("stage", releaseId, [token, upload.Sha256], cancellationToken).ConfigureAwait(false);
    }

    public Task ActivateAsync(ReleaseId releaseId, CancellationToken cancellationToken = default) =>
        ExecuteAsync("activate", releaseId, [], cancellationToken);

    public Task CommitAsync(ReleaseId releaseId, CancellationToken cancellationToken = default) =>
        ExecuteAsync("commit", releaseId, [], cancellationToken);

    public Task AbortAsync(ReleaseId releaseId, CancellationToken cancellationToken = default) =>
        ExecuteAsync("abort", releaseId, [], cancellationToken);

    public async Task<string> GetStatusAsync(
        ReleaseId releaseId,
        CancellationToken cancellationToken = default)
    {
        var result = await ExecuteAsync("transaction-status", releaseId, [], cancellationToken).ConfigureAwait(false);
        var status = result.StandardOutput.Trim();
        return status is "begun" or "staged" or "activated" or "committed" or "rolled-back"
            ? status
            : throw new InvalidDataException("The release transaction returned an invalid status.");
    }

    private async Task<RemoteCommandResult> ExecuteAsync(
        string operation,
        ReleaseId releaseId,
        IEnumerable<string> arguments,
        CancellationToken cancellationToken)
    {
        var values = new[] { operation, releaseId.Value }.Concat(arguments);
        var request = new RemoteCommandRequest(
            RemoteProgram.DeployHelper,
            values.Select(value => new RemoteArgument(value)).ToImmutableArray(),
            operationTimeout);
        var result = await commands.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            var detail = result.StandardError.Trim();
            if (detail.Length == 0)
            {
                detail = result.StandardOutput.Trim();
            }
            if (detail.Length > 4096)
            {
                detail = detail[^4096..];
            }
            var suffix = detail.Length == 0 ? string.Empty : $": {detail}";
            throw new IOException(
                $"Release helper operation '{operation}' failed with exit {result.ExitCode}{suffix}");
        }
        return result;
    }

    private static bool IsUploadToken(string token) => token.Length == 40
        && token.StartsWith(".upload-", StringComparison.Ordinal)
        && token[8..].All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');
}
