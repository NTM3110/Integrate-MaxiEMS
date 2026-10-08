using System.Collections.Immutable;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Renci.SshNet.Common;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

public sealed class HardwareInventoryCollector(IRemoteCommandTransport transport, TimeProvider? timeProvider = null)
{
    private const int MaximumProbeCharacters = 1024 * 1024;
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public async Task<HardwareInventorySnapshot> CollectAsync(CancellationToken cancellationToken = default)
    {
        var linksProbe = await ObserveAsync(RemoteProgram.NetworkLinksProbe, false, cancellationToken).ConfigureAwait(false);
        var links = NetworkInventoryParser.Parse(linksProbe.Output);
        var ethernet = ImmutableArray.CreateBuilder<EthernetPortInventory>();
        foreach (var link in links)
        {
            var permanentProbe = await ObserveAsync(RemoteProgram.PermanentMacProbe, true, cancellationToken,
                [new(link.LinuxName)]).ConfigureAwait(false);
            var permanent = permanentProbe.Available
                ? NetworkInventoryParser.ParsePermanentMac(permanentProbe.Output) : null;
            var availability = permanent is null ? EvidenceAvailability.Unavailable : EvidenceAvailability.Observed;
            ethernet.Add(new(link.LinuxName, null, link.CurrentMac, permanent, link.LinkUp, link.SpeedMegabits,
                new("ip link + ethtool", availability, permanent is null
                    ? $"Permanent MAC unavailable for {link.LinuxName}."
                    : $"{link.LinuxName}: current {link.CurrentMac}; permanent {permanent}")));
        }

        var cpuProbe = await ObserveAsync(RemoteProgram.CpuInventoryProbe, false, cancellationToken).ConfigureAwait(false);
        var memoryProbe = await ObserveAsync(RemoteProgram.MemoryProbe, false, cancellationToken).ConfigureAwait(false);
        var storageProbe = await ObserveAsync(RemoteProgram.BlockDeviceProbe, false, cancellationToken).ConfigureAwait(false);
        var osProbe = await ObserveAsync(RemoteProgram.OsReleaseProbe, false, cancellationToken).ConfigureAwait(false);
        var kernelProbe = await ObserveAsync(RemoteProgram.KernelInventoryProbe, false, cancellationToken).ConfigureAwait(false);
        var boardProbe = await ObserveAsync(RemoteProgram.BoardInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var deviceTreeBoardProbe = await ObserveAsync(RemoteProgram.DeviceTreeBoardInventoryProbe, true, cancellationToken)
            .ConfigureAwait(false);
        var imageProbe = await ObserveAsync(RemoteProgram.ImageVersionProbe, true, cancellationToken).ConfigureAwait(false);
        var usb = await ObserveAsync(RemoteProgram.UsbInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var pci = await ObserveAsync(RemoteProgram.PciInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var modem = await ObserveAsync(RemoteProgram.ModemInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var wireless = await ObserveAsync(RemoteProgram.WirelessInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var bluetooth = await ObserveAsync(RemoteProgram.BluetoothInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var nodes = await ObserveAsync(RemoteProgram.DeviceNodeInventoryProbe, true, cancellationToken).ConfigureAwait(false);
        var sdio = await ObserveAsync(RemoteProgram.SdioInventoryProbe, true, cancellationToken).ConfigureAwait(false);

        var cpu = SystemInventoryParser.ParseCpu(cpuProbe.Output);
        var peripherals = ImmutableArray.CreateBuilder<HardwarePeripheralInventory>();
        AddPeripheralProbe(peripherals, HardwareBus.Usb, usb, PeripheralInventoryParser.ParseUsb);
        AddPeripheralProbe(peripherals, HardwareBus.PciExpress, pci, PeripheralInventoryParser.ParsePci);
        AddPathProbe(peripherals, HardwareBus.Uart, nodes, PeripheralInventoryParser.ParseDeviceNodes(nodes.Output));
        AddPathProbe(peripherals, HardwareBus.Sdio, sdio, ParsePaths(sdio.Output));
        return new(this.clock.GetUtcNow(), cpu, SystemInventoryParser.ParseMemory(memoryProbe.Output),
            SystemInventoryParser.ParseStorage(storageProbe.Output),
            SystemInventoryParser.ParseOperatingSystem(osProbe.Output, kernelProbe.Output,
                boardProbe.Available ? boardProbe.Output
                    : deviceTreeBoardProbe.Available ? deviceTreeBoardProbe.Output : string.Empty,
                imageProbe.Available ? imageProbe.Output : string.Empty, cpu.Architecture),
            ethernet.ToImmutable(), PeripheralInventoryParser.ParseModules(modem, wireless, bluetooth),
            peripherals.ToImmutable());
    }

    private async Task<ProbeObservation> ObserveAsync(RemoteProgram program, bool optional,
        CancellationToken cancellationToken, ImmutableArray<RemoteArgument> arguments = default)
    {
        var request = new RemoteCommandRequest(program, arguments.IsDefault ? [] : arguments, TimeSpan.FromSeconds(15));
        RemoteCommandResult result;
        try
        {
            result = await transport.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (optional && !cancellationToken.IsCancellationRequested
            && exception is OperationCanceledException or TimeoutException or SshOperationTimeoutException)
        {
            return new(false, string.Empty, $"{program} timed out and was recorded as unavailable.");
        }
        if (result.StandardOutput.Length > MaximumProbeCharacters || result.StandardError.Length > MaximumProbeCharacters)
        {
            throw new HardwareInventoryException($"{program} exceeded the bounded output limit.");
        }
        if (result.ExitCode != 0 && !optional)
        {
            throw new HardwareInventoryException($"Required hardware probe {program} failed with exit {result.ExitCode}.");
        }
        return new(result.ExitCode == 0, result.StandardOutput, Bound(string.IsNullOrWhiteSpace(result.StandardError)
            ? $"{program} unavailable (exit {result.ExitCode})." : result.StandardError));
    }

    private static void AddPeripheralProbe(ImmutableArray<HardwarePeripheralInventory>.Builder target, HardwareBus bus,
        ProbeObservation probe, Func<string, ImmutableArray<HardwarePeripheralInventory>> parser)
    {
        if (probe.Available)
        {
            target.AddRange(parser(probe.Output));
        }
        else
        {
            target.Add(new(bus, null, null, null, null, [], [],
                new(bus.ToString(), EvidenceAvailability.Unavailable, probe.Summary)));
        }
    }

    private static void AddPathProbe(ImmutableArray<HardwarePeripheralInventory>.Builder target, HardwareBus bus,
        ProbeObservation probe, ImmutableArray<string> paths)
    {
        target.Add(new(bus, null, null, null, null, [], paths, new(bus.ToString(),
            !probe.Available ? EvidenceAvailability.Unavailable
                : paths.Length == 0 ? EvidenceAvailability.NotApplicable : EvidenceAvailability.Observed,
            !probe.Available ? probe.Summary : paths.Length == 0 ? "No matching device found." : Bound(string.Join(", ", paths)))));
    }

    private static ImmutableArray<string> ParsePaths(string output) => output
        .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
        .Where(path => path.Length <= 160 && path.StartsWith('/'))
        .Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToImmutableArray();
    private static string Bound(string value) => new(value.Where(character => !char.IsControl(character)).Take(512).ToArray());
}
