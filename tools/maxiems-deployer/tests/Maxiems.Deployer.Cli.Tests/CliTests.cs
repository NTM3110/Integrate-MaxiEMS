using Maxiems.Deployer.Cli;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Security;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Cli.Tests;

[TestClass]
public sealed class CliTests
{
    private static string[] Valid => ["deploy", "--host", "192.168.1.10", "--user", "operator", "--payload", "payload.zip", "--identity", "id_ed25519"];

    [TestMethod]
    public void ParseDefaultsAndOptionalReboot()
    {
        var options = CliOptions.Parse([.. Valid, "--require-reboot", "--port", "2222"]);
        Assert.AreEqual(2222, options.Endpoint.Port);
        Assert.IsTrue(options.RequireReboot);
        Assert.IsTrue(Path.IsPathFullyQualified(options.Payload));
        Assert.IsNull(options.Manifest);
        Assert.AreEqual(DeploymentTargetProfile.Real, options.TargetProfile);
        Assert.AreEqual(DeploymentKioskMode.Managed, options.KioskMode);
        Assert.IsFalse(options.LocalBootstrap);
        Assert.IsFalse(options.KeepFailedReleaseForInspection);
        Assert.AreEqual(22, CliOptions.Parse(Valid).Endpoint.Port);
    }

    [TestMethod]
    public void ParsesInspectionModeOnlyWhenRequested()
    {
        Assert.IsTrue(CliOptions.Parse([.. Valid, "--keep-failed-release"]).KeepFailedReleaseForInspection);
        Assert.ThrowsExactly<ArgumentException>(() =>
            CliOptions.Parse([.. Valid, "--keep-failed-release", "--keep-failed-release"]));
    }

    [TestMethod]
    public void ParsesRealAndDemoDeploymentProfiles()
    {
        Assert.AreEqual(DeploymentTargetProfile.Demo,
            CliOptions.Parse([.. Valid, "--deploy-option", "demo"]).TargetProfile);
        Assert.AreEqual(DeploymentTargetProfile.Real,
            CliOptions.Parse([.. Valid, "-deploy-option", "real"]).TargetProfile);
        Assert.ThrowsExactly<ArgumentException>(() =>
            CliOptions.Parse([.. Valid, "--deploy-option", "staging"]));
        Assert.ThrowsExactly<ArgumentException>(() =>
            CliOptions.Parse([.. Valid, "--deploy-option", "real", "-deploy-option", "demo"]));
    }

