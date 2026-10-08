using System.Collections.ObjectModel;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public enum ProfileEditorMode
{
    None,
    New,
    Revision,
}

public sealed class ProfileOperationsViewModel(IManufacturingOperationsRepository repository,
    IManufacturingProfileRepository? writer = null, TimeProvider? timeProvider = null,
    IAppLocalizer? localizer = null,
    Func<CancellationToken, Task<string?>>? currentReleaseIdProvider = null) : ObservableObject
{
    private const long Gibibyte = 1024L * 1024 * 1024;
    private const long Gigabyte = 1000L * 1000 * 1000;
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private ManufacturingProfile? selectedProfile;
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.LoadingProfiles");
    private bool advancedVisible;
    private bool advancedConfirmed;
    private string draftJson = string.Empty;
    private string editorStatus = string.Empty;
    private ProfileEditorMode editorMode;
    private string profileId = string.Empty;
    private bool profileIdManuallyEdited;
    private int profileVersion = 1;
    private string displayName = string.Empty;
    private string productFamily = "MAXiEMS Edge";
    private string hardwareVariant = string.Empty;
    private string changeNote = string.Empty;
    private string architecture = "arm64";
    private string cpuModel = string.Empty;
    private int minimumLogicalCores = 4;
    private int minimumMemoryGiB = 4;
    private int maximumMemoryGiB = 8;
    private string storageKind = "emmc";
    private string storageModel = string.Empty;
    private int minimumStorageGb = 60;
    private int maximumStorageGb = 70;
    private string operatingSystemVersion = "24.04";
    private string operatingSystemImage = string.Empty;
    private string currentReleaseId = string.Empty;
    private bool include4G;
    private string fourGVendorId = string.Empty;
    private string fourGProductId = string.Empty;
    private bool includeLora;
    private string loraDeviceNode = string.Empty;
    private bool includeWifiBle;
    private string wifiDriver = string.Empty;
    private string bleDriver = string.Empty;

    public event EventHandler? ProfileSaved;
    public ObservableCollection<ManufacturingProfile> Profiles { get; } = [];
    public IReadOnlyList<string> Architectures { get; } = ["arm64", "amd64"];
    public IReadOnlyList<string> StorageKinds { get; } = ["emmc", "nvme", "sata", "usb"];
    public ManufacturingProfile? SelectedProfile
    {
        get => this.selectedProfile;
        set
        {
            if (this.SetProperty(ref this.selectedProfile, value))
                this.Notify(nameof(this.HasSelectedProfile));
        }
    }
    public bool HasSelectedProfile => this.SelectedProfile is not null;
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }
    public bool AdvancedVisible
    {
        get => this.advancedVisible; set => this.SetProperty(ref this.advancedVisible, value);
    }
    public bool AdvancedConfirmed
    {
        get => this.advancedConfirmed; set => this.SetProperty(ref this.advancedConfirmed, value);
    }
    public string DraftJson
    {
        get => this.draftJson; set => this.SetProperty(ref this.draftJson, value);
    }
    public string EditorStatus
    {
        get => this.editorStatus; private set => this.SetProperty(ref this.editorStatus, value);
    }
    public ProfileEditorMode EditorMode
    {
        get => this.editorMode;
        private set
        {
            if (!this.SetProperty(ref this.editorMode, value))
                return;
            this.Notify(nameof(this.IsEditorVisible));
            this.Notify(nameof(this.EditorHeading));
            this.Notify(nameof(this.IsProfileIdEditable));
        }
    }
    public bool IsEditorVisible => this.EditorMode != ProfileEditorMode.None;
    public bool IsProfileIdEditable => this.EditorMode == ProfileEditorMode.New;
    public string EditorHeading => this.localizer.Text(this.EditorMode == ProfileEditorMode.Revision
        ? "Profiles.RevisionHeading" : "Profiles.NewHeading");
    public string ProfileId
    {
        get => this.profileId;
        set
        {
            if (this.SetProperty(ref this.profileId, value) && this.EditorMode == ProfileEditorMode.New)
            {
                this.profileIdManuallyEdited = true;
            }
        }
    }
    public int ProfileVersion
    {
        get => this.profileVersion; private set => this.SetProperty(ref this.profileVersion, value);
    }
    public string DisplayName
    {
        get => this.displayName;
        set
        {
            if (!this.SetProperty(ref this.displayName, value) || this.EditorMode != ProfileEditorMode.New
                || this.profileIdManuallyEdited)
                return;
            this.SetProperty(ref this.profileId, Slug(value), nameof(this.ProfileId));
        }
    }
    public string ProductFamily
    {
        get => this.productFamily; set => this.SetProperty(ref this.productFamily, value);
    }
    public string HardwareVariant
    {
        get => this.hardwareVariant; set => this.SetProperty(ref this.hardwareVariant, value);
    }
    public string ChangeNote
    {
        get => this.changeNote; set => this.SetProperty(ref this.changeNote, value);
    }
    public string Architecture
    {
        get => this.architecture; set => this.SetProperty(ref this.architecture, value);
    }
    public string CpuModel
    {
        get => this.cpuModel; set => this.SetProperty(ref this.cpuModel, value);
    }
    public int MinimumLogicalCores
    {
        get => this.minimumLogicalCores; set => this.SetProperty(ref this.minimumLogicalCores, value);
    }
    public int MinimumMemoryGiB
    {
        get => this.minimumMemoryGiB; set => this.SetProperty(ref this.minimumMemoryGiB, value);
    }
    public int MaximumMemoryGiB
    {
        get => this.maximumMemoryGiB; set => this.SetProperty(ref this.maximumMemoryGiB, value);
    }
    public string StorageKind
    {
        get => this.storageKind; set => this.SetProperty(ref this.storageKind, value);
    }
    public string StorageModel
    {
        get => this.storageModel; set => this.SetProperty(ref this.storageModel, value);
    }
    public int MinimumStorageGb
    {
        get => this.minimumStorageGb; set => this.SetProperty(ref this.minimumStorageGb, value);
    }
    public int MaximumStorageGb
    {
        get => this.maximumStorageGb; set => this.SetProperty(ref this.maximumStorageGb, value);
    }
    public string OperatingSystemVersion
    {
        get => this.operatingSystemVersion; set => this.SetProperty(ref this.operatingSystemVersion, value);
    }
    public string OperatingSystemImage
    {
        get => this.operatingSystemImage; set => this.SetProperty(ref this.operatingSystemImage, value);
    }
    public string CurrentReleaseId
    {
        get => this.currentReleaseId; private set => this.SetProperty(ref this.currentReleaseId, value);
    }
    public bool Include4G
    {
        get => this.include4G; set => this.SetProperty(ref this.include4G, value);
    }
    public string FourGVendorId
    {
        get => this.fourGVendorId; set => this.SetProperty(ref this.fourGVendorId, value);
    }
    public string FourGProductId
    {
        get => this.fourGProductId; set => this.SetProperty(ref this.fourGProductId, value);
    }
    public bool IncludeLora
    {
        get => this.includeLora; set => this.SetProperty(ref this.includeLora, value);
    }
    public string LoraDeviceNode
    {
        get => this.loraDeviceNode; set => this.SetProperty(ref this.loraDeviceNode, value);
    }
    public bool IncludeWifiBle
    {
        get => this.includeWifiBle; set => this.SetProperty(ref this.includeWifiBle, value);
    }
    public string WifiDriver
    {
        get => this.wifiDriver; set => this.SetProperty(ref this.wifiDriver, value);
    }
    public string BleDriver
    {
        get => this.bleDriver; set => this.SetProperty(ref this.bleDriver, value);
    }

    public RelayCommand NewProfileCommand => new(this.StartNew);
    public RelayCommand NewRevisionCommand => new(this.StartRevision);
    public RelayCommand CancelEditCommand => new(() => this.EditorMode = ProfileEditorMode.None);
    public RelayCommand SaveFormDraftCommand => new(async () => await this.SaveEditorAsync(false));
    public RelayCommand ApproveFormCommand => new(async () => await this.SaveEditorAsync(true));
    public RelayCommand SaveDraftCommand => new(async () => await this.SaveRevisionAsync(false));
    public RelayCommand ApproveRevisionCommand => new(async () => await this.SaveRevisionAsync(true));

    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        if (currentReleaseIdProvider is not null)
        {
            try
            {
                this.CurrentReleaseId = await currentReleaseIdProvider(cancellationToken) ?? string.Empty;
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                this.EditorStatus = this.localizer.Text("Status.ProfileReleaseUnavailable");
            }
        }
        try
        {
            var profiles = await repository.ListProfilesAsync(cancellationToken);
            this.Profiles.Clear();
            foreach (var profile in profiles)
                this.Profiles.Add(profile);
            this.SelectedProfile = this.Profiles.FirstOrDefault(profile => profile.State == ManufacturingProfileState.Approved)
                ?? this.Profiles.FirstOrDefault();
            this.RefreshLanguage();
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            this.Status = this.localizer.Text("Status.ProfileStoreUnavailable", exception.Message);
        }
    }

    public void RefreshLanguage()
    {
        this.Status = this.Profiles.Count == 0
            ? this.localizer.Text("Status.NoProfiles") : this.localizer.Text("Status.ProfileCount", this.Profiles.Count);
        this.Notify(nameof(this.EditorHeading));
    }

    public async Task<bool> SaveEditorAsync(bool approve, CancellationToken cancellationToken = default)
    {
        if (this.EditorMode == ProfileEditorMode.None)
            return this.Fail(this.localizer.Text("Status.ProfileChooseMode"));
        if (this.EditorMode == ProfileEditorMode.New && this.Profiles.Any(profile =>
            profile.Reference.ProfileId.Equals(this.ProfileId.Trim(), StringComparison.OrdinalIgnoreCase)))
        {
            return this.Fail(this.localizer.Text("Status.ProfileIdExists"));
        }
        try
        {
            var json = this.BuildJson(approve ? "approved" : "draft");
            return await this.SaveJsonAsync(json, approve,
                this.EditorMode == ProfileEditorMode.Revision ? this.SelectedProfile : null,
                cancellationToken);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            return this.Fail(exception.Message);
        }
    }

    public async Task<bool> SaveRevisionAsync(bool approve, CancellationToken cancellationToken = default)
    {
        if (!this.AdvancedVisible || !this.AdvancedConfirmed)
            return this.Fail(
            this.localizer.Text("Status.ProfileConfirmAdvanced"));
        return await this.SaveJsonAsync(this.DraftJson, approve, this.SelectedProfile, cancellationToken);
    }

    private async Task<bool> SaveJsonAsync(string json, bool approve, ManufacturingProfile? baseline,
        CancellationToken cancellationToken)
    {
        if (writer is null)
            return this.Fail(this.localizer.Text("Status.ProfileEditingUnavailable"));
        try
        {
            var candidate = ManufacturingProfileParser.Parse(json);
            if (baseline is { } current && (candidate.Reference.ProfileId != current.Reference.ProfileId
                || candidate.Reference.Version != current.Reference.Version + 1))
            {
                return this.Fail(this.localizer.Text("Status.ProfileRevisionRule"));
            }
            var expectedState = approve ? ManufacturingProfileState.Approved : ManufacturingProfileState.Draft;
            if (candidate.State != expectedState)
                return this.Fail(
                this.localizer.Text("Status.ProfileStateRule", expectedState));
            var createdBy = $"{Environment.UserDomainName}\\{Environment.UserName}";
            var saved = await writer.AddAsync(json, createdBy,
                (timeProvider ?? TimeProvider.System).GetUtcNow(), cancellationToken);
            this.Profiles.Insert(0, saved);
            this.SelectedProfile = saved;
            this.EditorMode = ProfileEditorMode.None;
            this.EditorStatus = this.localizer.Text("Status.ProfileSaved", saved.State, saved.Reference.Version);
            this.RefreshLanguage();
            this.ProfileSaved?.Invoke(this, EventArgs.Empty);
            return true;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            return this.Fail(exception.Message);
        }
    }

    private void StartNew()
    {
        this.EditorMode = ProfileEditorMode.New;
        this.profileIdManuallyEdited = false;
        this.SetProperty(ref this.profileId, string.Empty, nameof(this.ProfileId));
        this.ProfileVersion = 1;
        this.DisplayName = string.Empty;
        this.ProductFamily = "MAXiEMS Edge";
        this.HardwareVariant = string.Empty;
        this.ChangeNote = this.localizer.Text("Profiles.InitialChangeNote");
        this.Architecture = "arm64";
        this.CpuModel = string.Empty;
        this.MinimumLogicalCores = 4;
        this.MinimumMemoryGiB = 4;
        this.MaximumMemoryGiB = 8;
        this.StorageKind = "emmc";
        this.StorageModel = string.Empty;
        this.MinimumStorageGb = 60;
        this.MaximumStorageGb = 70;
        this.OperatingSystemVersion = "24.04";
        this.OperatingSystemImage = string.Empty;
        this.Include4G = false;
        this.IncludeLora = false;
        this.IncludeWifiBle = false;
        this.EditorStatus = string.Empty;
    }

    private void StartRevision()
    {
        if (this.SelectedProfile is not { } profile)
            return;
        this.EditorMode = ProfileEditorMode.Revision;
        this.profileIdManuallyEdited = false;
        this.SetProperty(ref this.profileId, profile.Reference.ProfileId, nameof(this.ProfileId));
        this.ProfileVersion = profile.Reference.Version + 1;
        this.DisplayName = profile.DisplayName;
        this.ProductFamily = profile.ProductFamily;
        this.HardwareVariant = profile.HardwareVariant;
        this.ChangeNote = string.Empty;
        this.Architecture = profile.Cpu.Architectures.FirstOrDefault() ?? "arm64";
        this.CpuModel = profile.Cpu.Models.FirstOrDefault() ?? string.Empty;
        this.MinimumLogicalCores = profile.Cpu.MinimumLogicalCores;
        this.MinimumMemoryGiB = ToDisplayUnit(profile.MemoryBytes.Minimum, Gibibyte);
        this.MaximumMemoryGiB = ToDisplayUnit(profile.MemoryBytes.Maximum, Gibibyte);
        var storage = profile.Storage.First();
        this.StorageKind = storage.Kind.ToString().ToLowerInvariant();
        this.StorageModel = storage.AllowedModels.FirstOrDefault() ?? string.Empty;
        this.MinimumStorageGb = ToDisplayUnit(storage.SizeBytes.Minimum, Gigabyte);
        this.MaximumStorageGb = ToDisplayUnit(storage.SizeBytes.Maximum, Gigabyte);
        this.OperatingSystemVersion = profile.OperatingSystem.Versions.FirstOrDefault() ?? "24.04";
        this.OperatingSystemImage = profile.OperatingSystem.ImageVersions.FirstOrDefault() ?? string.Empty;
        this.CurrentReleaseId = profile.DefaultReleaseId.Value;
        this.LoadModules(profile);
        this.EditorStatus = string.Empty;
    }

    private string BuildJson(string state)
    {
        if (string.IsNullOrWhiteSpace(this.CurrentReleaseId))
            throw new InvalidOperationException(
            this.localizer.Text("Status.ProfileReleaseUnavailable"));
        var modules = new JsonArray();
        if (this.Include4G)
            modules.Add(Module("cellular-4g", "cellular4G", "usb",
            ("vendorId", this.FourGVendorId), ("productId", this.FourGProductId)));
        if (this.IncludeLora)
            modules.Add(Module("lora-radio", "lora", "uart",
            ("deviceNode", this.LoraDeviceNode)));
        if (this.IncludeWifiBle)
        {
            modules.Add(Module("wifi-radio", "wifi", "integrated", ("driver", this.WifiDriver)));
            modules.Add(Module("ble-radio", "bluetoothLowEnergy", "integrated", ("driver", this.BleDriver)));
        }
        var storageModels = new JsonArray();
        if (!string.IsNullOrWhiteSpace(this.StorageModel))
            storageModels.Add(this.StorageModel.Trim());
        var imageVersions = new JsonArray();
        if (!string.IsNullOrWhiteSpace(this.OperatingSystemImage))
            imageVersions.Add(this.OperatingSystemImage.Trim());
        var root = new JsonObject
        {
            ["schemaVersion"] = 1,
            ["profile"] = new JsonObject
            {
                ["id"] = this.ProfileId.Trim(),
                ["version"] = this.ProfileVersion,
                ["state"] = state,
                ["displayName"] = this.DisplayName.Trim(),
                ["productFamily"] = this.ProductFamily.Trim(),
                ["hardwareVariant"] = this.HardwareVariant.Trim(),
                ["changeNote"] = this.ChangeNote.Trim(),
            },
            ["hardware"] = new JsonObject
            {
                ["cpu"] = new JsonObject
                {
                    ["architectures"] = new JsonArray(this.Architecture),
                    ["models"] = new JsonArray(this.CpuModel.Trim()),
                    ["minimumLogicalCores"] = this.MinimumLogicalCores,
                },
                ["memoryBytes"] = Range(this.MinimumMemoryGiB, this.MaximumMemoryGiB, Gibibyte),
                ["storage"] = new JsonArray(new JsonObject
                {
                    ["kind"] = this.StorageKind,
                    ["sizeBytes"] = Range(this.MinimumStorageGb, this.MaximumStorageGb, Gigabyte),
                    ["allowedModels"] = storageModels,
                }),
                ["operatingSystem"] = new JsonObject
                {
                    ["id"] = "ubuntu",
                    ["versions"] = new JsonArray(this.OperatingSystemVersion.Trim()),
                    ["architectures"] = new JsonArray(this.Architecture),
                    ["imageVersions"] = imageVersions,
                    ["requireExactKernel"] = false,
                },
                ["ethernetPorts"] = new JsonArray(
                    new JsonObject { ["physicalLabel"] = "WAN0", ["serialSource"] = true },
                    new JsonObject { ["physicalLabel"] = "WAN1", ["serialSource"] = false }),
                ["modules"] = modules,
            },
            ["deployment"] = new JsonObject
            {
                ["defaultReleaseId"] = this.CurrentReleaseId,
                ["componentProfileId"] = "standard-maxiems",
                ["configureTailscale"] = false,
                ["requireRebootAcceptance"] = true,
            },
        };
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private void LoadModules(ManufacturingProfile profile)
    {
        var cellular = profile.Modules.FirstOrDefault(module => module.Kind == ModuleKind.Cellular4G);
        this.Include4G = cellular is not null;
        this.FourGVendorId = cellular?.Matchers.FirstOrDefault()?.VendorId ?? string.Empty;
        this.FourGProductId = cellular?.Matchers.FirstOrDefault()?.ProductId ?? string.Empty;
        var lora = profile.Modules.FirstOrDefault(module => module.Kind == ModuleKind.Lora);
        this.IncludeLora = lora is not null;
        this.LoraDeviceNode = lora?.Matchers.FirstOrDefault()?.DeviceNode ?? string.Empty;
        var wifi = profile.Modules.FirstOrDefault(module => module.Kind == ModuleKind.Wifi);
        var ble = profile.Modules.FirstOrDefault(module => module.Kind == ModuleKind.BluetoothLowEnergy);
        this.IncludeWifiBle = wifi is not null || ble is not null;
        this.WifiDriver = wifi?.Matchers.FirstOrDefault()?.Driver ?? string.Empty;
        this.BleDriver = ble?.Matchers.FirstOrDefault()?.Driver ?? string.Empty;
    }

    private static JsonObject Module(string id, string kind, string bus,
        params (string Name, string Value)[] evidence)
    {
        var matcher = new JsonObject { ["bus"] = bus };
        foreach (var item in evidence.Where(item => !string.IsNullOrWhiteSpace(item.Value)))
        {
            matcher[item.Name] = item.Value.Trim();
        }
        return new()
        {
            ["ruleId"] = id,
            ["kind"] = kind,
            ["mode"] = "optional",
            ["matchers"] = new JsonArray(matcher),
        };
    }

    private static JsonObject Range(int minimum, int maximum, long unit) => new()
    {
        ["minimum"] = checked(minimum * unit),
        ["maximum"] = checked(maximum * unit),
    };
    private static int ToDisplayUnit(long value, long unit) => checked((int)Math.Ceiling((double)value / unit));
    private static string Slug(string value)
    {
        var normalized = value.Normalize(NormalizationForm.FormD);
        var characters = normalized.Where(character => CharUnicodeInfo.GetUnicodeCategory(character)
            != UnicodeCategory.NonSpacingMark).Select(character => char.ToLowerInvariant(character))
            .Select(character => char.IsAsciiLetterOrDigit(character) ? character : '-')
            .ToArray();
        var result = string.Join('-', new string(characters).Split('-', StringSplitOptions.RemoveEmptyEntries));
        return result.Length <= 64 ? result : result[..64].TrimEnd('-');
    }

    private bool Fail(string message)
    {
        this.EditorStatus = message;
        return false;
    }
}
