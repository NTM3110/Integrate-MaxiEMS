using System.Collections.Immutable;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class SshOpenEmsBootstrapStoreTests
{
    private static readonly string[] BootstrapStatusArguments = ["bootstrap-status"];

    [TestMethod]
    public async Task InstallUploadsVerifiedArchiveAndUsesFixedHelperArguments()
    {
        var commands = new RecordingCommandTransport();
        var files = new RecordingFileTransport();
        var store = new SshOpenEmsBootstrapStore(commands, files, TimeSpan.FromSeconds(5));
        var configuration = OpenEmsBootstrapConfiguration.Create(
            "fixture-admin-password",
            new ConstantEntropy());

        await store.InstallFreshAsync(configuration.Files, CancellationToken.None);

        Assert.AreEqual(2, commands.Requests.Count);
        Assert.AreEqual(RemoteProgram.DeployHelper, commands.Requests[0].Program);
        Assert.AreEqual("bootstrap-config", commands.Requests[0].Arguments[0].Value);
        Assert.AreEqual(".upload-0123456789abcdef0123456789abcdef",
            commands.Requests[0].Arguments[1].Value);
        Assert.AreEqual(files.Sha256, commands.Requests[0].Arguments[2].Value);
        CollectionAssert.AreEqual(BootstrapStatusArguments,
            commands.Requests[1].Arguments.Select(argument => argument.Value).ToArray());
        CollectionAssert.AreEquivalent(configuration.Files.Select(file => file.Name).ToArray(), files.EntryNames);
        Assert.IsFalse(files.ArchiveText.Contains("fixture-admin-password", StringComparison.Ordinal));
    }

    private sealed class RecordingCommandTransport : IRemoteCommandTransport
    {
        public List<RemoteCommandRequest> Requests { get; } = [];

        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            this.Requests.Add(request);
            var operation = request.Arguments[0].Value;
            return Task.FromResult(new RemoteCommandResult(
                0,
                operation == "bootstrap-status" ? "present\n" : string.Empty,
                string.Empty,
                TimeSpan.Zero));
        }
    }

    private sealed class RecordingFileTransport : IRemoteFileTransport
    {
        public string[] EntryNames { get; private set; } = [];
        public string ArchiveText { get; private set; } = string.Empty;
        public string Sha256 { get; private set; } = string.Empty;

        public async Task<SftpUploadResult> UploadToRandomStagingAsync(
            Stream source,
            CancellationToken cancellationToken = default)
        {
            using var copy = new MemoryStream();
            await source.CopyToAsync(copy, cancellationToken);
            var bytes = copy.ToArray();
            this.Sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            copy.Position = 0;
            using var archive = new ZipArchive(copy, ZipArchiveMode.Read, leaveOpen: true);
            this.EntryNames = archive.Entries.Select(entry => entry.FullName).ToArray();
            var content = new StringBuilder();
            foreach (var entry in archive.Entries)
            {
                using var reader = new StreamReader(entry.Open(), Encoding.UTF8);
                content.Append(await reader.ReadToEndAsync(cancellationToken));
            }
            this.ArchiveText = content.ToString();
            return new(
                "/var/lib/maxiems-deploy/incoming/.upload-0123456789abcdef0123456789abcdef",
                bytes.Length,
                this.Sha256);
        }
    }

    private sealed class ConstantEntropy : IBootstrapEntropy
    {
        private byte next;

        public byte[] GetBytes(int count)
        {
            var result = Enumerable.Range(0, count).Select(_ => this.next++).ToArray();
            return result;
        }
    }
}
