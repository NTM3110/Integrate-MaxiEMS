using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Contracts;

public sealed record ComponentManifest(
    int SchemaVersion,
    ImmutableArray<ComponentDefinition> Components);

public sealed record ComponentDefinition(
    string FactoryPid,
    string? Id,
    ImmutableArray<ComponentProperty> Properties,
    bool UpdateOnly = false);

public sealed record ComponentProperty(string Name, ComponentValue Value);

public abstract record ComponentValue;

public sealed record ComponentStringValue(string Value) : ComponentValue;

public sealed record ComponentNumberValue(decimal Value) : ComponentValue;

public sealed record ComponentBooleanValue(bool Value) : ComponentValue;

public sealed record ComponentArrayValue(ImmutableArray<ComponentValue> Items) : ComponentValue;
