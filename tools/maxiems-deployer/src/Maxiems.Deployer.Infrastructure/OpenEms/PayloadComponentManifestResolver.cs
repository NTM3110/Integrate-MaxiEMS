using System.Collections.Immutable;
using System.IO.Compression;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public static class PayloadComponentManifestResolver
{
    internal const string BundledManifestPath = "components/managed-components.v1.json";
    private const int MaximumManifestBytes = 1024 * 1024;

    public static ComponentManifest Resolve(string payloadPath, string? externalManifestPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(payloadPath);
        var bundled = ReadBundled(payloadPath);
        var external = externalManifestPath is null
            ? null
            : ComponentManifestParser.Parse(File.ReadAllText(externalManifestPath));
        return Merge(bundled, external);
    }

    private static ComponentManifest ReadBundled(string payloadPath)
    {
        using var archive = ZipFile.OpenRead(payloadPath);
        var entries = archive.Entries.Where(entry =>
            entry.FullName.Equals(BundledManifestPath, StringComparison.Ordinal)).ToArray();
        if (entries.Length == 0)
        {
            throw new ComponentManifestException(
                $"Payload is missing the required component migration manifest '{BundledManifestPath}'. Build a full release before deploying.");
        }
        if (entries.Length != 1 || entries[0].Length > MaximumManifestBytes)
        {
            throw new ComponentManifestException("The payload component manifest is duplicate or too large.");
        }
        using var stream = entries[0].Open();
        using var reader = new StreamReader(stream);
        return ComponentManifestParser.Parse(reader.ReadToEnd());
    }

    private static ComponentManifest Merge(ComponentManifest bundled, ComponentManifest? external)
    {
        if (external is null)
        {
            return bundled;
        }
        var components = ImmutableArray.CreateBuilder<ComponentDefinition>();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var factorySelectors = new HashSet<string>(StringComparer.Ordinal);
        var factoriesWithExactIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var component in bundled.Components.Concat(external.Components))
        {
            if (component.Id is not null && !ids.Add(component.Id))
            {
                throw new ComponentManifestException(
                    $"Bundled and external component manifests both manage component id '{component.Id}'.");
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
        return new(1, components.ToImmutable());
    }
}
