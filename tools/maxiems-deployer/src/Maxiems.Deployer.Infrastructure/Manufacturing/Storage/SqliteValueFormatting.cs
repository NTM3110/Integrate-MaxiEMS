using System.Globalization;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

internal static class SqliteValueFormatting
{
    public static string Endpoint(HostEndpoint endpoint)
    {
        var host = endpoint.Host.Contains(':', StringComparison.Ordinal) ? $"[{endpoint.Host}]" : endpoint.Host;
        return string.Create(CultureInfo.InvariantCulture, $"{host}:{endpoint.Port}");
    }
}
