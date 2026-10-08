using System.Collections.ObjectModel;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public sealed class DeviceOperationsViewModel(IManufacturingOperationsRepository repository,
    IAppLocalizer? localizer = null) : ObservableObject
{
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private string searchText = string.Empty;
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.LoadingDevices");
    private ManufacturingDeviceSummary? selectedDevice;
    public ObservableCollection<ManufacturingDeviceSummary> Devices { get; } = [];
    public string SearchText
    {
        get => this.searchText; set => this.SetProperty(ref this.searchText, value);
    }
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }
    public ManufacturingDeviceSummary? SelectedDevice
    {
        get => this.selectedDevice; set => this.SetProperty(ref this.selectedDevice, value);
    }
    public RelayCommand SubmitSearchCommand => new(async () => await this.LoadAsync());

    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            var devices = await repository.SearchDevicesAsync(this.SearchText, cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            this.Devices.Clear();
            foreach (var device in devices)
                this.Devices.Add(device);
            this.RefreshLanguage();
        }
        catch (Exception exception)
        {
            this.Status = this.localizer.Text("Status.DeviceStoreUnavailable", exception.Message);
        }
    }

    public void RefreshLanguage() => this.Status = this.Devices.Count == 0
        ? this.localizer.Text("Status.NoMatches") : this.localizer.Text("Status.Matches", this.Devices.Count);
}
