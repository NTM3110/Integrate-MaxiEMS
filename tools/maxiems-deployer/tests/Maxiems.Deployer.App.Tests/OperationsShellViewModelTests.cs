using System.Collections.Immutable;
using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.App.ViewModels.Operations;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing;

namespace Maxiems.Deployer.App.Tests;

[TestClass]
public sealed class OperationsShellViewModelTests
{
    private static readonly string[] ExpectedDeviceQueries = ["MR00613A7D755A", "00613A7D755B"];
    private static readonly string[] ExpectedNavigation = ["Dashboard", "Profiles", "Devices", "Deployments", "History", "Settings"];
    [TestMethod]
    public async Task NavigationLoadsOperationsAndBlocksLeavingActiveDeployment()
    {
        var repository = new MemoryOperationsRepository();
        var busy = false;
        var shell = new OperationsShellViewModel(repository, repository, () => busy);

        await shell.LoadAsync();
        shell.NavigateCommand.Execute("Devices");
        busy = true;
        shell.RefreshBusyState();
        shell.NavigateCommand.Execute("Settings");

        Assert.AreEqual(OperationsSection.Devices, shell.CurrentSection);
        Assert.IsFalse(shell.NavigateCommand.CanExecute("Settings"));
        Assert.AreEqual(3, shell.Dashboard.Counts.Devices);
        Assert.HasCount(1, shell.Profiles.Profiles);
    }

    [TestMethod]
    public async Task DeviceSearchAcceptsScannerSerialAndEitherMac()
    {
        var repository = new MemoryOperationsRepository();
        var devices = new DeviceOperationsViewModel(repository) { SearchText = "MR00613A7D755A" };

        await devices.LoadAsync();
        devices.SearchText = "00613A7D755B";
        await devices.LoadAsync();

        CollectionAssert.AreEqual(ExpectedDeviceQueries, repository.DeviceQueries.ToArray());
        Assert.HasCount(1, devices.Devices);
    }

    [TestMethod]
    public async Task ProfileRevisionRequiresAdvancedConfirmationAndIncrementedVersion()
    {
        var repository = new MemoryOperationsRepository();
        var profiles = new ProfileOperationsViewModel(repository, repository);
        await profiles.LoadAsync();
        profiles.DraftJson = await RevisionJsonAsync(2, "draft");

        Assert.IsFalse(await profiles.SaveRevisionAsync(false));
        profiles.AdvancedVisible = true;
        profiles.AdvancedConfirmed = true;
        Assert.IsTrue(await profiles.SaveRevisionAsync(false));
        Assert.AreEqual(2, profiles.SelectedProfile!.Reference.Version);
        profiles.DraftJson = await RevisionJsonAsync(4, "approved");
        Assert.IsFalse(await profiles.SaveRevisionAsync(true));
        StringAssert.Contains(profiles.EditorStatus, "increment");
    }

    [TestMethod]
    public async Task FriendlyProfileFormCreatesApprovedProfileAndMakesItAvailableToDeploy()
    {
        const string releaseId = "20260722080000-123456789abc";
        var repository = new MemoryOperationsRepository();
        var shell = new OperationsShellViewModel(repository, repository, () => false,
            profileWriter: repository, currentReleaseIdProvider: _ => Task.FromResult<string?>(releaseId));
        await shell.LoadAsync();
        Assert.IsNull(shell.Deployment.SelectedProfile, "The fixture starts with a draft-only profile.");

        shell.Profiles.NewProfileCommand.Execute(null);
        shell.Profiles.DisplayName = "MAXiEMS Edge MR1";
        Assert.AreEqual("maxiems-edge-mr1", shell.Profiles.ProfileId);
        shell.Profiles.HardwareVariant = "MR1-ARM64";
        shell.Profiles.CpuModel = "Rockchip RK3588";
        shell.Profiles.Include4G = true;
        shell.Profiles.FourGVendorId = "2c7c";
        shell.Profiles.FourGProductId = "0125";

        Assert.IsTrue(await shell.Profiles.SaveEditorAsync(true));

        var saved = shell.Profiles.SelectedProfile!;
        Assert.AreEqual("maxiems-edge-mr1", saved.Reference.ProfileId);
        Assert.AreEqual(1, saved.Reference.Version);
        Assert.AreEqual(ManufacturingProfileState.Approved, saved.State);
        Assert.AreEqual(releaseId, saved.DefaultReleaseId.Value);
        Assert.HasCount(2, saved.EthernetPorts);
        Assert.AreEqual("WAN0", saved.EthernetPorts.Single(port => port.IsSerialSource).PhysicalLabel);
        Assert.HasCount(1, saved.Modules);
        Assert.AreSame(saved, shell.Deployment.SelectedProfile);
    }

    [TestMethod]
    public async Task FriendlyRevisionFormPreservesIdentityAndIncrementsVersion()
    {
        var repository = new MemoryOperationsRepository();
        var profiles = new ProfileOperationsViewModel(repository, repository,
            currentReleaseIdProvider: _ => Task.FromResult<string?>("20260722080000-123456789abc"));
        await profiles.LoadAsync();

        profiles.NewRevisionCommand.Execute(null);
        profiles.ChangeNote = "Validated BOM update.";

        Assert.AreEqual(ProfileEditorMode.Revision, profiles.EditorMode);
        Assert.AreEqual("maxiems-edge-example", profiles.ProfileId);
        Assert.AreEqual(2, profiles.ProfileVersion);
        Assert.IsTrue(await profiles.SaveEditorAsync(false));
        Assert.AreEqual(ManufacturingProfileState.Draft, profiles.SelectedProfile!.State);
        Assert.AreEqual(2, profiles.SelectedProfile.Reference.Version);
    }

