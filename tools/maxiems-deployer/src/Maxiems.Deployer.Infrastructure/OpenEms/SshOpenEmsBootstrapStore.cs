using System.Collections.Immutable;
using System.IO.Compression;
using Maxiems.Deployer.Core.Transport;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class SshOpenEmsBootstrapStore(
    IRemoteCommandTransport commands,
    IRemoteFileTransport fileTransport,
    TimeSpan operationTimeout) : IOpenEmsBootstrapStore
{
    private static readonly DateTimeOffset DeterministicTimestamp = new(1980, 1, 1, 0, 0, 0, TimeSpan.Zero);

    public async Task<bool> ConfigurationExistsAsync(CancellationToken cancellationToken)
    {
        var result = await ExecuteAsync("bootstrap-status", [], cancellationToken).ConfigureAwait(false);
        return result.StandardOutput.Trim() switch
        {
            "present" => true,
            "absent" => false,
            _ => throw new InvalidDataException("OpenEMS bootstrap status returned an invalid response."),
        };
    }

    public async Task InstallFreshAsync(
        IReadOnlyCollection<OpenEmsBootstrapFile> files,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(files);
        if (files.Count != 4)
        {
            throw new ArgumentException("Exactly four OpenEMS bootstrap files are required.",
                nameof(files));
        }

        await using var archive = CreateArchive(files);
        var upload = await fileTransport.UploadToRandomStagingAsync(archive, cancellationToken).ConfigureAwait(false);
        var token = Path.GetFileName(upload.RemoteStagingPath);
        if (!token.StartsWith(".upload-", StringComparison.Ordinal) || token.Length != 40
            || token[8..].Any(character => !Uri.IsHexDigit(character)))
        {
            throw new InvalidDataException("The SSH upload path did not return a valid staging token.");
        }
        await ExecuteAsync("bootstrap-config", [token, upload.Sha256], cancellationToken).ConfigureAwait(false);
        if (!await ConfigurationExistsAsync(cancellationToken).ConfigureAwait(false))
        {
            throw new InvalidDataException("OpenEMS bootstrap files were not visible after installation.");
        }
    }

    private async Task<RemoteCommandResult> ExecuteAsync(
        string operation,
        IEnumerable<string> arguments,
        CancellationToken cancellationToken)
    {
        var request = new RemoteCommandRequest(
            RemoteProgram.DeployHelper,
            new[] { operation }.Concat(arguments).Select(value => new RemoteArgument(value)).ToImmutableArray(),
            operationTimeout);
        var result = await commands.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            throw new IOException($"OpenEMS bootstrap helper operation '{operation}' failed with exit {result.ExitCode}.");
        }
        return result;
    }

    private static MemoryStream CreateArchive(IEnumerable<OpenEmsBootstrapFile> configurationFiles)
    {
        var stream = new MemoryStream();
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var file in configurationFiles.OrderBy(file => file.Name, StringComparer.Ordinal))
            {
                var entry = archive.CreateEntry(file.Name, CompressionLevel.Optimal);
                entry.LastWriteTime = DeterministicTimestamp;
                using var destination = entry.Open();
                destination.Write(file.Content);
            }
        }
        stream.Position = 0;
        return stream;
    }
}
