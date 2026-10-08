using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.Infrastructure.Preflight;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Preflight;

[TestClass]
[TestCategory("UbuntuPreflight")]
public sealed class BootstrapInputSecurityTests
{
    [TestMethod]
    public void PublicKeyRequiresOneAllowedAlgorithmAndMatchingBinaryBlob()
    {
        var algorithm = Encoding.ASCII.GetBytes("ssh-ed25519");
        var blob = new byte[4 + algorithm.Length + 32];
        BinaryPrimitives.WriteInt32BigEndian(blob, algorithm.Length);
        algorithm.CopyTo(blob.AsSpan(4));
        var valid = $"ssh-ed25519 {Convert.ToBase64String(blob)} fixture";

        Assert.IsTrue(SshPublicKeyValidator.IsValid(valid));
        Assert.IsFalse(SshPublicKeyValidator.IsValid(valid + "\nssh-ed25519 AAAA"));
        Assert.IsFalse(SshPublicKeyValidator.IsValid("ssh-ed25519 not-base64"));
        Assert.IsFalse(SshPublicKeyValidator.IsValid(valid.Replace("ssh-ed25519", "ssh-rsa", StringComparison.Ordinal)));
    }

    [TestMethod]
    public void TamperedBootstrapManifestFailsPinnedSignatureVerification()
    {
        using var key = RSA.Create(2048);
        var manifest = "helper sha256"u8.ToArray();
        var signature = key.SignData(manifest, HashAlgorithmName.SHA256, RSASignaturePadding.Pss);
        var publicKey = key.ExportSubjectPublicKeyInfoPem();

        Assert.IsTrue(BootstrapSignatureVerifier.Verify(manifest, signature, publicKey));
        manifest[0] ^= 1;
        Assert.IsFalse(BootstrapSignatureVerifier.Verify(manifest, signature, publicKey));
        Assert.IsFalse(BootstrapSignatureVerifier.Verify(manifest, signature, "not a pem"));
    }
}