    [STATestMethod]
    public void WindowExposesKeyboardShortcutsAccessibleNavigationAndScannerEnter()
    {
        using var viewModel = new MainWindowViewModel(new NoopOrchestrator(), new MemoryWizardHistory(),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            new RecordingReportLauncher());
        var window = new MainWindow(viewModel);
        window.ApplyTemplate();
        window.RaiseEvent(new RoutedEventArgs(FrameworkElement.LoadedEvent));
        window.UpdateLayout();

        var shortcuts = window.InputBindings.OfType<KeyBinding>().ToArray();
        Assert.IsTrue(shortcuts.Any(binding => binding is { Key: Key.N, Modifiers: ModifierKeys.Control }));
        Assert.IsTrue(shortcuts.Any(binding => binding is { Key: Key.F, Modifiers: ModifierKeys.Control }));
        Assert.IsTrue(shortcuts.Any(binding => binding is { Key: Key.OemComma, Modifiers: ModifierKeys.Control }));
        var navigation = Descendants<Button>(window).Where(button => ExpectedNavigation.Contains(
            System.Windows.Automation.AutomationProperties.GetName(button), StringComparer.Ordinal)).ToArray();
        Assert.HasCount(6, navigation);
        Assert.IsTrue(navigation.All(button => !string.IsNullOrWhiteSpace(
            System.Windows.Automation.AutomationProperties.GetName(button))));
        var scanner = Descendants<TextBox>(window).Single(box =>
            System.Windows.Automation.AutomationProperties.GetName(box) == "Device serial or MAC search");
        Assert.IsTrue(scanner.InputBindings.OfType<KeyBinding>().Any(binding => binding.Key == Key.Enter));
        viewModel.Shell.SelectedLanguage = viewModel.Shell.Languages.Single(language => language.Code == "vi");
        window.UpdateLayout();
        var dashboardButton = Descendants<Button>(window).Single(button =>
            Equals(button.CommandParameter, "Dashboard"));
        Assert.AreEqual("_Tổng quan", dashboardButton.Content);
        Assert.AreEqual("Tổng quan", System.Windows.Automation.AutomationProperties.GetName(dashboardButton));
        StringAssert.Contains(viewModel.Shell.Deployment.Status, "Chọn");
        window.Close();
    }

    [STATestMethod]
    public void ProfileCreationFormCanBeActivatedWithoutReadOnlyBindingFailure()
    {
        using var viewModel = new MainWindowViewModel(new NoopOrchestrator(), new MemoryWizardHistory(),
            new RecordingTrustEvaluator(WizardTestSupport.Trust(HostTrustStatus.KnownMatch)),
            new RecordingReportLauncher());
        var window = new MainWindow(viewModel);
        window.ApplyTemplate();
        window.RaiseEvent(new RoutedEventArgs(FrameworkElement.LoadedEvent));
        viewModel.Shell.NavigateCommand.Execute("Profiles");
        viewModel.Shell.Profiles.NewProfileCommand.Execute(null);

        window.UpdateLayout();

        Assert.AreEqual(ProfileEditorMode.New, viewModel.Shell.Profiles.EditorMode);
        window.Close();
    }

    private static async Task<string> RevisionJsonAsync(int version, string state)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "fixtures", "manufacturing-profile.example.v1.json");
        var root = JsonNode.Parse(await File.ReadAllTextAsync(path))!.AsObject();
        root["profile"]!["version"] = version;
        root["profile"]!["state"] = state;
        return root.ToJsonString();
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

    private sealed class MemoryOperationsRepository : IManufacturingOperationsRepository,
        IDeploymentRunRepository, IManufacturingProfileRepository
    {
        private readonly ManufacturingProfile profile = ManufacturingProfileParser.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "manufacturing-profile.example.v1.json")));
        public List<string> DeviceQueries { get; } = [];
        public Task<ManufacturingDashboardCounts> GetDashboardAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(new ManufacturingDashboardCounts(1, 0, 3, 1, 1, 2));
        public Task<IReadOnlyList<ManufacturingProfile>> ListProfilesAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<ManufacturingProfile>>([this.profile]);
        public Task<IReadOnlyList<ManufacturingDeviceSummary>> SearchDevicesAsync(string? query, int limit = 100,
            CancellationToken cancellationToken = default)
        {
            this.DeviceQueries.Add(query ?? string.Empty);
            var serial = DeviceSerial.FromSerialSource(new("00613A7D755A"));
            return Task.FromResult<IReadOnlyList<ManufacturingDeviceSummary>>([new(Guid.NewGuid(), serial,
                DeviceLifecycleState.Registered, this.profile.Reference, ImmutableArray<DeviceEthernetPortRecord>.Empty,
                DateTimeOffset.UtcNow)]);
        }
        public Task AppendAsync(StoredDeploymentRun run, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task<IReadOnlyList<ManufacturingHistoryEntry>> SearchAsync(string? query, int limit = 100,
            CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<ManufacturingHistoryEntry>>([]);
        public Task<ManufacturingProfile> AddAsync(string canonicalJson, string createdBy, DateTimeOffset createdAt,
            CancellationToken cancellationToken = default) => Task.FromResult(ManufacturingProfileParser.Parse(canonicalJson));
        public Task<string?> FindCanonicalJsonAsync(ManufacturingProfileReference reference,
            CancellationToken cancellationToken = default) => Task.FromResult<string?>(null);
    }

    private sealed class NoopOrchestrator : IWizardDeploymentOrchestrator
    {
        public async IAsyncEnumerable<WizardProgressUpdate> RunAsync(WizardDeploymentRequest request,
            [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
        {
            await Task.CompletedTask;
            yield break;
        }
    }
}
