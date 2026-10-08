using System.Text.Json;

namespace Maxiems.Deployer.Infrastructure.Manufacturing;

internal sealed class StrictJsonObject
{
    private readonly Dictionary<string, JsonElement> values = new(StringComparer.Ordinal);
    private readonly string scope;

    public StrictJsonObject(JsonElement element, string scope, params string[] allowedFields)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            throw new ManufacturingProfileException($"{scope} must be an object.");
        }
        this.scope = scope;
        var allowed = allowedFields.ToHashSet(StringComparer.Ordinal);
        foreach (var property in element.EnumerateObject())
        {
            if (!this.values.TryAdd(property.Name, property.Value))
            {
                throw new ManufacturingProfileException($"Duplicate field '{property.Name}' in {scope}.");
            }
            if (!allowed.Contains(property.Name))
            {
                throw new ManufacturingProfileException($"Unknown field '{property.Name}' in {scope}.");
            }
        }
    }

    public bool Has(string name) => this.values.ContainsKey(name);

    public JsonElement Required(string name) => this.values.TryGetValue(name, out var value)
        ? value
        : throw new ManufacturingProfileException($"Required field '{name}' is missing in {this.scope}.");

    public string RequiredString(string name, int maximumLength = 500)
    {
        var value = this.Required(name);
        if (value.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(value.GetString()))
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} must be a non-empty string.");
        }
        var result = value.GetString()!;
        if (result.Length > maximumLength)
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} is too long.");
        }
        return result;
    }

    public string? OptionalString(string name, int maximumLength = 500) => !this.Has(name)
        ? null
        : this.RequiredString(name, maximumLength);

    public int RequiredInt32(string name)
    {
        var value = this.Required(name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt32(out var result))
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} must be an integer.");
        }
        return result;
    }

    public long RequiredInt64(string name)
    {
        var value = this.Required(name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out var result))
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} must be an integer.");
        }
        return result;
    }

    public bool RequiredBoolean(string name)
    {
        var value = this.Required(name);
        if (value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} must be a boolean.");
        }
        return value.GetBoolean();
    }

    public JsonElement RequiredArray(string name)
    {
        var value = this.Required(name);
        if (value.ValueKind != JsonValueKind.Array)
        {
            throw new ManufacturingProfileException($"Field '{name}' in {this.scope} must be an array.");
        }
        return value;
    }
}
