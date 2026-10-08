using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

[TestClass]
[TestCategory("SshTransport")]
public sealed class SshFingerprintTests
{
    [TestMethod]
    public void ExactAlgorithmAndSha256ValueAreRequired()
    {
        var expected = SshFingerprint.Create("ssh-ed25519", "YWJjZGVm");
        var same = SshFingerprint.Create("ssh-ed25519", "SHA256:YWJjZGVm");
        var oneCharacterChanged = SshFingerprint.Create("ssh-ed25519", "YWJjZGVu");
        var otherAlgorithm = SshFingerprint.Create("rsa-sha2-512", "YWJjZGVm");

        Assert.IsTrue(SshFingerprint.FixedEquals(expected, same));
        Assert.IsFalse(SshFingerprint.FixedEquals(expected, oneCharacterChanged));
        Assert.IsFalse(SshFingerprint.FixedEquals(expected, otherAlgorithm));
    }

    [TestMethod]
    public void MalformedAndUnsupportedFingerprintsAreRejected()
    {
        Assert.ThrowsExactly<FormatException>(() => SshFingerprint.Create("ssh-ed25519", "not valid!"));
        Assert.ThrowsExactly<InvalidOperationException>(() => SshFingerprint.Create("ssh-dss", "YWJj"));
    }
}
