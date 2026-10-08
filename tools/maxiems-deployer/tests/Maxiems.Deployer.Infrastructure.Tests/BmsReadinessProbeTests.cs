using System.Text.Json;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests;

[TestClass]
public sealed class BmsReadinessProbeTests
{
    private static readonly string[] AdminRoles = ["PLATFORM_ADMIN"];
    private static readonly string[] ViewerRoles = ["VIEWER"];
    private static readonly string[] FreshCalls = ["platformReady", "platformAuthenticate", "platformAuthenticateWithToken", "platformLogout"];
    private static readonly string[] UpdateCalls = ["platformReady"];
    [TestMethod]
    public void UsesCanonicalUuidAcceptedByOpenEms()
    {
        Assert.IsTrue(Guid.TryParseExact(BmsReadinessProbe.NewRequestId(), "D", out _));
    }

    [TestMethod]
    public async Task FreshInstallAuthenticatesChecksRoleAndLogsOut()
    {
        var calls = new List<string>();
        await BmsReadinessProbe.VerifyAsync((method, parameters) =>
        {
            calls.Add(method);
            return Task.FromResult(JsonSerializer.SerializeToElement<object>(method switch
            {
                "platformReady" => new { ready = true },
                "platformAuthenticate" => new { token = "test-session" },
                "platformAuthenticateWithToken" => new { roles = AdminRoles },
                _ => new { },
            }));
        }, true);
        CollectionAssert.AreEqual(FreshCalls, calls);
    }

    [TestMethod]
    public async Task UpdateDoesNotAttemptDefaultPassword()
    {
        var calls = new List<string>();
        await BmsReadinessProbe.VerifyAsync((method, _) =>
        {
            calls.Add(method);
            return Task.FromResult(JsonSerializer.SerializeToElement(new
            {
                ready = true
            }));
        }, false);
        CollectionAssert.AreEqual(UpdateCalls, calls);
    }

    [TestMethod]
    public async Task MissingAdminRoleRejectsAndLogsOut()
    {
        var loggedOut = false;
        await Assert.ThrowsExactlyAsync<InvalidOperationException>(() => BmsReadinessProbe.VerifyAsync((method, _) =>
        {
            if (method == "platformLogout")
                loggedOut = true;
            return Task.FromResult(JsonSerializer.SerializeToElement<object>(method switch
            {
                "platformReady" => new { ready = true },
                "platformAuthenticate" => new { token = "test-session" },
                "platformAuthenticateWithToken" => new { roles = ViewerRoles },
                _ => new { },
            }));
        }, true));
        Assert.IsTrue(loggedOut);
    }
}
