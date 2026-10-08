using System.Reflection;

namespace Maxiems.Deployer.Core.Tests;

internal static class ArchitecturePolicy
{
    private static readonly string[] ForbiddenNamespacePrefixes =
    [
        "Maxiems.Deployer.Infrastructure",
        "Renci.SshNet",
        "System.IO",
        "System.Net.Http",
        "System.Windows",
    ];

    public static IReadOnlyList<string> FindAssemblyViolations(Assembly assembly)
    {
        var violations = new List<string>();
        foreach (var reference in assembly.GetReferencedAssemblies())
        {
            if (reference.Name is not null && IsForbidden(reference.Name))
            {
                violations.Add($"Assembly reference: {reference.Name}");
            }
        }

        foreach (var type in assembly.GetExportedTypes())
        {
            FindTypeViolations(type, violations);
        }

        return violations;
    }

    public static IReadOnlyList<string> FindContractViolations(Type contractType)
    {
        var violations = new List<string>();
        FindTypeViolations(contractType, violations);
        return violations;
    }

    private static void FindTypeViolations(Type type, ICollection<string> violations)
    {
        InspectType(type, $"Type {type.FullName}", violations);
        foreach (var constructor in type.GetConstructors())
        {
            foreach (var parameter in constructor.GetParameters())
            {
                InspectType(parameter.ParameterType, $"Constructor {type.FullName}", violations);
            }
        }

        foreach (var property in type.GetProperties())
        {
            InspectType(property.PropertyType, $"Property {type.FullName}.{property.Name}", violations);
        }
    }

    private static void InspectType(Type type, string location, ICollection<string> violations)
    {
        var candidate = Nullable.GetUnderlyingType(type) ?? type;
        if (candidate.IsArray)
        {
            InspectType(candidate.GetElementType()!, location, violations);
            return;
        }

        if (candidate.IsGenericType)
        {
            foreach (var argument in candidate.GetGenericArguments())
            {
                InspectType(argument, location, violations);
            }
        }

        if (candidate.Namespace is not null && IsForbidden(candidate.Namespace))
        {
            violations.Add($"{location}: {candidate.FullName}");
        }
    }

    private static bool IsForbidden(string value) => ForbiddenNamespacePrefixes.Any(
        prefix => value.Equals(prefix, StringComparison.Ordinal)
            || value.StartsWith($"{prefix}.", StringComparison.Ordinal));
}
