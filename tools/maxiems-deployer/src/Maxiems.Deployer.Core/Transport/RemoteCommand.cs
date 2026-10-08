using System.Collections.Immutable;

namespace Maxiems.Deployer.Core.Transport;

public enum RemoteProgram
{
    DeployHelper,
    NetworkHelper,
    OsReleaseProbe,
    ArchitectureProbe,
    DiskProbe,
    MemoryProbe,
    TimeProbe,
    DnsProbe,
    SudoProbe,
    PortProbe,
    ServiceProbe,
    LegacyServiceEnabledProbe,
    AccountProbe,
    CurrentIdentityProbe,
    BroadSudoDenialProbe,
    SudoAllowlistProbe,
    NetworkLinksProbe,
    PermanentMacProbe,
    CpuInventoryProbe,
    BlockDeviceProbe,
    KernelInventoryProbe,
    BoardInventoryProbe,
    DeviceTreeBoardInventoryProbe,
    ImageVersionProbe,
    UsbInventoryProbe,
    PciInventoryProbe,
    ModemInventoryProbe,
    WirelessInventoryProbe,
    BluetoothInventoryProbe,
    DeviceNodeInventoryProbe,
    SdioInventoryProbe,
    ManagedServiceEnabledProbe,
    CurrentReleaseProbe,
    ConfigurationPresentProbe,
    RollbackReleaseProbe,
    FailureProbe,
}

public interface IRemoteCommandTransport
{
    Task<RemoteCommandResult> ExecuteAsync(
        RemoteCommandRequest request,
        CancellationToken cancellationToken = default);
}

public interface IRemoteFileTransport
{
    Task<SftpUploadResult> UploadToRandomStagingAsync(
        Stream source,
        CancellationToken cancellationToken = default);
}

public sealed record RemoteArgument(string Value);

public sealed record RemoteCommandRequest(
    RemoteProgram Program,
    ImmutableArray<RemoteArgument> Arguments,
    TimeSpan Timeout);

public sealed record RemoteCommandResult(
    int ExitCode,
    string StandardOutput,
    string StandardError,
    TimeSpan Duration);

public sealed record SftpUploadResult(
    string RemoteStagingPath,
    long Length,
    string Sha256);
