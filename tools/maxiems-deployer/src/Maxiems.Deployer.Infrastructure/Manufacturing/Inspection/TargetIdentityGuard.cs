using System.Collections.Immutable;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;

public sealed class TargetIdentityGuard(IRemoteCommandTransport transport)
{
    public async Task VerifyAsync(TargetIdentityExpectation expected,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(expected);
        if (expected.PermanentMacAddresses.IsDefaultOrEmpty)
        {
            throw new InvalidDataException("The reviewed inspection has no permanent MAC identity.");
        }
        var links = await transport.ExecuteAsync(new(RemoteProgram.NetworkLinksProbe, [], TimeSpan.FromSeconds(10)),
            cancellationToken).ConfigureAwait(false);
        if (links.ExitCode != 0)
            throw new IOException("Unable to re-probe target network identity.");
        var observed = ImmutableArray.CreateBuilder<MacAddress>();
        foreach (var link in NetworkInventoryParser.Parse(links.StandardOutput))
        {
            var result = await transport.ExecuteAsync(new(RemoteProgram.PermanentMacProbe,
                [new(link.LinuxName)], TimeSpan.FromSeconds(10)), cancellationToken).ConfigureAwait(false);
            if (result.ExitCode == 0 && NetworkInventoryParser.ParsePermanentMac(result.StandardOutput) is { } mac)
            {
                observed.Add(mac);
            }
        }
        var expectedValues = expected.PermanentMacAddresses.Select(mac => mac.Normalized)
            .Order(StringComparer.Ordinal).ToArray();
        var observedValues = observed.Select(mac => mac.Normalized).Distinct(StringComparer.Ordinal)
            .Order(StringComparer.Ordinal).ToArray();
        if (!expectedValues.SequenceEqual(observedValues, StringComparer.Ordinal))
        {
            throw new InvalidOperationException("Target identity changed after inspection; inspect the device again.");
        }
    }
}
