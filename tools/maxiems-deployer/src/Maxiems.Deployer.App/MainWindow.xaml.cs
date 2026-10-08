using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Maxiems.Deployer.App.ViewModels;
using Maxiems.Deployer.Core.Contracts;

namespace Maxiems.Deployer.App;

public partial class MainWindow : Window
{
    public MainWindow() : this(AppComposition.CreateViewModel())
    {
    }

    public MainWindow(MainWindowViewModel viewModel)
    {
        this.InitializeComponent();
        this.DataContext = viewModel;
        this.ApplyLanguage(viewModel.Shell.SelectedLanguage.Code);
        viewModel.Shell.PropertyChanged += this.Shell_PropertyChanged;
        this.Loaded += async (_, _) =>
        {
            await viewModel.LoadHistoryAsync();
            await viewModel.Shell.LoadAsync();
        };
        viewModel.Shell.FocusSearchRequested += (_, _) => this.FocusOperationsSearch();
        this.Closed += (_, _) =>
        {
            viewModel.Shell.PropertyChanged -= this.Shell_PropertyChanged;
            viewModel.Dispose();
        };
    }

    private MainWindowViewModel ViewModel => (MainWindowViewModel)this.DataContext;

    private void Shell_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(this.ViewModel.Shell.SelectedLanguage))
        {
            this.ApplyLanguage(this.ViewModel.Shell.SelectedLanguage.Code);
        }
    }

    private void ApplyLanguage(string code)
    {
        var dictionaries = this.Resources.MergedDictionaries;
        var previous = dictionaries.FirstOrDefault(dictionary => dictionary.Source?.OriginalString
            .Contains("Localization/Strings.", StringComparison.OrdinalIgnoreCase) == true);
        if (previous is not null)
            dictionaries.Remove(previous);
        dictionaries.Add(new()
        {
            Source = new(
            $"/Maxiems.Deployer;component/Localization/Strings.{code}.xaml", UriKind.Relative)
        });
    }

    private async void Connect_Click(object sender, RoutedEventArgs e) => await this.ViewModel.EvaluateTrustAsync();

    private void RejectTrust_Click(object sender, RoutedEventArgs e) => this.ViewModel.RejectTrust();

    private async void ConfirmTrust_Click(object sender, RoutedEventArgs e) => await this.ViewModel.ConfirmTrustAsync();

    private async void Deploy_Click(object sender, RoutedEventArgs e)
    {
        var administratorPassword = this.AdminPasswordBox.SecurePassword.Copy();
        var bootstrapPassword = this.BootstrapPasswordBox.SecurePassword.Copy();
        var keyPassphrase = this.KeyPassphraseBox.SecurePassword.Copy();
        this.AdminPasswordBox.Clear();
        this.BootstrapPasswordBox.Clear();
        this.KeyPassphraseBox.Clear();
        await this.ViewModel.StartDeploymentAsync(administratorPassword, bootstrapPassword, keyPassphrase);
    }

    private void OpenTailscale_Click(object sender, RoutedEventArgs e)
    {
        if (this.ViewModel.TailscaleLoginUrl is { } url)
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
    }

    private void CopyTailscale_Click(object sender, RoutedEventArgs e)
    {
        if (this.ViewModel.TailscaleLoginUrl is { } url)
            Clipboard.SetText(url);
    }

    private void History_DoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (sender is ListView { SelectedItem: DeploymentHistorySummary item })
            this.ViewModel.SelectedHistory = item;
        if (this.ViewModel.OpenHistoryReportCommand.CanExecute(null))
            this.ViewModel.OpenHistoryReportCommand.Execute(null);
    }

    private void FocusOperationsSearch()
    {
        var search = Descendants<TextBox>(this).FirstOrDefault(box =>
            System.Windows.Automation.AutomationProperties.GetName(box)
                .Contains("search", StringComparison.OrdinalIgnoreCase));
        search?.Focus();
    }

    private static IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (child is T match && child is UIElement { IsVisible: true })
                yield return match;
            foreach (var descendant in Descendants<T>(child))
                yield return descendant;
        }
    }
}
