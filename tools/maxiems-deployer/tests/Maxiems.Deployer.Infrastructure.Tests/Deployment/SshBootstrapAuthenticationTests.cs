using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Renci.SshNet;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
public sealed class SshBootstrapAuthenticationTests
{
    [TestMethod]
    public void TemporaryPasswordSelectsPasswordAuthenticationWithoutReadingKey()
    {
        var options = Options("one-time-password".AsMemory(), "missing-key.pem");

        var connection = SshBootstrapSession.CreateConnectionInfo(options);

        Assert.IsInstanceOfType<PasswordConnectionInfo>(connection);
    }

    [TestMethod]
    public void SudoCommandNeverContainsThePassword()
    {
        const string password = "one-time-password";
        var command = SshBootstrapSession.BuildSudoCommand(
            "/tmp/bootstrap/maxiems-bootstrap-helper install-layout /tmp/bootstrap", usePassword: true);

        Assert.IsFalse(command.Contains(password, StringComparison.Ordinal));
        StringAssert.Contains(command, "sudo -S -p '' --");
    }

    [TestMethod]
    public void WorkerPreflightVerifiesSudoThroughStandardInputWhileKeyModeRemainsNonInteractive()
    {
        var request = new RemoteCommandRequest(RemoteProgram.SudoProbe, [], TimeSpan.FromSeconds(5));

        var workerCommand = SshBootstrapSession.EncodeCommand(request, usePasswordSudo: true);
        var technicianCommand = SshBootstrapSession.EncodeCommand(request, usePasswordSudo: false);

        Assert.AreEqual("/usr/bin/sudo -S -p '' -- /usr/bin/true", workerCommand);
        Assert.AreEqual("/usr/bin/sudo -n /usr/bin/true", technicianCommand);
    }

    [TestMethod]
    public void ManifestRequiresModbusOnlyForAModbusFactoryOrPort502()
    {
        var standard = new ComponentManifest(1, [new("Controller.Other", "other0", [])]);
        var modbus = new ComponentManifest(1, [new("Controller.Api.ModbusTcp.ReadOnly", "modbus0", [])]);

        Assert.IsFalse(DeploymentEngine.ManifestRequiresModbus(null));
        Assert.IsFalse(DeploymentEngine.ManifestRequiresModbus(standard));
        Assert.IsTrue(DeploymentEngine.ManifestRequiresModbus(modbus));
    }

    [TestMethod]
    public void LegacyServiceForcesARecoverableTransactionEvenForSameRelease()
    {
        Assert.IsTrue(DeploymentEngine.ShouldQuiesceLegacy(active: true, enabled: false));
        Assert.IsTrue(DeploymentEngine.ShouldQuiesceLegacy(active: false, enabled: true));
        Assert.IsTrue(DeploymentEngine.RequiresReleaseTransaction(redeploy: true, quiesceLegacy: true));
        Assert.IsFalse(DeploymentEngine.RequiresReleaseTransaction(redeploy: true, quiesceLegacy: false));
        Assert.IsTrue(DeploymentEngine.RequiresReleaseTransaction(redeploy: false, quiesceLegacy: false));
    }

    [TestMethod]
    public void RecoveryReconnectRemainsRequiredWhenTheRebootSessionIsDisconnected()
    {
        Assert.IsTrue(DeploymentEngine.RequiresRecoveryConnection(
            transactionActive: true,
            hasComponentCheckpoint: false));
        Assert.IsTrue(DeploymentEngine.RequiresRecoveryConnection(
            transactionActive: false,
            hasComponentCheckpoint: true));
        Assert.IsFalse(DeploymentEngine.RequiresRecoveryConnection(
            transactionActive: false,
            hasComponentCheckpoint: false));
    }

    [TestMethod]
    public void SftpModesUseTheDecimalOctalNotationRequiredBySshNet()
    {
        Assert.AreEqual(SshBootstrapSession.SshDirectoryMode,
            short.Parse("700", System.Globalization.CultureInfo.InvariantCulture));
        Assert.AreEqual(SshBootstrapSession.AuthorizedKeysMode,
            short.Parse("600", System.Globalization.CultureInfo.InvariantCulture));
    }

    private static SshTransportOptions Options(ReadOnlyMemory<char> bootstrapPassword, string privateKeyPath) => new(
        new HostEndpoint("edge.fixture.test", 22),
        "worker",
        bootstrapPassword,
        privateKeyPath,
        ReadOnlyMemory<char>.Empty,
        TimeSpan.FromSeconds(5),
        TimeSpan.FromSeconds(5));
}
