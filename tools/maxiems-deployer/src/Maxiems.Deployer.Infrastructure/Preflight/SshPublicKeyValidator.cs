using System.Buffers.Binary;
using System.Text;

namespace Maxiems.Deployer.Infrastructure.Preflight;

public static class SshPublicKeyValidator
{
    private static readonly HashSet<string> AllowedAlgorithms = new(StringComparer.Ordinal)
    {
        "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa",
    };

    public static bool IsValid(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Contains('\r') || value.Contains('\n'))
        {
            return false;
        }
        var fields = value.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (fields.Length < 2 || !AllowedAlgorithms.Contains(fields[0]))
        {
            return false;
        }
        byte[] blob;
        try
        {
            blob = Convert.FromBase64String(fields[1]);
        }
        catch (FormatException)
        {
            return false;
        }
        if (blob.Length < 8)
        {
            return false;
        }
        var algorithmLength = BinaryPrimitives.ReadInt32BigEndian(blob);
        if (algorithmLength <= 0 || algorithmLength > blob.Length - 4)
        {
            return false;
        }
        return Encoding.ASCII.GetString(blob, 4, algorithmLength).Equals(fields[0], StringComparison.Ordinal);
    }
}
