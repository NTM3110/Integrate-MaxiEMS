using System.Windows;
using System.Windows.Controls;
using Maxiems.Deployer.App.ViewModels.Operations;

namespace Maxiems.Deployer.App.Views.Operations;

public partial class ManufacturingDeploymentView : UserControl
{
    public ManufacturingDeploymentView() => this.InitializeComponent();

    private ManufacturingDeploymentViewModel ViewModel => (ManufacturingDeploymentViewModel)this.DataContext;
    private async void EvaluateTrust_Click(object sender, RoutedEventArgs e) => await this.ViewModel.EvaluateTrustAsync();
    private async void ConfirmTrust_Click(object sender, RoutedEventArgs e) => await this.ViewModel.ConfirmTrustAsync();
    private async void ConfirmMapping_Click(object sender, RoutedEventArgs e) => await this.ViewModel.ConfirmMappingAsync();
    private async void Deploy_Click(object sender, RoutedEventArgs e) => await this.ViewModel.DeployAsync();
    private void Cancel_Click(object sender, RoutedEventArgs e) => this.ViewModel.CancelCurrentOperation();

    private async void Inspect_Click(object sender, RoutedEventArgs e)
    {
        using var bootstrap = this.BootstrapPasswordBox.SecurePassword.Copy();
        using var administrator = this.AdministratorPasswordBox.SecurePassword.Copy();
        this.BootstrapPasswordBox.Clear();
        this.AdministratorPasswordBox.Clear();
        await this.ViewModel.InspectAsync(bootstrap, administrator);
        if (this.ViewModel.HasInspection)
        {
            this.LanMappingCard.UpdateLayout();
            this.LanMappingCard.BringIntoView();
        }
    }
}
