using System.Collections.ObjectModel;
using Maxiems.Deployer.App.Services;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.App.ViewModels.Operations;

public sealed class HistoryOperationsViewModel(IDeploymentRunRepository repository,
    IAppLocalizer? localizer = null) : ObservableObject
{
    private readonly IAppLocalizer localizer = localizer ?? new AppLocalizationService();
    private string searchText = string.Empty;
    private string status = (localizer ?? new AppLocalizationService()).Text("Status.LoadingHistory");
    public ObservableCollection<ManufacturingHistoryEntry> Entries { get; } = [];
    public string SearchText
    {
        get => this.searchText; set => this.SetProperty(ref this.searchText, value);
    }
    public string Status
    {
        get => this.status; private set => this.SetProperty(ref this.status, value);
    }
    public RelayCommand SubmitSearchCommand => new(async () => await this.LoadAsync());
    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            var entries = await repository.SearchAsync(this.SearchText, cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            this.Entries.Clear();
            foreach (var entry in entries)
                this.Entries.Add(entry);
            this.RefreshLanguage();
        }
        catch (Exception exception)
        {
            this.Status = this.localizer.Text("Status.HistoryUnavailable", exception.Message);
        }
    }

    public void RefreshLanguage() => this.Status = this.Entries.Count == 0
        ? this.localizer.Text("Status.NoRuns") : this.localizer.Text("Status.Runs", this.Entries.Count);
}
