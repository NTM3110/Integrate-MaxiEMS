namespace Maxiems.Deployer.Core.Lifecycle;

public enum DeploymentState
{
    ValidateInput,
    ConnectBootstrap,
    Preflight,
    ProvisionIdentity,
    ReconnectRestricted,
    InstallPrerequisites,
    StageRelease,
    VerifyRemotePayload,
    ActivateRelease,
    StartServices,
    ConfigureTailscale,
    ReconcileComponents,
    HealthCheck,
    Rollback,
    Complete,
    Cancelled,
    RolledBack,
    Failed,
}
