using System.Collections.Immutable;
using System.Text.Json;
using System.Text.Json.Nodes;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

internal static class OpenEmsComponentMapping
{
    public static void ValidateManifest(ComponentManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        if (manifest.SchemaVersion != 1)
        {
            throw new ArgumentException("Only manifest schemaVersion 1 is supported.", nameof(manifest));
        }
        if (manifest.Components.IsDefaultOrEmpty)
        {
            throw new ArgumentException("The manifest must contain at least one component.", nameof(manifest));
        }
    }

    public static OpenEmsComponentSnapshot? FindExisting(
        ComponentDefinition desired,
        IReadOnlyDictionary<string, OpenEmsComponentSnapshot> components)
    {
        if (desired.Id is not null)
        {
            if (!components.TryGetValue(desired.Id, out var found))
                return null;
            if (!found.FactoryPid.Equals(desired.FactoryPid, StringComparison.Ordinal))
            {
                throw new OpenEmsReconciliationException(desired.Id, ["factoryPid"]);
            }
            return found;
        }
        var matches = components.Values.Where(component =>
            component.FactoryPid.Equals(desired.FactoryPid, StringComparison.Ordinal)).ToArray();
        return matches.Length switch
        {
            0 => null,
            1 => matches[0],
            _ => throw new OpenEmsReconciliationException(desired.FactoryPid, ["id"]),
        };
    }

    public static string? FindCheckpointComponent(
        OpenEmsManagedCheckpointEntry entry,
        IReadOnlyDictionary<string, OpenEmsComponentSnapshot> components)
    {
        if (entry.ComponentId is not null)
        {
            return components.TryGetValue(entry.ComponentId, out var component)
                && component.FactoryPid.Equals(entry.FactoryPid, StringComparison.Ordinal)
                    ? entry.ComponentId
                    : null;
        }
        var matches = components.Values.Where(component =>
            component.FactoryPid.Equals(entry.FactoryPid, StringComparison.Ordinal)).ToArray();
        return matches.Length switch
        {
            0 => null,
            1 => matches[0].Id,
            _ => throw new OpenEmsReconciliationException(entry.FactoryPid, ["id"]),
        };
    }

    public static bool SnapshotsEqual(OpenEmsComponentSnapshot expected, OpenEmsComponentSnapshot actual)
    {
        if (!expected.FactoryPid.Equals(actual.FactoryPid, StringComparison.Ordinal))
            return false;
        var expectedProperties = ComparableProperties(expected.Properties);
        var actualProperties = ComparableProperties(actual.Properties);
        return expectedProperties.Count == actualProperties.Count
            && expectedProperties.All(property => actualProperties.TryGetValue(property.Key, out var value)
                && JsonElement.DeepEquals(property.Value, value));
    }

    public static List<string> HasDifferences(ComponentDefinition desired, OpenEmsComponentSnapshot actual)
    {
        var differences = new List<string>();
        foreach (var property in desired.Properties)
        {
            if (!actual.Properties.TryGetValue(property.Name, out var value) || !ValuesEqual(property.Value, value))
            {
                differences.Add(property.Name);
            }
        }
        return differences;
    }

    public static List<string> HasMigrationDifferences(
        ComponentDefinition desired,
        OpenEmsComponentSnapshot before,
        OpenEmsComponentSnapshot actual)
    {
        var differences = HasDifferences(desired, actual);
        var managedNames = desired.Properties.Select(property => property.Name).ToHashSet(StringComparer.Ordinal);
        foreach (var property in ComparableProperties(before.Properties))
        {
            if (managedNames.Contains(property.Key))
            {
                continue;
            }
            if (!actual.Properties.TryGetValue(property.Key, out var value)
                || !JsonElement.DeepEquals(property.Value, value))
            {
                differences.Add(property.Key);
            }
        }
        return differences;
    }

    public static ComponentUpsertResult Result(
        ComponentDefinition desired,
        string componentId,
        ComponentUpsertAction action) => new(
        desired.Id ?? componentId,
        action,
        desired.Properties.Select(property => property.Name).ToImmutableArray());

    public static JsonArray ToPropertyArray(ComponentDefinition component, bool includeId)
    {
        var array = new JsonArray();
        if (includeId && component.Id is not null)
            array.Add(PropertyNode("id", JsonValue.Create(component.Id)));
        foreach (var property in component.Properties)
            array.Add(PropertyNode(property.Name, ToNode(property.Value)));
        return array;
    }

    public static JsonArray ToMigratedPropertyArray(ComponentDefinition desired)
    {
        var array = new JsonArray();
        foreach (var property in desired.Properties)
        {
            array.Add(PropertyNode(property.Name, ToNode(property.Value)));
        }
        return array;
    }

    public static JsonArray ToPropertyArray(IReadOnlyDictionary<string, JsonElement> properties)
    {
        var array = new JsonArray();
        foreach (var property in ComparableProperties(properties))
        {
            array.Add(PropertyNode(property.Key, JsonNode.Parse(property.Value.GetRawText())));
        }
        return array;
    }

    public static JsonArray ToRestoredPropertyArray(
        IReadOnlyDictionary<string, JsonElement> before,
        IEnumerable<string> managedProperties)
    {
        var comparable = ComparableProperties(before);
        var array = ToPropertyArray(comparable);
        foreach (var property in managedProperties.Distinct(StringComparer.Ordinal))
        {
            if (!comparable.ContainsKey(property))
            {
                array.Add(PropertyNode(property, null));
            }
        }
        return array;
    }

    private static Dictionary<string, JsonElement> ComparableProperties(
        IReadOnlyDictionary<string, JsonElement> properties) => properties
        .Where(property => !IsFrameworkMetadata(property.Key))
        .ToDictionary(property => property.Key, property => property.Value, StringComparer.Ordinal);

    private static bool IsFrameworkMetadata(string propertyName) => propertyName is
        "lastChangeAt" or "lastChangeBy" or "_lastChangeAt" or "_lastChangeBy"
        or "service.pid" or "service.factoryPid" or "service.bundleLocation";

    private static bool ValuesEqual(ComponentValue desired, JsonElement actual) => desired switch
    {
        ComponentStringValue text => actual.ValueKind == JsonValueKind.String && actual.GetString() == text.Value,
        ComponentBooleanValue boolean => actual.ValueKind is JsonValueKind.True or JsonValueKind.False
            && actual.GetBoolean() == boolean.Value,
        ComponentNumberValue number => actual.ValueKind == JsonValueKind.Number
            && actual.TryGetDecimal(out var value) && value == number.Value,
        ComponentArrayValue array => actual.ValueKind == JsonValueKind.Array
            && array.Items.Length == actual.GetArrayLength()
            && array.Items.Zip(actual.EnumerateArray()).All(pair => ValuesEqual(pair.First, pair.Second)),
        _ => false,
    };

    private static JsonObject PropertyNode(string name, JsonNode? value) => new()
    {
        ["name"] = name,
        ["value"] = value,
    };

    private static JsonNode? ToNode(ComponentValue value) => value switch
    {
        ComponentStringValue text => JsonValue.Create(text.Value),
        ComponentBooleanValue boolean => JsonValue.Create(boolean.Value),
        ComponentNumberValue number => JsonValue.Create(number.Value),
        ComponentArrayValue array => new JsonArray(array.Items.Select(ToNode).ToArray()),
        _ => throw new ArgumentException("Unsupported component value.", nameof(value)),
    };
}
