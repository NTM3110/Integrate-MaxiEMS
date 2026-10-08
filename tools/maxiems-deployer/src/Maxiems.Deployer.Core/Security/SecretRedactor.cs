using System.Collections.Immutable;
using System.Text.RegularExpressions;

namespace Maxiems.Deployer.Core.Security;

/// <summary>
/// Removes registered secret values and known credential patterns before text
/// is written to deployment progress, logs, or reports.
/// </summary>
public sealed partial class SecretRedactor
{
    public const string Redacted = "[REDACTED]";

    private static readonly ImmutableHashSet<string> SensitiveKeys = new[] {
        "authorization", "basicauth", "openemspassword", "passphrase", "password",
        "privatekeypassphrase", "privatekeypath", "secret", "sudoinput", "tailscaletoken",
        "tailscaleurl", "token",
    }.ToImmutableHashSet(StringComparer.OrdinalIgnoreCase);

    private readonly ImmutableArray<string> secretValues;

    public SecretRedactor(IEnumerable<SecretMaterial>? secrets = null)
    {
        this.secretValues = (secrets ?? [])
            .Select(secret => secret.Value)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.Ordinal)
            .OrderByDescending(value => value.Length)
            .ToImmutableArray();
    }

    public string RedactValue(string key, string? value)
    {
        if (IsSensitiveKey(key))
        {
            return Redacted;
        }
        return this.RedactFreeText(value ?? string.Empty);
    }

    public string RedactFreeText(string? text)
    {
        var result = text ?? string.Empty;
        foreach (var secret in this.secretValues)
        {
            result = result.Replace(secret, Redacted, StringComparison.Ordinal);
        }
        result = BasicAuthorizationRegex().Replace(result, Redacted);
        result = TailscaleLoginRegex().Replace(result, Redacted);
        result = TailscaleKeyRegex().Replace(result, Redacted);
        result = PrivateKeyPathRegex().Replace(result, Redacted);
        result = JsonSecretRegex().Replace(result, match => $"{match.Groups[1].Value}{Redacted}{match.Groups[3].Value}");
        result = KeyValueSecretRegex().Replace(result, match => $"{match.Groups[1].Value}{match.Groups[2].Value}{Redacted}");
        return result;
    }

    private static string NormalizeKey(string key) => new(
        key.Where(character => char.IsLetterOrDigit(character)).ToArray());

    private static bool IsSensitiveKey(string key)
    {
        var normalized = NormalizeKey(key);
        return SensitiveKeys.Contains(normalized)
            || normalized.EndsWith("password", StringComparison.OrdinalIgnoreCase)
            || normalized.EndsWith("passphrase", StringComparison.OrdinalIgnoreCase)
            || normalized.EndsWith("secret", StringComparison.OrdinalIgnoreCase)
            || normalized.EndsWith("token", StringComparison.OrdinalIgnoreCase);
    }

    [GeneratedRegex(@"(?i)\bBasic\s+[A-Za-z0-9+/=._-]+", RegexOptions.CultureInvariant)]
    private static partial Regex BasicAuthorizationRegex();

    [GeneratedRegex(@"https://login\.tailscale\.com/\S+", RegexOptions.CultureInvariant)]
    private static partial Regex TailscaleLoginRegex();

    [GeneratedRegex(@"\btskey-[A-Za-z0-9_-]+", RegexOptions.CultureInvariant)]
    private static partial Regex TailscaleKeyRegex();

    [GeneratedRegex(@"(?i)(?:[A-Z]:\\[^\s]+\\(?:\.ssh\\)?id_(?:rsa|ed25519|ecdsa)|/(?:home|root)/[^\s]+/\.ssh/id_(?:rsa|ed25519|ecdsa))", RegexOptions.CultureInvariant)]
    private static partial Regex PrivateKeyPathRegex();

    [GeneratedRegex("(?i)(\\\"(?:password|passphrase|token|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", RegexOptions.CultureInvariant)]
    private static partial Regex JsonSecretRegex();

    [GeneratedRegex(@"(?i)\b(password|passphrase|token|secret|sudoInput)(\s*[=:]\s*)[^\s,;]+", RegexOptions.CultureInvariant)]
    private static partial Regex KeyValueSecretRegex();
}
