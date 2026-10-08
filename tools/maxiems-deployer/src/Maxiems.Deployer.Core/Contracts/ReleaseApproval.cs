namespace Maxiems.Deployer.Core.Contracts;

public static class ReleaseApproval
{
    private const int PayloadPrefixLength = 12;

    public static bool MatchesVerifiedPayload(ReleaseId approvedRelease, ReleaseId embeddedRelease,
        string payloadSha256)
    {
        if (approvedRelease == embeddedRelease)
            return true;
        if (!IsSha256(payloadSha256))
            return false;
        return TryGetPayloadPrefix(approvedRelease, out var approvedPrefix)
            && TryGetPayloadPrefix(embeddedRelease, out var embeddedPrefix)
            && approvedPrefix.Equals(embeddedPrefix, StringComparison.Ordinal)
            && payloadSha256.StartsWith(approvedPrefix, StringComparison.Ordinal);
    }

    private static bool TryGetPayloadPrefix(ReleaseId release, out string prefix)
    {
        prefix = string.Empty;
        var value = release.Value;
        if (value.Length != 14 + 1 + PayloadPrefixLength || value[14] != '-'
            || !value[..14].All(char.IsAsciiDigit))
            return false;
        prefix = value[15..];
        return prefix.All(character => char.IsAsciiHexDigit(character) && !char.IsAsciiLetterUpper(character));
    }

    private static bool IsSha256(string value) => value.Length == 64
        && value.All(character => char.IsAsciiHexDigit(character) && !char.IsAsciiLetterUpper(character));
}
