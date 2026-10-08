using System.Security;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App.ViewModels;

public sealed partial class MainWindowViewModel
{
    private readonly Func<string, bool> fileExists;
    private readonly IWizardSshIdentityProvider? identityProvider;
    private readonly IWizardComponentProfileProvider? profileProvider;
    private DeploymentFinalState? finalState;
    private string resultDetails = string.Empty;

    public DeploymentFinalState? FinalState
    {
        get => this.finalState;
        private set
        {
            if (!this.SetProperty(ref this.finalState, value))
                return;
            this.Notify(nameof(this.ResultHeading));
            this.Notify(nameof(this.ResultSummary));
            this.Notify(nameof(this.ResultPanelHeading));
            this.Notify(nameof(this.ResultPanelSummary));
            this.Notify(nameof(this.ResultGlyph));
        }
    }

    public string ResultDetails
    {
        get => this.resultDetails;
        private set => this.SetProperty(ref this.resultDetails, value);
    }

    public string ResultHeading => this.FinalState switch
    {
        DeploymentFinalState.Succeeded => "Deployment verified",
        DeploymentFinalState.RolledBack => "Deployment rolled back",
        DeploymentFinalState.Cancelled => "Deployment cancelled",
        _ => "Deployment failed",
    };

    public string ResultSummary => this.FinalState switch
    {
        DeploymentFinalState.Succeeded => "The target release is active and every required health check passed.",
        DeploymentFinalState.RolledBack => "The attempted release failed. The previous healthy release was restored.",
        DeploymentFinalState.Cancelled => "The deployment was cancelled at a safe boundary.",
        _ => "The deployment did not complete successfully. Review the error before retrying.",
    };

    public string ResultPanelHeading => this.FinalState switch
    {
        DeploymentFinalState.Succeeded => "Evidence is ready",
        DeploymentFinalState.RolledBack => "Previous release restored",
        DeploymentFinalState.Cancelled => "Cancellation completed",
        _ => "No successful deployment recorded",
    };

    public string ResultPanelSummary => this.FinalState switch
    {
        DeploymentFinalState.Succeeded => "Reports include phase timing, artifact hashes and verified health checks.",
        DeploymentFinalState.RolledBack => "Open history to inspect the failed phase and independent rollback proof.",
        DeploymentFinalState.Cancelled => "Open history to inspect the phases completed before cancellation.",
        _ => "Correct the reported problem, return to Review and start a new deployment.",
    };

    public string ResultGlyph => this.FinalState switch
    {
        DeploymentFinalState.Succeeded => "✓",
        DeploymentFinalState.RolledBack => "↶",
        DeploymentFinalState.Cancelled => "!",
        _ => "×",
    };

