using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public sealed class DashboardOperationsViewModel(IManufacturingOperationsRepository repository,
    IAppLocalizer? localizer = null) : ObservableObject
{
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private ManufacturingDashboardCounts counts = new(0, 0, 0, 0, 0, 0);
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.LoadingDashboard");
    public ManufacturingDashboardCounts Counts
    {
        get => this.counts; private set => this.SetProperty(ref this.counts, value);
    }
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }

    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            this.Counts = await repository.GetDashboardAsync(cancellationToken).ConfigureAwait(false);
            this.RefreshLanguage();
        }
        catch (Exception exception)
        {
            this.Status = this.localizer.Text("Status.StoreUnavailable", exception.Message);
        }
    }

    public void RefreshLanguage() => this.Status = this.Counts.Devices == 0
        ? this.localizer.Text("Status.NoDevices") : this.localizer.Text("Status.DeviceCount", this.Counts.Devices);
}
