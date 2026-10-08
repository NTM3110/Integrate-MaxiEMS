using System.Globalization;
using System.Text.Json.Serialization;

namespace Maxiems.Deployer.Core.Manufacturing;

public sealed record MacAddress
{
    private const ulong AddressMask = 0x0000FFFFFFFFFFFFUL;
    private const ulong BroadcastAddress = AddressMask;
    private const ulong MulticastBit = 0x0000010000000000UL;
    private const ulong LocallyAdministeredBit = 0x0000020000000000UL;

    [JsonConstructor]
    public MacAddress(string normalized)
    {
        this.Normalized = Normalize(normalized);
        var numeric = ulong.Parse(this.Normalized, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
        if (numeric == 0 || numeric == BroadcastAddress || (numeric & MulticastBit) != 0)
        {
            throw new ArgumentException("MAC address must be a non-zero unicast address.", nameof(normalized));
        }
        this.IsLocallyAdministered = (numeric & LocallyAdministeredBit) != 0;
    }

    public string Normalized
    {
        get;
    }
    public bool IsLocallyAdministered
    {
        get;
    }

    public string ToColonSeparated() => string.Join(':', Enumerable.Range(0, 6)
        .Select(index => this.Normalized.Substring(index * 2, 2)));

    public override string ToString() => this.Normalized;

    private static string Normalize(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("MAC address is required.", nameof(value));
        }
        var candidate = value.Trim();
        if (candidate.Length == 17)
        {
            var separator = candidate[2];
            if (separator is not (':' or '-') || candidate
                .Where((_, index) => index is 2 or 5 or 8 or 11 or 14)
                .Any(character => character != separator))
            {
                throw new ArgumentException("MAC address separators are malformed.", nameof(value));
            }
            candidate = candidate.Replace(separator.ToString(), string.Empty, StringComparison.Ordinal);
        }
        if (candidate.Length != 12 || candidate.Any(character => !Uri.IsHexDigit(character)))
        {
            throw new ArgumentException("MAC address must contain exactly twelve hexadecimal digits.", nameof(value));
        }
        return candidate.ToUpperInvariant();
    }
}

public sealed record DeviceSerial
{
    public const string Prefix = "MR";

    [JsonConstructor]
    public DeviceSerial(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("Device serial is required.", nameof(value));
        }
        var candidate = value.Trim().ToUpperInvariant();
        if (!candidate.StartsWith(Prefix, StringComparison.Ordinal) || candidate.Length != Prefix.Length + 12)
        {
            throw new ArgumentException("Device serial must be MR followed by twelve hexadecimal digits.", nameof(value));
        }
        this.SerialSourceMac = new(candidate[Prefix.Length..]);
        this.Value = Prefix + this.SerialSourceMac.Normalized;
    }

    public string Value
    {
        get;
    }
    public MacAddress SerialSourceMac
    {
        get;
    }

    public static DeviceSerial FromSerialSource(MacAddress address)
    {
        ArgumentNullException.ThrowIfNull(address);
        return new(Prefix + address.Normalized);
    }

    public override string ToString() => this.Value;
}
