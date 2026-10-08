using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

internal static class ReleasePackageManifestParser
{
    public static ReleasePackageManifest Parse(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = 16,
            });
            var value = new StrictJsonObject(document.RootElement, "release metadata", "schemaVersion", "releaseId",
                "version", "payloadSha256", "supportedUbuntuVersions", "supportedArchitectures",
                "minimumDeployerVersion");
            if (value.RequiredInt32("schemaVersion") != 1)
            {
                throw new ReleasePackageException("Unsupported release package schema version.");
            }
            var releaseId = value.RequiredString("releaseId", 27);
            if (!IsReleaseId(releaseId))
                throw new ReleasePackageException("Release ID has an invalid format.");
            var payloadHash = value.RequiredString("payloadSha256", 64);
            if (!IsSha256(payloadHash))
                throw new ReleasePackageException("Payload SHA-256 has an invalid format.");
            var minimum = value.RequiredString("minimumDeployerVersion", 40);
            if (!Version.TryParse(minimum, out var minimumVersion) || minimumVersion.Revision >= 0)
            {
                throw new ReleasePackageException("Minimum Deployer version must use major.minor.patch format.");
            }
            return new(1, new(releaseId), value.RequiredString("version", 80), payloadHash,
                ReadStrings(value.RequiredArray("supportedUbuntuVersions"), "supported Ubuntu versions"),
                ReadArchitectures(value.RequiredArray("supportedArchitectures")), minimumVersion);
        }
        catch (JsonException exception)
        {
            throw new ReleasePackageException("Release metadata is not valid JSON.", exception);
        }
        catch (ManufacturingProfileException exception)
        {
            throw new ReleasePackageException(exception.Message, exception);
        }
    }

    private static ImmutableArray<string> ReadStrings(JsonElement array, string scope)
    {
        var values = array.EnumerateArray().Select(item => item.ValueKind == JsonValueKind.String
            && !string.IsNullOrWhiteSpace(item.GetString()) && item.GetString()!.Length <= 40
                ? item.GetString()!
                : throw new ReleasePackageException($"{scope} must contain non-empty strings.")).ToImmutableArray();
        if (values.Length == 0 || values.Distinct(StringComparer.Ordinal).Count() != values.Length)
        {
            throw new ReleasePackageException($"{scope} must be non-empty and unique.");
        }
        return values;
    }

    private static ImmutableArray<SupportedArchitecture> ReadArchitectures(JsonElement array) =>
        ReadStrings(array, "supported architectures").Select(value => value switch
        {
            "amd64" => SupportedArchitecture.Amd64,
            "arm64" => SupportedArchitecture.Arm64,
            _ => throw new ReleasePackageException($"Unsupported architecture '{value}'."),
        }).ToImmutableArray();

    private static bool IsReleaseId(string value) => value.Length == 27 && value[14] == '-'
        && value[..14].All(char.IsAsciiDigit)
        && value[15..].All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static bool IsSha256(string value) => value.Length == 64
        && value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');
}
