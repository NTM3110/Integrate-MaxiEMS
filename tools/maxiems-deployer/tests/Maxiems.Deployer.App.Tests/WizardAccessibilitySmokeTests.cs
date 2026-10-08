using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Data;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
[TestCategory("Wizard")]
public sealed class WizardAccessibilitySmokeTests
{
    [STATestMethod]
    public void KeyboardCommandPathReachesReviewAndAccessibleProgressReturnsFromCancel()
    {
        var trust = new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.FirstSeen));
        var orchestrator = new RunningOrchestrator();
        var clock = new ManualTimeProvider(DateTimeOffset.Parse(
            "2026-07-19T01:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
        using var viewModel = new MainWindowViewModel(
            orchestrator, new MemoryWizardHistory(), trust, new RecordingReportLauncher(), clock, new(), _ => true);
        var window = new MainWindow(viewModel);
        window.ApplyTemplate();
        window.RaiseEvent(new RoutedEventArgs(FrameworkElement.LoadedEvent));
        window.UpdateLayout();

        var labels = Descendants<Label>(window).ToArray();
        Assert.IsTrue(labels.Length >= 8);
        Assert.IsTrue(labels.All(label => !string.IsNullOrWhiteSpace(
            BindingOperations.GetBindingExpression(label, Label.TargetProperty)?.ParentBinding.ElementName)));
        Assert.IsTrue(labels.All(label => label.Content is string content
            && content.Contains('_', StringComparison.Ordinal)));
        var passwordBoxes = Descendants<PasswordBox>(window).ToArray();
        Assert.AreEqual(5, passwordBoxes.Length);
        Assert.IsNull(typeof(PasswordBox).GetField("PasswordProperty",
            System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static));
        Assert.IsFalse(typeof(MainWindowViewModel).GetProperties().Any(property =>
            property.PropertyType == typeof(string)
            && (property.Name.Contains("password", StringComparison.OrdinalIgnoreCase)
                || property.Name.Contains("passphrase", StringComparison.OrdinalIgnoreCase))));
        Assert.IsTrue(passwordBoxes.All(box => !string.IsNullOrWhiteSpace(AutomationProperties.GetName(box))));
        Assert.IsTrue(Descendants<Button>(window).All(button =>
            button.Content is string content && content.Contains('_', StringComparison.Ordinal)));
        Assert.IsTrue(Descendants<TextBlock>(window).Count(block =>
            AutomationProperties.GetLiveSetting(block) != AutomationLiveSetting.Off) >= 3);

        viewModel.EvaluateTrustAsync().GetAwaiter().GetResult();
        Assert.AreEqual(WizardPage.Target, viewModel.CurrentPage);
        viewModel.Host = "edge.fixture.test";
        viewModel.EvaluateTrustAsync().GetAwaiter().GetResult();
        viewModel.ConfirmTrustAsync().GetAwaiter().GetResult();
        Assert.AreEqual(WizardPage.Trust, viewModel.CurrentPage, "Trust must not change without deliberate confirmation.");
        viewModel.ReplacementRiskAcknowledged = true;
        viewModel.ConfirmTrustAsync().GetAwaiter().GetResult();
        viewModel.UseAdvancedAccess = true;
        viewModel.PrivateKeyPath = "C:\\fixture\\id_ed25519";
        viewModel.NextCommand.Execute(null);
        viewModel.NextCommand.Execute(null);
        Assert.AreEqual(WizardPage.Review, viewModel.CurrentPage);

        const string administratorSecret = "visual-admin-secret";
        const string passphraseSecret = "visual-passphrase-secret";
        var deployment = viewModel.StartDeploymentAsync(
            WizardTestSupport.Secret(administratorSecret), WizardTestSupport.Secret("bootstrap-secret"),
            WizardTestSupport.Secret(passphraseSecret));
        orchestrator.Started.Task.GetAwaiter().GetResult();
        clock.Advance(TimeSpan.FromSeconds(3));
        viewModel.RefreshElapsed();
        Assert.AreEqual(DeploymentPhaseOutcome.Running,
            viewModel.Timeline.Single(row => row.Phase == DeploymentPhase.Preflight).Outcome);
        Assert.AreEqual(TimeSpan.FromSeconds(3), viewModel.Elapsed);
        var visualSnapshot = string.Join("\n",
            Descendants<TextBlock>(window).Select(block => block.Text)
                .Concat(Descendants<TextBox>(window).Select(box => box.Text))
                .Concat(passwordBoxes.Select(box => box.Password)));
        Assert.IsFalse(visualSnapshot.Contains(administratorSecret, StringComparison.Ordinal));
        Assert.IsFalse(visualSnapshot.Contains(passphraseSecret, StringComparison.Ordinal));
        viewModel.CancelCommand.Execute(null);
        deployment.GetAwaiter().GetResult();
        Assert.AreEqual(WizardPage.Result, viewModel.CurrentPage);
        window.UpdateLayout();
        Assert.AreEqual("Deployment cancelled", viewModel.ResultHeading);
        var resultHeading = Descendants<TextBlock>(window).Single(block =>
            AutomationProperties.GetName(block) == "Deployment result");
        Assert.AreEqual(nameof(MainWindowViewModel.ResultHeading),
            BindingOperations.GetBindingExpression(resultHeading, TextBlock.TextProperty)?.ParentBinding.Path.Path);
        Assert.IsFalse(Descendants<TextBlock>(window).Any(block => block.Text == "Deployment verified"),
            "A cancelled run must never retain the success result presentation.");
        Assert.IsTrue(viewModel.BackCommand.CanExecute(null));
        viewModel.BackCommand.Execute(null);
        Assert.AreEqual(WizardPage.Review, viewModel.CurrentPage, "A failed or cancelled run must have a retry boundary.");
        window.Close();
    }

    private static IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())
        {
            if (child is T match)
                yield return match;
            foreach (var descendant in Descendants<T>(child))
                yield return descendant;
        }
    }

    private sealed class RunningOrchestrator : IWizardDeploymentOrchestrator
    {
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(
            WizardDeploymentRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            this.Started.TrySetResult();
            yield return new(DeploymentPhase.Preflight, DeploymentPhaseOutcome.Running, DateTimeOffset.UtcNow,
                "Checking Ubuntu fixture prerequisites.");
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        }
    }
}
