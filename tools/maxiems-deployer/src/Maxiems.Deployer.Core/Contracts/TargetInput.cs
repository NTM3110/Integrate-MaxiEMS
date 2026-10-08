namespace Maxiems.Deployer.Core.Contracts;

public sealed record HostEndpoint(string Host, int Port);

public sealed record PrivateKeyPath(string Value);

public readonly record struct SecretHandle(Guid Value);

public sealed record BootstrapIdentity(
    string Username,
    PrivateKeyPath PrivateKey,
    SecretHandle? PrivateKeyPassphrase);

public sealed record ComponentManifestPath(string Value);

public sealed record DeploymentTargetInput(
    HostEndpoint Endpoint,
    BootstrapIdentity BootstrapIdentity,
    SshHostFingerprint? ExpectedHostFingerprint,
    SecretHandle DesiredOpenemsAdminPassword,
    ComponentManifestPath? ComponentManifest,
    bool EnableTailscale);

public enum SupportedArchitecture
{
    Amd64,
    Arm64,
}
