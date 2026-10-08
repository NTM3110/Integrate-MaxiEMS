using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Inspection;

[TestClass]
public sealed class TargetIdentityGuardTests
{
    [TestMethod]
    public async Task VerifyAcceptsSamePermanentIdentityDespiteCurrentMacChanges()
    {
        var transport = new IdentityTransport("00:61:3a:7d:75:5a", "00:61:3a:7d:75:5b");
        var expected = new TargetIdentityExpectation(new('a', 64),
            [new("00:61:3a:7d:75:5b"), new("00:61:3a:7d:75:5a")]);

        await new TargetIdentityGuard(transport).VerifyAsync(expected);
    }

    [TestMethod]
    public async Task VerifyRejectsTargetSwapBeforeMutation()
    {
        var transport = new IdentityTransport("00:61:3a:7d:75:5a", "00:61:3a:7d:75:99");
        var expected = new TargetIdentityExpectation(new('a', 64),
            [new("00:61:3a:7d:75:5a"), new("00:61:3a:7d:75:5b")]);

        var exception = await Assert.ThrowsExactlyAsync<InvalidOperationException>(() =>
            new TargetIdentityGuard(transport).VerifyAsync(expected));

        StringAssert.Contains(exception.Message, "changed after inspection");
        Assert.IsTrue(transport.Programs.All(program => program is RemoteProgram.NetworkLinksProbe
            or RemoteProgram.PermanentMacProbe));
    }

    private sealed class IdentityTransport(string first, string second) : IRemoteCommandTransport
    {
        public List<RemoteProgram> Programs { get; } = [];
        public Task<RemoteCommandResult> ExecuteAsync(RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            this.Programs.Add(request.Program);
            var output = request.Program == RemoteProgram.NetworkLinksProbe
                ? "[{\"ifname\":\"eth0\",\"address\":\"02:00:00:00:00:01\",\"link_type\":\"ether\"},{\"ifname\":\"eth1\",\"address\":\"02:00:00:00:00:02\",\"link_type\":\"ether\"}]"
                : $"Permanent address: {(request.Arguments[0].Value == "eth0" ? first : second)}";
            return Task.FromResult(new RemoteCommandResult(0, output, string.Empty, TimeSpan.Zero));
        }
    }
}
