using System.Buffers.Binary;
using System.IO;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.App.ViewModels;

namespace Maxiems.Deployer.App.Services;

public sealed class ManagedSshIdentityProvider(string directory) : IWizardSshIdentityProvider
{
    private static readonly byte[] Entropy = "MAXiEMS.Deployer.ManagedSshIdentity.v1"u8.ToArray();
    private readonly Lock gate = new();
    private readonly string directory = Path.GetFullPath(directory);

    public WizardSshIdentity GetOrCreate()
    {
        lock (this.gate)
        {
            Directory.CreateDirectory(this.directory);
            var privateKeyPath = Path.Combine(this.directory, "worker-bootstrap-key.pem");
            var publicKeyPath = privateKeyPath + ".pub";
            var protectedPassphrasePath = privateKeyPath + ".passphrase.dpapi";
            if (!File.Exists(privateKeyPath) || !File.Exists(publicKeyPath)
                || !File.Exists(protectedPassphrasePath))
            {
                Generate(privateKeyPath, publicKeyPath, protectedPassphrasePath);
            }
            return new(privateKeyPath, ReadPassphrase(protectedPassphrasePath));
        }
    }

    private static void Generate(string privateKeyPath, string publicKeyPath, string protectedPassphrasePath)
    {
        var passphrase = CreatePassphrase();
        var passphraseBytes = Encoding.UTF8.GetBytes(passphrase);
        byte[]? protectedPassphrase = null;
        try
        {
            using var rsa = RSA.Create(3072);
            var privatePem = rsa.ExportEncryptedPkcs8PrivateKeyPem(passphrase,
                new(PbeEncryptionAlgorithm.Aes256Cbc, HashAlgorithmName.SHA256, 210_000));
            var publicKey = CreatePublicKey(rsa.ExportParameters(false));
            protectedPassphrase = ProtectedData.Protect(passphraseBytes, Entropy, DataProtectionScope.CurrentUser);
            WriteAtomic(privateKeyPath, Encoding.ASCII.GetBytes(privatePem));
            WriteAtomic(publicKeyPath, Encoding.ASCII.GetBytes(publicKey + Environment.NewLine));
            WriteAtomic(protectedPassphrasePath, protectedPassphrase);
            File.SetAttributes(protectedPassphrasePath, File.GetAttributes(protectedPassphrasePath) | FileAttributes.Hidden);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(passphrase.AsSpan()));
            CryptographicOperations.ZeroMemory(passphraseBytes);
            if (protectedPassphrase is not null)
                CryptographicOperations.ZeroMemory(protectedPassphrase);
        }
    }

    private static SecureString ReadPassphrase(string path)
    {
        var protectedBytes = File.ReadAllBytes(path);
        byte[]? clearBytes = null;
        char[]? clearChars = null;
        try
        {
            clearBytes = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            clearChars = Encoding.UTF8.GetChars(clearBytes);
            var result = new SecureString();
            foreach (var character in clearChars)
                result.AppendChar(character);
            result.MakeReadOnly();
            return result;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
            if (clearBytes is not null)
                CryptographicOperations.ZeroMemory(clearBytes);
            if (clearChars is not null)
                CryptographicOperations.ZeroMemory(MemoryMarshal.AsBytes(clearChars.AsSpan()));
        }
    }

    private static char[] CreatePassphrase()
    {
        const string alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        var random = RandomNumberGenerator.GetBytes(48);
        var result = new char[random.Length];
        for (var index = 0; index < random.Length; index++)
            result[index] = alphabet[random[index] % alphabet.Length];
        CryptographicOperations.ZeroMemory(random);
        return result;
    }

    private static string CreatePublicKey(RSAParameters parameters)
    {
        using var stream = new MemoryStream();
        WriteField(stream, "ssh-rsa"u8);
        WriteMpint(stream, parameters.Exponent ?? throw new CryptographicException("RSA exponent is missing."));
        WriteMpint(stream, parameters.Modulus ?? throw new CryptographicException("RSA modulus is missing."));
        return $"ssh-rsa {Convert.ToBase64String(stream.ToArray())} maxiems-deployer";
    }

    private static void WriteMpint(Stream stream, ReadOnlySpan<byte> value)
    {
        var prefix = value.Length > 0 && (value[0] & 0x80) != 0;
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, value.Length + (prefix ? 1 : 0));
        stream.Write(length);
        if (prefix)
            stream.WriteByte(0);
        stream.Write(value);
    }

    private static void WriteField(Stream stream, ReadOnlySpan<byte> value)
    {
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, value.Length);
        stream.Write(length);
        stream.Write(value);
    }

    private static void WriteAtomic(string path, ReadOnlySpan<byte> content)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, content.ToArray());
            File.Move(temporary, path, true);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }
}
