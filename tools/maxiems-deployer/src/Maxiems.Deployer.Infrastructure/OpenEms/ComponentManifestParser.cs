using System.Collections.Immutable;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class ComponentManifestException(string message) : Exception(message);

public static class ComponentManifestParser
{
    private static readonly HashSet<string> RootFields = new(StringComparer.Ordinal)
    {
        "schemaVersion", "components",
    };

    private static readonly HashSet<string> ComponentFields = new(StringComparer.Ordinal)
    {
        "factoryPid", "id", "properties", "updateOnly",
    };

    private static readonly HashSet<string> FrameworkMetadataProperties = new(StringComparer.Ordinal)
    {
        "lastChangeAt", "lastChangeBy", "_lastChangeAt", "_lastChangeBy",
        "service.pid", "service.factoryPid", "service.bundleLocation",
    };

    public static ComponentManifest Parse(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
            });
            return ParseRoot(document.RootElement);
        }
        catch (JsonException exception)
        {
            throw new ComponentManifestException($"Manifest is not valid JSON: {exception.Message}");
        }
    }

    private static ComponentManifest ParseRoot(JsonElement root)
    {
        RequireObject(root, "manifest");
        RejectUnknownOrDuplicateFields(root, RootFields, "manifest");
        var version = Required(root, "schemaVersion");
        if (version.ValueKind != JsonValueKind.Number || !version.TryGetInt32(out var number) || number != 1)
        {
            throw new ComponentManifestException("schemaVersion must be the integer 1.");
        }

        var array = Required(root, "components");
        if (array.ValueKind != JsonValueKind.Array || array.GetArrayLength() == 0)
        {
            throw new ComponentManifestException("components must be a non-empty array.");
        }

        var components = ImmutableArray.CreateBuilder<ComponentDefinition>();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var factorySelectors = new HashSet<string>(StringComparer.Ordinal);
        var factoriesWithExactIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var element in array.EnumerateArray())
        {
            var component = ParseComponent(element);
            if (component.Id is not null && !ids.Add(component.Id))
            {
                throw new ComponentManifestException($"Duplicate component id '{component.Id}'.");
            }
            if (component.Id is null)
            {
                if (!factorySelectors.Add(component.FactoryPid)
                    || factoriesWithExactIds.Contains(component.FactoryPid))
                {
                    throw new ComponentManifestException(
                        $"Component factory selector '{component.FactoryPid}' overlaps another managed component.");
                }
            }
            else
            {
                if (factorySelectors.Contains(component.FactoryPid))
                {
                    throw new ComponentManifestException(
                        $"Component factory selector '{component.FactoryPid}' overlaps another managed component.");
                }
                factoriesWithExactIds.Add(component.FactoryPid);
            }
            components.Add(component);
        }
        return new(number, components.ToImmutable());
    }

    private static ComponentDefinition ParseComponent(JsonElement element)
    {
        RequireObject(element, "component");
        RejectUnknownOrDuplicateFields(element, ComponentFields, "component");
        var factoryPid = RequiredNonEmptyString(element, "factoryPid");
        var id = element.TryGetProperty("id", out var idValue)
            ? ReadNonEmptyString(idValue, "id")
            : null;
        var propertyObject = Required(element, "properties");
        RequireObject(propertyObject, "properties");
        var properties = ImmutableArray.CreateBuilder<ComponentProperty>();
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var property in propertyObject.EnumerateObject())
        {
            if (!names.Add(property.Name))
            {
                throw new ComponentManifestException($"Duplicate property '{property.Name}'.");
            }
            if (property.Name.Equals("id", StringComparison.Ordinal))
            {
                throw new ComponentManifestException("The component id belongs in the id field.");
            }
            if (FrameworkMetadataProperties.Contains(property.Name))
            {
                throw new ComponentManifestException(
                    $"Property '{property.Name}' is framework metadata and cannot be managed.");
            }
            properties.Add(new(property.Name, ParseValue(property.Value, property.Name, false)));
        }
        var updateOnly = element.TryGetProperty("updateOnly", out var updateOnlyValue)
            ? ReadBoolean(updateOnlyValue, "updateOnly")
            : false;
        if (updateOnly && id is null)
        {
            throw new ComponentManifestException("An updateOnly component requires an explicit id.");
        }
        return new(factoryPid, id, properties.ToImmutable(), updateOnly);
    }

    private static ComponentValue ParseValue(JsonElement element, string name, bool arrayItem) => element.ValueKind switch
    {
        JsonValueKind.String => new ComponentStringValue(element.GetString()!),
        JsonValueKind.True => new ComponentBooleanValue(true),
        JsonValueKind.False => new ComponentBooleanValue(false),
        JsonValueKind.Number when element.TryGetDecimal(out var number) => new ComponentNumberValue(number),
        JsonValueKind.Array when !arrayItem => new ComponentArrayValue(element.EnumerateArray()
            .Select(item => ParseValue(item, name, true)).ToImmutableArray()),
        _ => throw new ComponentManifestException($"Property '{name}' has an unsupported or untyped value."),
    };

    private static JsonElement Required(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value)
            ? value
            : throw new ComponentManifestException($"Required field '{name}' is missing.");

    private static string RequiredNonEmptyString(JsonElement element, string name) =>
        ReadNonEmptyString(Required(element, name), name);

    private static string ReadNonEmptyString(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(element.GetString()))
        {
            throw new ComponentManifestException($"Field '{name}' must be a non-empty string.");
        }
        return element.GetString()!;
    }

    private static bool ReadBoolean(JsonElement element, string name) => element.ValueKind switch
    {
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => throw new ComponentManifestException($"Field '{name}' must be a boolean."),
    };

    private static void RequireObject(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            throw new ComponentManifestException($"{name} must be an object.");
        }
    }

    private static void RejectUnknownOrDuplicateFields(
        JsonElement element,
        HashSet<string> allowed,
        string scope)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var property in element.EnumerateObject())
        {
            if (!seen.Add(property.Name))
            {
                throw new ComponentManifestException($"Duplicate field '{property.Name}' in {scope}.");
            }
            if (!allowed.Contains(property.Name))
            {
                throw new ComponentManifestException($"Unknown field '{property.Name}' in {scope}.");
            }
        }
    }
}
