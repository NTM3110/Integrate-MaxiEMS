namespace Maxiems.Deployer.Core.Security;

/// <summary>
/// Enforces the deployment boundary shared by OpenEMS management-password inputs.
/// </summary>
public static class OpenEmsManagementPassword
{
    public static void RequireNonEmpty(ReadOnlySpan<char> password, string parameterName)
    {
        if (password.IsEmpty)
        {
            throw new ArgumentException("OpenEMS management password must not be empty.", parameterName);
        }
    }
}
