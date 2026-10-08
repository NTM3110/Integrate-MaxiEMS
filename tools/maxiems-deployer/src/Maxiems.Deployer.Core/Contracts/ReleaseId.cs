namespace Maxiems.Deployer.Core.Contracts;

public readonly record struct ReleaseId(string Value)
{
    public override string ToString() => this.Value;
}
