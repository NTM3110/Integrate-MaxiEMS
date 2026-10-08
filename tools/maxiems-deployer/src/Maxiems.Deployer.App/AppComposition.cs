using System.IO;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Deployment;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.App;

public static class AppComposition
{
    public static MainWindowViewModel CreateViewModel()
    {
        var appData = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "MAXiEMS",
            "Deployer");
        var trustStore = new HostTrustStore(Path.Combine(appData, "ssh-trust.json"));
        var trust = new HostTrustWizardEvaluator(
            new(new SshNetHostKeyProbe(), trustStore),
            TimeSpan.FromSeconds(15));
        var history = new JsonDeploymentHistory(Path.Combine(appData, "history.json"));
        var payloadProvider = new EmbeddedPayloadProvider();
        var orchestrator = new ProductionWizardOrchestrator(
            new DeploymentEngine(trustStore),
            payloadProvider,
            Path.Combine(appData, "reports"));
        var identity = new ManagedSshIdentityProvider(Path.Combine(appData, "keys"));
        var profiles = new EmbeddedComponentProfileProvider(Path.Combine(appData, "profiles"));
        var store = new SqliteManufacturingStore(Path.Combine(appData, "manufacturing.db"));
        var operations = new ManufacturingOperationsRepository(store);
        var runs = new DeploymentRunRepository(store);
        var profileRepository = new ManufacturingProfileRepository(store);
        var manufacturingWorkflow = new ProductionManufacturingWorkflowBackend(trustStore, store, identity,
            payloadProvider, Path.Combine(appData, "reports"));
        var releaseIdProvider = new EmbeddedReleaseIdProvider(payloadProvider);
        var backupService = new ManufacturingBackupService(store);
        var exportService = new ManufacturingExportService(store);
        var localizer = new AppLocalizationService(Path.Combine(appData, "settings.json"));
        return new(orchestrator, history, trust, new ShellReportLauncher(),
            identityProvider: identity, profileProvider: profiles,
            operationsRepository: operations, deploymentRunRepository: runs,
            manufacturingProfileRepository: profileRepository,
            manufacturingWorkflowBackend: manufacturingWorkflow,
            backupService: backupService, exportService: exportService, localizer: localizer,
            currentReleaseIdProvider: releaseIdProvider.GetAsync);
    }

    private sealed class ShellReportLauncher : IWizardReportLauncher
    {
        public void Open(string path)
        {
            if (!File.Exists(path))
                return;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true });
        }
    }

}
