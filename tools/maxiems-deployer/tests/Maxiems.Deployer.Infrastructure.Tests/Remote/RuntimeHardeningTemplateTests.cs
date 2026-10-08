using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Remote;

[TestClass]
[TestCategory("RuntimeHardening")]
public sealed class RuntimeHardeningTemplateTests
{
    [TestMethod]
    public void SystemdUnitUsesOpenemsAndAllowsConstrainedSudoHelpers()
    {
        var unit = Read("etc", "systemd", "system", "maxiems-edge.service");
        StringAssert.Contains(unit, "Type=simple");
        StringAssert.Contains(unit, "User=openems\nGroup=openems");
        Assert.IsFalse(unit.Contains("AmbientCapabilities=", StringComparison.Ordinal));
        Assert.IsFalse(unit.Contains("CapabilityBoundingSet=", StringComparison.Ordinal));
        StringAssert.Contains(unit, "NoNewPrivileges=false");
        Assert.IsFalse(unit.Contains("ProtectSystem=strict", StringComparison.Ordinal));
        StringAssert.Contains(unit, "PrivateTmp=true");
        StringAssert.Contains(unit,
            "ReadWritePaths=/var/lib/maxiems/shared/config /var/lib/openems /var/log/openems");
        StringAssert.Contains(unit, "-Dfelix.cm.dir=/var/lib/maxiems/shared/config");
        StringAssert.Contains(unit, "-jar /opt/maxiems/current/edge/openems.jar");
        StringAssert.Contains(unit, "-Dorg.apache.felix.http.host=0.0.0.0");
        StringAssert.Contains(unit, "-Dorg.osgi.service.http.port=8080");
        StringAssert.Contains(unit, "ExecStartPost=/usr/local/libexec/maxiems-edge-healthcheck 180");
        Assert.IsFalse(unit.Contains("Type=notify", StringComparison.Ordinal));
        Assert.IsFalse(unit.Contains("WatchdogSec", StringComparison.Ordinal));
        Assert.IsFalse(unit.Contains("User=root", StringComparison.Ordinal));
    }

    [TestMethod]
    public void NginxHasSpaCacheAndOnlyLoopbackApiUpstreams()
    {
        var nginx = Read("etc", "nginx", "sites-available", "maxiems");
        StringAssert.Contains(nginx, "root /opt/maxiems/current/ui;");
        StringAssert.Contains(nginx, "try_files $uri $uri/ /index.html;");
        StringAssert.Contains(nginx, "Cache-Control \"no-cache, no-store, must-revalidate\"");
        StringAssert.Contains(nginx, "max-age=31536000, immutable");
        StringAssert.Contains(nginx, "proxy_pass http://127.0.0.1:8084/rest/;");
        StringAssert.Contains(nginx, "proxy_pass http://127.0.0.1:8085;");
        StringAssert.Contains(nginx, "proxy_set_header Upgrade $http_upgrade;");
        Assert.IsFalse(nginx.Contains("grafana", StringComparison.OrdinalIgnoreCase));
        Assert.IsFalse(nginx.Contains("system/console", StringComparison.OrdinalIgnoreCase));
    }

    [TestMethod]
    public void CandidateValidationPrecedesLastGoodReplacement()
    {
        var installer = Read("usr", "local", "libexec", "maxiems-runtime-config-ops");
        StringAssert.Contains(installer, "readonly SOURCE='/opt/maxiems/current/remote'");
        var systemdVerify = installer.IndexOf("systemd-analyze verify", StringComparison.Ordinal);
        var nginxVerify = installer.IndexOf("nginx -t -q", StringComparison.Ordinal);
        var systemdInstall = installer.IndexOf(
            "\"$STATE/maxiems-edge-candidate.service\" \"$unit_target\"", StringComparison.Ordinal);
        var nginxInstall = installer.IndexOf(
            "\"$STATE/maxiems.nginx.candidate\" \"$nginx_target\"", StringComparison.Ordinal);
        Assert.IsTrue(systemdVerify >= 0 && systemdVerify < systemdInstall);
        Assert.IsTrue(nginxVerify >= 0 && nginxVerify < nginxInstall);
    }

    private static string Read(params string[] parts) =>
        File.ReadAllText(Path.Combine([RemoteRoot(), .. parts])).Replace("\r\n", "\n", StringComparison.Ordinal);

    private static string RemoteRoot()
    {
        var current = new DirectoryInfo(AppContext.BaseDirectory);
        while (current is not null)
        {
            var candidate = Path.Combine(current.FullName, "tools", "maxiems-deployer", "remote");
            if (Directory.Exists(candidate))
            {
                return candidate;
            }
            current = current.Parent;
        }
        throw new DirectoryNotFoundException("Unable to locate remote templates.");
    }
}
