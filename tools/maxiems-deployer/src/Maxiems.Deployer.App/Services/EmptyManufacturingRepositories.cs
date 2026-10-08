using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.Services;

internal sealed class EmptyManufacturingRepositories : IManufacturingOperationsRepository, IDeploymentRunRepository
{
    public Task<ManufacturingDashboardCounts> GetDashboardAsync(CancellationToken cancellationToken = default) =>
        Task.FromResult(new ManufacturingDashboardCounts(0, 0, 0, 0, 0, 0));
    public Task<IReadOnlyList<ManufacturingProfile>> ListProfilesAsync(CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<ManufacturingProfile>>([]);
    public Task<IReadOnlyList<ManufacturingDeviceSummary>> SearchDevicesAsync(string? query, int limit = 100,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<ManufacturingDeviceSummary>>([]);
    public Task AppendAsync(StoredDeploymentRun run, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task<IReadOnlyList<ManufacturingHistoryEntry>> SearchAsync(string? query, int limit = 100,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<ManufacturingHistoryEntry>>([]);
}
