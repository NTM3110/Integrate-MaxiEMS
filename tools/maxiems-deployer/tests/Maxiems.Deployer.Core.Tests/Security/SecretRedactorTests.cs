using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Lifecycle;
using Maxiems.Deployer.Core.Reporting;
using Maxiems.Deployer.Core.Security;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Security;

[TestClass]
[TestCategory("StateMachine")]
public sealed class SecretRedactorTests
{
    [TestMethod]
    [DataRow("privateKeyPath", "C:\\keys\\id_ed25519")]
    [DataRow("passphrase", "correct horse battery staple")]
    [DataRow("openemsPassword", "admin-secret")]
    [DataRow("authorization", "Basic YWRtaW46c2VjcmV0")]
    [DataRow("tailscaleUrl", "https://login.tailscale.com/a/secret-token")]
    [DataRow("sudoInput", "sudo-password")]
    [DataRow("jsonRpc.secret", "rpc-secret")]
    public void SensitiveKeyValuesAreAlwaysRedacted(string key, string value)
    {
        Assert.AreEqual(SecretRedactor.Redacted, new SecretRedactor().RedactValue(key, value));
    }

    [TestMethod]
    [DataRow("Authorization: Basic YWRtaW46c2VjcmV0")]
    [DataRow("Login at https://login.tailscale.com/a/secret-token now")]
    [DataRow("token=tskey-auth-super-secret")]
    [DataRow("{\"password\":\"admin-secret\"}")]
    [DataRow("passphrase:open-sesame")]
    [DataRow("key C:\\Users\\operator\\.ssh\\id_ed25519")]
    [DataRow("key /home/operator/.ssh/id_rsa")]
    public void FreeTextPatternsDoNotLeakKnownSecretShapes(string value)
    {
        var result = new SecretRedactor().RedactFreeText(value);

        StringAssert.Contains(result, SecretRedactor.Redacted);
        Assert.AreNotEqual(value, result);
    }

    [TestMethod]
    public void RegisteredSecretValuesAreRedactedWhereverTheyAppear()
    {
        var redactor = new SecretRedactor([new("seed", "unique-seeded-private-value")]);
        Assert.AreEqual("before [REDACTED] after", redactor.RedactFreeText("before unique-seeded-private-value after"));
    }

    [TestMethod]
    public void SerializedReportContainsNoSeededSecretSubstring()
    {
        const string secret = "unique-report-secret";
        var now = DateTimeOffset.Parse("2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var record = new DeploymentRunRecord(
            Guid.Parse("89e8e238-e818-428e-b8d9-f427f834ad26"),
            DeploymentFinalState.Failed,
            now,
            now.AddSeconds(1),
            TimeSpan.FromSeconds(1),
            [
                new(DeploymentPhase.StartServices, DeploymentPhaseOutcome.Failed, now, now, TimeSpan.Zero, "failed", secret),
            ],
            [
                new(now, DeploymentLogLevel.Error, "failed", secret, [ new("password", secret, false) ]),
            ]);
        var json = new DeploymentReportSerializer(new([new("seed", secret)])).Serialize(record);

        Assert.IsFalse(json.Contains(secret, StringComparison.Ordinal));
        StringAssert.Contains(json, SecretRedactor.Redacted);
    }
}
