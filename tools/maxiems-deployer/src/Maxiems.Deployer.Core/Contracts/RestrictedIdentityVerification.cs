namespace Maxiems.Deployer.Core.Contracts;

public sealed record RestrictedIdentityVerification(
    bool IsDeployIdentity,
    bool BroadSudoDenied,
    bool ExactHelperAllowlistPresent)
{
    public bool IsValid => IsDeployIdentity && BroadSudoDenied && ExactHelperAllowlistPresent;
}
