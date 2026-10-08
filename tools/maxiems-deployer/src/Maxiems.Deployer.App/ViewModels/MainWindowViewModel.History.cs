using System.Collections.ObjectModel;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App.ViewModels;

public sealed partial class MainWindowViewModel
{
    private readonly IWizardHistory historyStore;
    private readonly IWizardReportLauncher reportLauncher;
    private string historySearch = string.Empty;
    private HistorySort historySort;
    private DeploymentHistorySummary? selectedHistory;

    public ObservableCollection<DeploymentHistorySummary> History { get; } = [];
    public IReadOnlyList<HistorySort> HistorySortOptions { get; } = Enum.GetValues<HistorySort>();
    public string HistorySearch
    {
        get => this.historySearch;
        set
        {
            if (this.SetProperty(ref this.historySearch, value))
                this.Notify(nameof(this.FilteredHistory));
        }
    }
    public HistorySort HistorySort
    {
        get => this.historySort;
        set
        {
            if (this.SetProperty(ref this.historySort, value))
                this.Notify(nameof(this.FilteredHistory));
        }
    }
    public DeploymentHistorySummary? SelectedHistory
    {
        get => this.selectedHistory;
        set
        {
            if (this.SetProperty(ref this.selectedHistory, value))
                this.OpenHistoryReportCommand.RaiseCanExecuteChanged();
        }
    }
    public string HistoryStatus => this.History.Count == 0
        ? "No deployment history has been recorded."
        : $"{this.History.Count} deployment record(s).";
    public IReadOnlyList<DeploymentHistorySummary> FilteredHistory => this.SortHistory(this.History
        .Where(item => string.IsNullOrWhiteSpace(this.HistorySearch)
            || item.TargetDisplayName.Contains(this.HistorySearch, StringComparison.OrdinalIgnoreCase)
            || item.FinalState.ToString().Contains(this.HistorySearch, StringComparison.OrdinalIgnoreCase)));

    public async Task LoadHistoryAsync(CancellationToken cancellationToken = default)
    {
        this.History.Clear();
        foreach (var item in await this.historyStore.LoadAsync(cancellationToken))
            this.History.Add(item);
        this.Notify(nameof(this.FilteredHistory));
        this.Notify(nameof(this.HistoryStatus));
    }

    private void OpenSelectedHistory()
    {
        if (this.SelectedHistory is { } selected)
            this.reportLauncher.Open(selected.Reports.HtmlReportPath);
    }

    private DeploymentHistorySummary[] SortHistory(IEnumerable<DeploymentHistorySummary> entries) =>
        this.HistorySort switch
        {
            HistorySort.Target => entries.OrderBy(item => item.TargetDisplayName, StringComparer.OrdinalIgnoreCase)
                .ThenByDescending(item => item.StartedAt).ToArray(),
            HistorySort.Status => entries.OrderBy(item => item.FinalState)
                .ThenByDescending(item => item.StartedAt).ToArray(),
            _ => entries.OrderByDescending(item => item.StartedAt).ToArray(),
        };
}
