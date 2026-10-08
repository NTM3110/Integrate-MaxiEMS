using System.Security.Cryptography;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
[TestCategory("AtomicRelease")]
public sealed class SshReleaseTransactionClientTests
{
    private static readonly ReleaseId Release = new("20260719010000-0123456789ab");

    [TestMethod]
    public async Task UsesTypedHelperProtocolAndVerifiedUploadToken()
    {
        var transport = new RecordingTransport();
        var client = new SshReleaseTransactionClient(transport, transport, TimeSpan.FromSeconds(30));
        using var payload = new MemoryStream([1, 2, 3, 4]);

        await client.BeginAsync(Release);
        await client.StageAsync(Release, payload);
        await client.ActivateAsync(Release);
        await client.CommitAsync(Release);

        Assert.AreEqual(4, transport.Requests.Count);
        CollectionAssert.AreEqual(new[] { "begin", Release.Value }, Values(transport.Requests[0]));
        CollectionAssert.AreEqual(new[] {
            "stage", Release.Value, ".upload-0123456789abcdef0123456789abcdef", transport.Hash,
        }, Values(transport.Requests[1]));
        CollectionAssert.AreEqual(new[] { "activate", Release.Value }, Values(transport.Requests[2]));
        CollectionAssert.AreEqual(new[] { "commit", Release.Value }, Values(transport.Requests[3]));
        Assert.IsTrue(transport.Requests.All(request => request.Program == RemoteProgram.DeployHelper));
    }

    [TestMethod]
    public async Task AbortAndStatusAreFixedOperations()
    {
        var transport = new RecordingTransport { Status = "rolled-back\n" };
        var client = new SshReleaseTransactionClient(transport, transport, TimeSpan.FromSeconds(30));

        await client.AbortAsync(Release);
        var status = await client.GetStatusAsync(Release);

        Assert.AreEqual("rolled-back", status);
        CollectionAssert.AreEqual(new[] { "abort", Release.Value }, Values(transport.Requests[0]));
        CollectionAssert.AreEqual(new[] { "transaction-status", Release.Value }, Values(transport.Requests[1]));
    }

    [TestMethod]
    public async Task FailureIncludesBoundedRemoteHelperDetail()
    {
        var transport = new RecordingTransport
        {
            ExitCode = 64,
            StandardError = new string('x', 5000) + "maxiems-helper: another deployment transaction is active",
        };
        var client = new SshReleaseTransactionClient(transport, transport, TimeSpan.FromSeconds(30));

        var error = await Assert.ThrowsExactlyAsync<IOException>(() => client.BeginAsync(Release));

        StringAssert.Contains(error.Message, "Release helper operation 'begin' failed with exit 64:");
        StringAssert.Contains(error.Message, "maxiems-helper: another deployment transaction is active");
        Assert.IsTrue(error.Message.Length < 4300, "Remote helper error detail was not bounded.");
    }

    [TestMethod]
    public async Task FailureUsesStandardOutputWhenStandardErrorIsEmpty()
    {
        var transport = new RecordingTransport
        {
            ExitCode = 64,
            StandardOutput = "maxiems-helper: transaction status is unavailable",
        };
        var client = new SshReleaseTransactionClient(transport, transport, TimeSpan.FromSeconds(30));

        var error = await Assert.ThrowsExactlyAsync<IOException>(() => client.BeginAsync(Release));

        StringAssert.Contains(error.Message, "maxiems-helper: transaction status is unavailable");
    }

    private static string[] Values(RemoteCommandRequest request) =>
        request.Arguments.Select(argument => argument.Value).ToArray();

    private sealed class RecordingTransport : IRemoteCommandTransport, IRemoteFileTransport
    {
        public List<RemoteCommandRequest> Requests { get; } = [];
        public string Status { get; init; } = string.Empty;
        public int ExitCode { get; init; }
        public string StandardOutput { get; init; } = string.Empty;
        public string StandardError { get; init; } = string.Empty;
        public string Hash { get; private set; } = string.Empty;

        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            this.Requests.Add(request);
            return Task.FromResult(new RemoteCommandResult(
                this.ExitCode,
                request.Arguments[0].Value == "transaction-status" ? this.Status : this.StandardOutput,
                this.StandardError,
                TimeSpan.Zero));
        }

        public async Task<SftpUploadResult> UploadToRandomStagingAsync(
            Stream source,
            CancellationToken cancellationToken = default)
        {
            this.Hash = Convert.ToHexString(await SHA256.HashDataAsync(source, cancellationToken)).ToLowerInvariant();
            return new(
                "/var/lib/maxiems-deploy/incoming/.upload-0123456789abcdef0123456789abcdef",
                source.Length,
                this.Hash);
        }
    }
}
