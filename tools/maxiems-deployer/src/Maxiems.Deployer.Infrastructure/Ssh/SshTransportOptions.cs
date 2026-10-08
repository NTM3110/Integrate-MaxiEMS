using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.Infrastructure.Ssh;

public sealed record SshTransportOptions(
    HostEndpoint Endpoint,
    string Username,
    ReadOnlyMemory<char> BootstrapPassword,
    string PrivateKeyPath,
    ReadOnlyMemory<char> PrivateKeyPassphrase,
    TimeSpan ConnectTimeout,
    TimeSpan OperationTimeout);
