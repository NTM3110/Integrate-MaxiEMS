using System.Windows;
using Maxiems.Deployer.App.Services;

namespace Maxiems.Deployer.App;

public partial class App : Application
{
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args is ["--test-driver", var inputPath])
        {
            this.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            var exitCode = await DeploymentTestDriver.RunAsync(inputPath, CancellationToken.None);
            this.Shutdown(exitCode);
            return;
        }
        if (e.Args is ["--verify-payload"])
        {
            this.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            this.Shutdown(await EmbeddedPayloadVerifier.RunAsync(CancellationToken.None));
            return;
        }
        new MainWindow().Show();
    }
}
