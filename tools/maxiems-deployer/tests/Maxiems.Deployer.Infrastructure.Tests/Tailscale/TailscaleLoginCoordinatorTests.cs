using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Tailscale;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Tailscale;

[TestClass]
[TestCategory("Wizard")]
public sealed class TailscaleLoginCoordinatorTests
{
    private static readonly string[] UpArguments = ["tailscale", "up"];
    private static readonly string[] StatusArguments = ["tailscale", "status"];

    [TestMethod]
    public async Task RecognizesNormalNodeLoginUrlAndAuthenticatedStatus()
    {
        var client = new ScriptedNodeClient(
            "To authenticate, visit: https://login.tailscale.com/a/fixture-token\n",
            ["{\"BackendState\":\"NeedsLogin\",\"Self\":{\"Online\":false}}",
             "{\"BackendState\":\"Running\",\"Self\":{\"Online\":true}}"]);
        Uri? presented = null;

        var result = await new TailscaleLoginCoordinator(client).LoginAsync(
            TimeSpan.FromSeconds(1), TimeSpan.FromMilliseconds(1), uri => presented = uri);

        Assert.IsTrue(result.IsAuthenticated);
        Assert.AreEqual("https://login.tailscale.com/a/fixture-token", result.LoginUri!.AbsoluteUri);
        Assert.AreEqual(result.LoginUri, presented);
        Assert.IsTrue(client.StatusCalls >= 2);
    }

    [TestMethod]
    public async Task InvalidStatusTimesOutAndCancellationStopsPolling()
    {
        var timeoutClient = new ScriptedNodeClient(
            "https://login.tailscale.com/a/never-online", ["not-json"]);
        var timedOut = await new TailscaleLoginCoordinator(timeoutClient).LoginAsync(
            TimeSpan.FromMilliseconds(15), TimeSpan.FromMilliseconds(2));
        Assert.IsFalse(timedOut.IsAuthenticated);

        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(15));
        await Assert.ThrowsExactlyAsync<TaskCanceledException>(async () =>
            await new TailscaleLoginCoordinator(timeoutClient).LoginAsync(
                TimeSpan.FromSeconds(2), TimeSpan.FromMilliseconds(5), cancellationToken: cancellation.Token));
    }

    [TestMethod]
    public async Task SshClientUsesOnlyTypedNormalNodeHelperActions()
    {
        var transport = new RecordingTransport();
        var client = new SshTailscaleNodeClient(transport, TimeSpan.FromSeconds(10));

        await client.StartLoginAsync(CancellationToken.None);
        await client.GetStatusJsonAsync(CancellationToken.None);

        Assert.AreEqual(2, transport.Requests.Count);
        CollectionAssert.AreEqual(UpArguments, Values(transport.Requests[0]));
        CollectionAssert.AreEqual(StatusArguments, Values(transport.Requests[1]));
        Assert.IsTrue(transport.Requests.All(request => request.Program == RemoteProgram.DeployHelper));
        Assert.IsFalse(transport.Requests.SelectMany(request => Values(request))
            .Any(value => value.Contains("route", StringComparison.OrdinalIgnoreCase)
                || value.Contains("exit-node", StringComparison.OrdinalIgnoreCase)));
    }

    private static string[] Values(RemoteCommandRequest request) =>
        request.Arguments.Select(argument => argument.Value).ToArray();

    private sealed class ScriptedNodeClient(string loginOutput, IReadOnlyList<string> statuses) : ITailscaleNodeClient
    {
        public int StatusCalls
        {
            get; private set;
        }

        public Task<string> StartLoginAsync(CancellationToken cancellationToken) => Task.FromResult(loginOutput);

        public Task<string> GetStatusJsonAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var index = Math.Min(this.StatusCalls++, statuses.Count - 1);
            return Task.FromResult(statuses[index]);
        }
    }

    private sealed class RecordingTransport : IRemoteCommandTransport
    {
        public List<RemoteCommandRequest> Requests { get; } = [];

        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            this.Requests.Add(request);
            return Task.FromResult(new RemoteCommandResult(0, "{}", string.Empty, TimeSpan.Zero));
        }
    }
}
