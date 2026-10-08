namespace Maxiems.Deployer.Core.Lifecycle;

public sealed class DeploymentJournal
{
    private readonly List<IDeploymentStep> entries = [];
    private readonly HashSet<IDeploymentStep> compensated = new(ReferenceEqualityComparer.Instance);

    public int Count => this.entries.Count;

    public void Register(IDeploymentStep step)
    {
        ArgumentNullException.ThrowIfNull(step);
        if (!this.entries.Contains(step, ReferenceEqualityComparer.Instance))
        {
            this.entries.Add(step);
        }
    }

    public async Task CompensateAsync(CancellationToken cancellationToken = default)
    {
        for (var index = this.entries.Count - 1; index >= 0; index--)
        {
            var step = this.entries[index];
            if (!this.compensated.Add(step))
            {
                continue;
            }
            await step.CompensateAsync(cancellationToken).ConfigureAwait(false);
        }
    }
}
