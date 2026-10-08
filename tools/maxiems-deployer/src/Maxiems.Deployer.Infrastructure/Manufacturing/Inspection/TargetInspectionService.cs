using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;

public sealed record TargetInspectionConnection(
    HostEndpoint Endpoint,
    string Username,
    ReadOnlyMemory<char> Password,
    string PrivateKeyPath,
    ReadOnlyMemory<char> PrivateKeyPassphrase);

public interface IReadOnlyInspectionSession : IRemoteCommandTransport, IAsyncDisposable;

public interface ITargetInspectionSessionFactory
{
    Task<IReadOnlyInspectionSession> ConnectAsync(
        TargetInspectionConnection connection,
        CancellationToken cancellationToken = default);
}

public sealed class TargetInspectionService(
    ITargetInspectionSessionFactory sessionFactory,
    TimeProvider? timeProvider = null)
{
    public async Task<ReadOnlyTargetInspection> InspectAsync(TargetInspectionConnection connection,
        LocalReleaseDescriptor selectedRelease, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        await using var session = await sessionFactory.ConnectAsync(connection, cancellationToken).ConfigureAwait(false);
        return await new ReadOnlyTargetInspectionCollector(session, timeProvider)
            .CollectAsync(connection.Endpoint, selectedRelease, cancellationToken).ConfigureAwait(false);
    }
}

public sealed class SshTargetInspectionSessionFactory(HostTrustStore trustStore) : ITargetInspectionSessionFactory
{
    public async Task<IReadOnlyInspectionSession> ConnectAsync(TargetInspectionConnection connection,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        var options = new SshTransportOptions(connection.Endpoint, connection.Username, connection.Password,
            connection.PrivateKeyPath, connection.PrivateKeyPassphrase, TimeSpan.FromSeconds(20), TimeSpan.FromMinutes(3));
        var session = await SshBootstrapSession.ConnectAsync(options, trustStore, cancellationToken).ConfigureAwait(false);
        return new ReadOnlySshInspectionSession(session);
    }

    private sealed class ReadOnlySshInspectionSession(SshBootstrapSession session) : IReadOnlyInspectionSession
    {
        public Task<RemoteCommandResult> ExecuteAsync(RemoteCommandRequest request,
            CancellationToken cancellationToken = default) => session.ExecuteAsync(request, cancellationToken);
        public ValueTask DisposeAsync() => session.DisposeAsync();
    }
}
