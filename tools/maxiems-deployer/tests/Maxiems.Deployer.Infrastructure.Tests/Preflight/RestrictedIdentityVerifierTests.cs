using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Preflight;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Preflight;

[TestClass]
[TestCategory("UbuntuPreflight")]
public sealed class RestrictedIdentityVerifierTests
{
    [TestMethod]
    public async Task ReconnectedDeployIdentityRequiresExactHelperAndBroadSudoDenial()
    {
        var transport = new FixtureTransport(new Dictionary<RemoteProgram, RemoteCommandResult>
        {
            [RemoteProgram.CurrentIdentityProbe] = Result(0, "uid=982(maxiems-deploy) gid=982(maxiems-deploy)"),
            [RemoteProgram.BroadSudoDenialProbe] = Result(1, string.Empty),
            [RemoteProgram.SudoAllowlistProbe] = Result(0,
                "(root) NOPASSWD: /usr/local/libexec/maxiems-deploy-helper status"),
        });

        var result = await new RestrictedIdentityVerifier(transport).VerifyAsync();

        Assert.IsTrue(result.IsValid);
    }

    [TestMethod]
    public async Task NormalUserUidCannotBecomeRestrictedDeployIdentity()
    {
        var transport = new FixtureTransport(new Dictionary<RemoteProgram, RemoteCommandResult>
        {
            [RemoteProgram.CurrentIdentityProbe] = Result(0, "uid=1000(maxiems-deploy) gid=1000(maxiems-deploy)"),
            [RemoteProgram.BroadSudoDenialProbe] = Result(1, string.Empty),
            [RemoteProgram.SudoAllowlistProbe] = Result(0,
                "(root) NOPASSWD: /usr/local/libexec/maxiems-deploy-helper status"),
        });

        var result = await new RestrictedIdentityVerifier(transport).VerifyAsync();

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    public async Task UnexpectedBroadSudoMakesVerificationFail()
    {
        var transport = new FixtureTransport(new Dictionary<RemoteProgram, RemoteCommandResult>
        {
            [RemoteProgram.CurrentIdentityProbe] = Result(0, "uid=991(maxiems-deploy)"),
            [RemoteProgram.BroadSudoDenialProbe] = Result(0, "uid=0(root)"),
            [RemoteProgram.SudoAllowlistProbe] = Result(0,
                "(root) NOPASSWD: /usr/local/libexec/maxiems-deploy-helper *, /bin/bash"),
        });

        var result = await new RestrictedIdentityVerifier(transport).VerifyAsync();

        Assert.IsFalse(result.IsValid);
    }

    private static RemoteCommandResult Result(int exitCode, string output) =>
        new(exitCode, output, string.Empty, TimeSpan.Zero);

    private sealed class FixtureTransport(IReadOnlyDictionary<RemoteProgram, RemoteCommandResult> results)
        : IRemoteCommandTransport
    {
        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default) => Task.FromResult(results[request.Program]);
    }
}
