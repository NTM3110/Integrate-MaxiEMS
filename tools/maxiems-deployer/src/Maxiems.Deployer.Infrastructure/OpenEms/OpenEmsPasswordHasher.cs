using System.Security.Cryptography;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public static class OpenEmsPasswordHasher
{
    public const int Iterations = 10;
    public const int KeyLengthBytes = 32;
    public const int SaltLengthBytes = 16;

    public static byte[] Derive(ReadOnlySpan<char> password, ReadOnlySpan<byte> salt)
    {
        if (password.IsEmpty)
        {
            throw new ArgumentException("The OpenEMS password must not be empty.", nameof(password));
        }
        ValidateSalt(salt);
        return Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA512, KeyLengthBytes);
    }

    internal static byte[] Derive(ReadOnlySpan<byte> password, ReadOnlySpan<byte> salt)
    {
        ValidateSalt(salt);
        return Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA512, KeyLengthBytes);
    }

    private static void ValidateSalt(ReadOnlySpan<byte> salt)
    {
        if (salt.Length != SaltLengthBytes)
        {
            throw new ArgumentException($"OpenEMS salts must contain {SaltLengthBytes} bytes.", nameof(salt));
        }
    }
}
