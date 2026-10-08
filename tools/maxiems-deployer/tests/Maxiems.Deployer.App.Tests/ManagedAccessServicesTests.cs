using System.IO;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Maxiems.Deployer.Infrastructure.Preflight;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
public sealed class ManagedAccessServicesTests
{
    [TestMethod]
    public void ManagedIdentityIsEncryptedValidAndStable()
    {
        var directory = NewTemporaryDirectory();
        try
        {
            var provider = new ManagedSshIdentityProvider(directory);
            using var first = provider.GetOrCreate();
            var publicKey = File.ReadAllText(first.PrivateKeyPath + ".pub").Trim();
            Assert.IsTrue(SshPublicKeyValidator.IsValid(publicKey));
            var passphrase = Reveal(first.PrivateKeyPassphrase);
            try
            {
                using var rsa = RSA.Create();
                rsa.ImportFromEncryptedPem(File.ReadAllText(first.PrivateKeyPath), passphrase);
                Assert.IsTrue(rsa.KeySize >= 3072);
                var protectedBytes = File.ReadAllBytes(first.PrivateKeyPath + ".passphrase.dpapi");
                var clearBytes = Encoding.UTF8.GetBytes(passphrase);
                try
                {
                    Assert.AreEqual(-1, protectedBytes.AsSpan().IndexOf(clearBytes));
                }
                finally
                {
                    CryptographicOperations.ZeroMemory(clearBytes);
                }
            }
            finally
            {
                CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(passphrase.AsSpan()));
            }
            using var second = provider.GetOrCreate();
            Assert.AreEqual(first.PrivateKeyPath, second.PrivateKeyPath);
            Assert.AreEqual(publicKey, File.ReadAllText(second.PrivateKeyPath + ".pub").Trim());
        }
        finally
        {
            Directory.Delete(directory, true);
        }
    }

    [TestMethod]
    public void EmbeddedProfileMaterializesOnlyWhenSelectedAndParses()
    {
        var directory = NewTemporaryDirectory();
        try
        {
            var provider = new EmbeddedComponentProfileProvider(directory);
            Assert.IsNull(provider.ResolveManifest(false));
            var path = provider.ResolveManifest(true);
            Assert.IsNotNull(path);
            var manifest = ComponentManifestParser.Parse(File.ReadAllText(path));
            Assert.AreEqual(1, manifest.SchemaVersion);
            Assert.AreEqual("ctrlApiModbusTcp0", manifest.Components.Single().Id);
            Assert.AreEqual(path, provider.ResolveManifest(true));
        }
        finally
        {
            Directory.Delete(directory, true);
        }
    }

    private static string NewTemporaryDirectory()
    {
        var directory = Path.Combine(Path.GetTempPath(), "maxiems-managed-access-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    private static char[] Reveal(SecureString value)
    {
        var pointer = Marshal.SecureStringToGlobalAllocUnicode(value);
        try
        {
            var result = new char[value.Length];
            Marshal.Copy(pointer, result, 0, result.Length);
            return result;
        }
        finally
        {
            Marshal.ZeroFreeGlobalAllocUnicode(pointer);
        }
    }
}
