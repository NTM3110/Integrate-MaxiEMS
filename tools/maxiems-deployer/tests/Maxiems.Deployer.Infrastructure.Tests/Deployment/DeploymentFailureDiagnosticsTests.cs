using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Security;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
public sealed class DeploymentFailureDiagnosticsTests
{
    private static readonly string[] DiagnosticsArguments = ["diagnostics"];

    [TestMethod]
    public async Task DiagnosticsAreBoundedRedactedAndEmittedBeforeRollback()
    {
        const string secret = "operator-secret";
        var output = $"service failed password={secret}\n" + new string('x', 40000);
        var events = new List<DeploymentExecutionEvent>();
        var run = Run(events, secret);
        var operations = new DeploymentRemoteOperations(
            new FixtureTransport(new(0, output, string.Empty, TimeSpan.Zero)),
            TimeSpan.FromSeconds(5));

        await DeploymentFailureDiagnostics.CollectAsync(operations, run);
        run.Rollback(true, "rollback complete");

        Assert.AreEqual(2, events.Count);
        Assert.AreEqual(DeploymentPhase.FailureDiagnostics, events[0].Phase);
        Assert.AreEqual(DeploymentPhase.Rollback, events[1].Phase);
        var diagnostic = events[0].OperatorMessage ?? throw new AssertFailedException("Missing diagnostics output.");
        Assert.IsFalse(diagnostic.Contains(secret, StringComparison.Ordinal));
        StringAssert.Contains(diagnostic, SecretRedactor.Redacted);
        Assert.IsLessThanOrEqualTo(
            DeploymentFailureDiagnostics.MaximumOutputCharacters + "Remote failure diagnostics:\n".Length,
            diagnostic.Length);
    }

    [TestMethod]
    public async Task DiagnosticsFailureDoesNotPreventRollbackProgress()
    {
        var events = new List<DeploymentExecutionEvent>();
        var run = Run(events, "secret");
        var operations = new DeploymentRemoteOperations(
            new FixtureTransport(new(64, string.Empty, "diagnostics unavailable", TimeSpan.Zero)),
            TimeSpan.FromSeconds(5));

        await DeploymentFailureDiagnostics.CollectAsync(operations, run);
        run.Rollback(true, "rollback complete");

        CollectionAssert.AreEqual(
            new[] { DeploymentPhase.FailureDiagnostics, DeploymentPhase.Rollback },
            events.Select(item => item.Phase).ToArray());
        StringAssert.Contains(events[0].OperatorMessage, "diagnostics unavailable");
        Assert.AreEqual(DeploymentPhaseOutcome.RolledBack, events[1].Outcome);
    }

    private static DeploymentRunBuilder Run(List<DeploymentExecutionEvent> events, string secret) => new(
        new ReleaseId("20261005061447-123456abcdef"),
        new SecretRedactor([new("password", secret)]),
        events.Add);

    private sealed class FixtureTransport(RemoteCommandResult result) : IRemoteCommandTransport
    {
        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            Assert.AreEqual(RemoteProgram.DeployHelper, request.Program);
            CollectionAssert.AreEqual(
                DiagnosticsArguments,
                request.Arguments.Select(argument => argument.Value).ToArray());
            Assert.AreEqual(DeploymentFailureDiagnostics.CollectionTimeout, request.Timeout);
            return Task.FromResult(result);
        }
    }
}