    [TestMethod]
    public void ParsesKioskPreserveWithoutChangingDeploymentProfile()
    {
        var options = CliOptions.Parse([.. Valid, "--deploy-option", "demo", "--kiosk", "preserve"]);
        Assert.AreEqual(DeploymentTargetProfile.Demo, options.TargetProfile);
        Assert.AreEqual(DeploymentKioskMode.Preserve, options.KioskMode);
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([.. Valid, "--kiosk", "other"]));
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([.. Valid, "--kiosk", "managed", "--kiosk", "preserve"]));
    }

    [TestMethod]
    public void LocalBootstrapRequiresLoopbackIpLiteral()
    {
        var options = CliOptions.Parse([
            "deploy", "--host", "127.0.0.1", "--user", "temporary",
            "--payload", "payload.zip", "--identity", "temporary-key", "--local-bootstrap"]);
        Assert.IsTrue(options.LocalBootstrap);
        Assert.IsTrue(CliOptions.Parse([
            "deploy", "--host", "::1", "--user", "temporary",
            "--payload", "payload.zip", "--identity", "temporary-key", "--local-bootstrap"]).LocalBootstrap);
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([.. Valid, "--local-bootstrap"]));
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([
            "deploy", "--host", "localhost", "--user", "temporary",
            "--payload", "payload.zip", "--identity", "temporary-key", "--local-bootstrap"]));
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([
            "deploy", "--host", "127.0.0.1", "--user", "temporary",
            "--payload", "payload.zip", "--identity", "temporary-key",
            "--local-bootstrap", "--local-bootstrap"]));
    }

    [TestMethod]
    [DataRow("--password", "secret")]
    [DataRow("--port", "0")]
    [DataRow("--port", "65536")]
    [DataRow("--host", "other-host")]
    [DataRow("--unknown", "value")]
    public void RejectsSecretsDuplicatesAndInvalidOptions(string option, string value) =>
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([.. Valid, option, value]));

    [TestMethod]
    public void MissingValueDoesNotConsumeNextOption() =>
        Assert.ThrowsExactly<ArgumentException>(() => CliOptions.Parse([.. Valid, "--manifest", "--require-reboot"]));

    [TestMethod]
    public void SecretReaderHandlesEditingWithoutEcho()
    {
        var keys = new Queue<ConsoleKeyInfo>([
            new('a', ConsoleKey.A, false, false, false),
            new('b', ConsoleKey.B, false, false, false),
            new('\b', ConsoleKey.Backspace, false, false, false),
            new('c', ConsoleKey.C, false, false, false),
            new('\r', ConsoleKey.Enter, false, false, false),
        ]);
        var secret = SecretInput.Read(keys.Dequeue);
        try
        {
            Assert.AreEqual("ac", new string(secret));
        }
        finally
        {
            Array.Clear(secret);
        }
    }

    [TestMethod]
    public void SecretReaderHonorsCancellation()
    {
        using var source = new CancellationTokenSource();
        source.Cancel();
        Assert.ThrowsExactly<OperationCanceledException>(() => SecretInput.Read(() => throw new InvalidOperationException(), source.Token));
    }

    [TestMethod]
    public void ManagementPasswordInputAcceptsShortNonEmptyValue()
    {
        OpenEmsManagementPassword.RequireNonEmpty("x", "management");
    }

    [TestMethod]
    public void ManagementPasswordInputRejectsEmptyValue() =>
        Assert.ThrowsExactly<ArgumentException>(() =>
            OpenEmsManagementPassword.RequireNonEmpty([], "management"));

    [TestMethod]
    public void ExitCodesSeparateRollbackFailureAndCancellation()
    {
        Assert.AreEqual(0, CliOptions.ExitCode(DeploymentFinalState.Succeeded));
        Assert.AreEqual(3, CliOptions.ExitCode(DeploymentFinalState.RolledBack));
        Assert.AreEqual(1, CliOptions.ExitCode(DeploymentFinalState.Failed));
        Assert.AreEqual(130, CliOptions.ExitCode(DeploymentFinalState.Cancelled));
    }

    [TestMethod]
    [DataRow("y")]
    [DataRow("yes")]
    [DataRow("Y")]
    [DataRow(" YES ")]
    public void FirstSeenHostTrustAcceptsExplicitYes(string answer) =>
        Assert.IsTrue(HostTrustConfirmation.IsConfirmed(answer));

    [TestMethod]
    [DataRow(null)]
    [DataRow("")]
    [DataRow(" ")]
    [DataRow("n")]
    [DataRow("no")]
    [DataRow("SHA256:observed")]
    [DataRow("anything-else")]
    public void FirstSeenHostTrustRejectsEveryOtherAnswer(string? answer) =>
        Assert.IsFalse(HostTrustConfirmation.IsConfirmed(answer));

    [TestMethod]
    [DataRow(MissingInput.Payload, "Payload ZIP", true)]
    [DataRow(MissingInput.PrivateKey, "SSH private key", false)]
    [DataRow(MissingInput.PublicKey, "SSH public key sidecar", false)]
    [DataRow(MissingInput.Manifest, "Optional component manifest", true)]
    public void MissingInputReportsSpecificFile(MissingInput missing, string expectedLabel, bool pathIsPublic)
    {
        using var fixture = new CliInputFixture();
        fixture.CreateFilesExcept(missing);

        var error = Assert.ThrowsExactly<ArgumentException>(() => CliInputFiles.Validate(fixture.Options));

        StringAssert.Contains(error.Message, expectedLabel);
        var expectedPath = fixture.PathFor(missing);
        if (pathIsPublic)
        {
            StringAssert.Contains(error.Message, expectedPath);
        }
    }

    [TestMethod]
    [DataRow(MissingInput.PrivateKey, "SSH private key")]
    [DataRow(MissingInput.PublicKey, "SSH public key sidecar")]
    public void MissingSshKeyOutputKeepsLabelAndRedactsPrivateKeyPath(MissingInput missing, string expectedLabel)
    {
        using var fixture = new CliInputFixture();
        fixture.CreateFilesExcept(missing);
        var error = Assert.ThrowsExactly<ArgumentException>(() => CliInputFiles.Validate(fixture.Options));
        var output = new SecretRedactor([new("privateKeyPath", fixture.Options.Identity)])
            .RedactFreeText(error.Message);

        StringAssert.Contains(output, expectedLabel);
        StringAssert.Contains(output, SecretRedactor.Redacted);
        Assert.IsFalse(output.Contains(fixture.Options.Identity, StringComparison.Ordinal));
    }

    [TestMethod]
    public void OmittedOptionalManifestDoesNotFailInputValidation()
    {
        using var fixture = new CliInputFixture(includeManifest: false);
        fixture.CreateFilesExcept(MissingInput.Manifest);

        CliInputFiles.Validate(fixture.Options);
    }

    public enum MissingInput
    {
        Payload,
        PrivateKey,
        PublicKey,
        Manifest,
    }

    private sealed class CliInputFixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), $"maxiems-cli-input-{Guid.NewGuid():N}");

        public CliInputFixture(bool includeManifest = true)
        {
            Directory.CreateDirectory(this.root);
            var arguments = new List<string>([
                "deploy", "--host", "192.168.1.10", "--user", "operator",
                "--payload", Path.Combine(this.root, "payload.zip"),
                "--identity", Path.Combine(this.root, "deploy-key"),
            ]);
            if (includeManifest)
            {
                arguments.AddRange(["--manifest", Path.Combine(this.root, "components.json")]);
            }
            this.Options = CliOptions.Parse([.. arguments]);
        }

        public CliOptions Options
        {
            get;
        }

        public void CreateFilesExcept(MissingInput missing)
        {
            foreach (var input in Enum.GetValues<MissingInput>())
            {
                if (input != missing)
                {
                    File.WriteAllText(this.PathFor(input), "fixture");
                }
            }
        }

        public string PathFor(MissingInput input) => input switch
        {
            MissingInput.Payload => this.Options.Payload,
            MissingInput.PrivateKey => this.Options.Identity,
            MissingInput.PublicKey => this.Options.Identity + ".pub",
            MissingInput.Manifest => this.Options.Manifest ?? Path.Combine(this.root, "components.json"),
            _ => throw new ArgumentOutOfRangeException(nameof(input)),
        };

        public void Dispose() => Directory.Delete(this.root, recursive: true);
    }
}