    public async Task StartDeploymentAsync(
        SecureString administratorPassword,
        SecureString bootstrapPassword,
        SecureString keyPassphrase)
    {
        ArgumentNullException.ThrowIfNull(administratorPassword);
        ArgumentNullException.ThrowIfNull(bootstrapPassword);
        ArgumentNullException.ThrowIfNull(keyPassphrase);
        WizardSshIdentity? managedIdentity = null;
        try
        {
            if (this.IsBusy)
                return;
            if (string.IsNullOrWhiteSpace(this.Host) || this.Port is < 1 or > 65535
                || string.IsNullOrWhiteSpace(this.Username)
                || this.UseAdvancedAccess && string.IsNullOrWhiteSpace(this.PrivateKeyPath))
            {
                this.ValidationMessage = "Complete the target and access fields before deploying.";
                return;
            }
            if (this.UseAdvancedAccess && !this.ValidateKeyPair())
                return;
            string privateKeyPath;
            SecureString privateKeyPassphrase;
            string? manifestPath;
            try
            {
                if (this.UseAdvancedAccess)
                {
                    privateKeyPath = this.PrivateKeyPath;
                    privateKeyPassphrase = keyPassphrase;
                    manifestPath = string.IsNullOrWhiteSpace(this.ManifestPath) ? null : this.ManifestPath;
                }
                else
                {
                    managedIdentity = this.identityProvider?.GetOrCreate()
                        ?? throw new InvalidOperationException("Automatic SSH identity management is unavailable.");
                    privateKeyPath = managedIdentity.PrivateKeyPath;
                    privateKeyPassphrase = managedIdentity.PrivateKeyPassphrase;
                    manifestPath = this.profileProvider?.ResolveManifest(this.EnableModbusProfile);
                }
            }
            catch (Exception exception)
            {
                this.ValidationMessage = "Could not prepare secure device access: " + exception.Message;
                return;
            }
            this.ResetProgress();
            this.IsBusy = true;
            this.CurrentPage = WizardPage.Progress;
            this.DeploymentStartedAt = this.timeProvider.GetUtcNow();
            this.deploymentCancellation = new();
            this.elapsedTimer = this.timeProvider.CreateTimer(
                _ => this.context.Post(_ => this.RefreshElapsed(), null), null,
                TimeSpan.Zero, TimeSpan.FromSeconds(1));
            try
            {
                var request = new WizardDeploymentRequest(new(this.Host, this.Port), this.Username, privateKeyPath,
                    manifestPath, bootstrapPassword, administratorPassword, privateKeyPassphrase, this.EnableTailscale);
                await foreach (var update in this.orchestrator.RunAsync(request, this.deploymentCancellation.Token))
                {
                    await this.ApplyAsync(update);
                }
                if (this.FinalState is null)
                {
                    this.SetFinalState(DeploymentFinalState.Failed, "Deployment ended without a terminal result.");
                    this.CurrentPhase = "Failed";
                }
                this.CurrentPage = WizardPage.Result;
            }
            catch (OperationCanceledException) when (this.deploymentCancellation.IsCancellationRequested)
            {
                this.CurrentPhase = "Cancelled";
                this.ValidationMessage = "Deployment cancellation completed safely.";
                this.SetFinalState(DeploymentFinalState.Cancelled, this.ValidationMessage);
                this.CurrentPage = WizardPage.Result;
            }
            catch (Exception exception)
            {
                this.CurrentPhase = "Failed";
                this.ValidationMessage = exception.Message;
                this.SetFinalState(DeploymentFinalState.Failed, exception.Message);
                this.CurrentPage = WizardPage.Result;
            }
            finally
            {
                this.TailscaleLoginUrl = null;
                this.elapsedTimer?.Dispose();
                this.elapsedTimer = null;
                this.deploymentCancellation.Dispose();
                this.deploymentCancellation = null;
                this.IsBusy = false;
            }
        }
        finally
        {
            managedIdentity?.Dispose();
            administratorPassword.Dispose();
            bootstrapPassword.Dispose();
            keyPassphrase.Dispose();
        }
    }

    private bool ValidateKeyPair()
    {
        if (this.fileExists(this.PrivateKeyPath) && this.fileExists(this.PrivateKeyPath + ".pub"))
            return true;
        this.ValidationMessage = "SSH key pair not found. Select a private key path with a matching .pub public key. "
            + "Or turn off technician mode to let MAXiEMS manage the key automatically.";
        return false;
    }

    private void CaptureResult(WizardProgressUpdate update)
    {
        if (update.Phase is not DeploymentPhase.Complete
            && update.Outcome == DeploymentPhaseOutcome.Failed
            && !string.IsNullOrWhiteSpace(update.OperatorMessage))
        {
            this.ResultDetails = update.OperatorMessage;
        }
        if (update.CompletedHistory is not { } history)
            return;
        this.FinalState = history.FinalState;
        if (history.FinalState == DeploymentFinalState.Succeeded)
            this.ResultDetails = string.Empty;
    }

    private void SetFinalState(DeploymentFinalState state, string details)
    {
        this.FinalState = state;
        this.ResultDetails = details;
    }

    private void ResetResult()
    {
        this.FinalState = null;
        this.ResultDetails = string.Empty;
    }
}
