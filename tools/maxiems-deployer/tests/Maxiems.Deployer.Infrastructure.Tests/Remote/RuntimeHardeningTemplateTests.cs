using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Remote;

[TestClass]
[TestCategory("RuntimeHardening")]
public sealed class RuntimeHardeningTemplateTests
{
    [TestMethod]
    public void SystemdUnitAllowsPort502AndConstrainedSudoHelpers()
    {
        var unit = Read("etc", "systemd", "system", "maxiems-edge.service");
        StringAssert.Contains(unit, "Type=simple");
        StringAssert.Contains(unit, "User=openems\nGroup=openems");
        StringAssert.Contains(unit, "AmbientCapabilities=CAP_NET_BIND_SERVICE");
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

    [TestMethod]
    public void PreserveKioskModeSkipsKioskOpsWhileKeepingRuntimeAndWebsocketUpdates()
    {
        var helper = Read("usr", "local", "libexec", "maxiems-deploy-helper");
        var operations = Read("usr", "local", "libexec", "maxiems-deploy-ops");
        var sudoers = Read("etc", "sudoers.d", "maxiems-deployer");
        StringAssert.Contains(helper, "[[ \"$2\" == 'managed' || \"$2\" == 'preserve' ]]");
        StringAssert.Contains(operations, "if [[ \"$2\" == 'managed' ]]; then");
        StringAssert.Contains(operations, "\t\t\t/usr/local/libexec/maxiems-kiosk-ops\n\t\tfi");
        StringAssert.Contains(operations, "/usr/local/libexec/maxiems-runtime-config-ops");
        StringAssert.Contains(operations, "migrate_managed_websocket");
        StringAssert.Contains(operations, "[[ -f \"$transaction/kiosk-managed\" ]] && kiosk_managed=true");
        StringAssert.Contains(operations, "if [[ \"$kiosk_managed\" == true ]]; then");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper runtime-config managed");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper runtime-config preserve");
        Assert.IsFalse(sudoers.Contains("maxiems-deploy-helper runtime-config,", StringComparison.Ordinal));
    }

    [TestMethod]
    public void ManagedSwayKioskUsesAnAdminSystemServiceAndMigratesTheUserUnit()
    {
        var unit = Read("etc", "systemd", "system", "maxiems-kiosk.service");
        var operations = Read("usr", "local", "libexec", "maxiems-kiosk-ops");
        var sway = Read("etc", "sway", "maxiems-kiosk.conf");
        var supervisor = Read("usr", "local", "libexec", "maxiems-kiosk-supervisor");
        var deployOperations = Read("usr", "local", "libexec", "maxiems-deploy-ops");
        var getty = Read("etc", "systemd", "system", "getty@tty1.service.d",
            "90-maxiems-kiosk-autologin.conf");
        var profile = Read("etc", "profile.d", "90-maxiems-kiosk-sway.sh");
        var keyboardHelper = Read("usr", "local", "sbin", "maxiems-keyboard");
        var sudoers = Read("etc", "sudoers.d", "maxiems-deployer");
        var keyboardController = File.ReadAllText(Path.Combine(
            RemoteRoot(), "..", "..", "..", "scripts", "maxiems-keyboard-controller"))
            .Replace("\r\n", "\n", StringComparison.Ordinal);

        StringAssert.Contains(unit, "User=admin\nGroup=admin");
        Assert.IsFalse(unit.Contains("User=root", StringComparison.Ordinal));
        Assert.IsFalse(unit.Contains("NoNewPrivileges=", StringComparison.Ordinal));
        StringAssert.Contains(unit, "Snap Chromium needs its launcher/user-namespace");
        StringAssert.Contains(unit, "PrivateTmp=true");
        StringAssert.Contains(unit, "[Install]\nWantedBy=multi-user.target");
        StringAssert.Contains(getty, "ExecStart=-/sbin/agetty --autologin admin --noclear %I $TERM");
        StringAssert.Contains(profile, "$(/usr/bin/id -un)\" = admin");
        StringAssert.Contains(profile, "SSH_CONNECTION");
        StringAssert.Contains(profile, "SSH_TTY");
        StringAssert.Contains(profile, "/dev/tty1");
        StringAssert.Contains(profile, "exec /usr/bin/sway");
        StringAssert.Contains(sway, "output DSI-1 transform 270");
        StringAssert.Contains(sway, "calibration_matrix 0 -1 1 1 0 0");
        Assert.IsFalse(sway.Contains("output DSI-1 transform 90", StringComparison.Ordinal));
        StringAssert.Contains(keyboardController,
            "\"$keyboard\" --non-exclusive & keyboard_pid=$!");
        StringAssert.Contains(keyboardController,
            "\"$keyboard\" --non-exclusive -l dialer & keyboard_pid=$!");
        StringAssert.Contains(keyboardHelper, "normal|dialer|hide");
        StringAssert.Contains(sudoers, "/usr/local/sbin/maxiems-keyboard normal");
        StringAssert.Contains(sudoers, "/usr/local/sbin/maxiems-keyboard dialer");
        StringAssert.Contains(sudoers, "/usr/local/sbin/maxiems-keyboard hide");
        StringAssert.Contains(operations, "readonly unit='/etc/systemd/system/maxiems-kiosk.service'");
        StringAssert.Contains(operations, "readonly legacy_user_unit='/etc/systemd/user/maxiems-kiosk.service'");
        StringAssert.Contains(operations, "/usr/bin/systemctl enable maxiems-kiosk.service");
        StringAssert.Contains(operations, "/usr/bin/systemctl is-enabled --quiet maxiems-kiosk.service");
        StringAssert.Contains(operations, "/usr/bin/systemctl start maxiems-kiosk.service");
        StringAssert.Contains(operations, "/usr/bin/systemctl enable getty@tty1.service");
        StringAssert.Contains(operations, "/usr/bin/systemctl is-enabled --quiet getty@tty1.service");
        StringAssert.Contains(operations, "/usr/bin/systemctl restart getty@tty1.service");
        StringAssert.Contains(operations, "Unknown tty1 getty override: $getty_dropin");
        StringAssert.Contains(operations, "Unknown tty1 Sway launcher: $profile_launcher");
        StringAssert.Contains(operations, "readonly keyboard_directory='/var/lib/maxiems/kbd'");
        StringAssert.Contains(operations,
            "/usr/bin/install --directory --owner=root --group=openems --mode=0770 \"$keyboard_directory\"");
        StringAssert.Contains(operations, "stat -c '%a' \"$keyboard_directory\"");
        StringAssert.Contains(operations, "/usr/sbin/usermod --append --groups openems admin");
        StringAssert.Contains(operations, "is_known_legacy_user_unit()");
        StringAssert.Contains(operations, "is_known_system_unit()");
        StringAssert.Contains(operations, "Unknown kiosk system unit: $unit");
        StringAssert.Contains(operations, "stop_legacy_user_service()");
        StringAssert.Contains(operations, "/usr/bin/pgrep --uid admin --full");
        StringAssert.Contains(operations, "Legacy kiosk user service did not stop");
        StringAssert.Contains(operations, "/usr/bin/rm -f -- \"$legacy_user_link\"");
        StringAssert.Contains(operations, "/usr/bin/rm -f -- \"$legacy_user_unit\"");
        StringAssert.Contains(operations, "require_safe_directory_or_absent()");
        StringAssert.Contains(operations,
            "[[ -d /home/admin && ! -L /home/admin && \"$(/usr/bin/stat -c '%U' /home/admin)\" == admin ]]");
        StringAssert.Contains(operations, "admin_passwd=\"$(/usr/bin/getent passwd admin)\"");
        StringAssert.Contains(operations, "Admin account cannot start a tty1 login shell for the BMS kiosk");
        var transactionMarker = operations.IndexOf("\nmark_kiosk_transaction\n", StringComparison.Ordinal);
        var firstMutation = operations.IndexOf("[[ ! -e \"$old_gnome\" ]] || /usr/bin/rm", StringComparison.Ordinal);
        Assert.IsTrue(transactionMarker >= 0 && transactionMarker < firstMutation);
        StringAssert.Contains(operations,
            "[[ -e \"$MAXIEMS_DEPLOY_LOCK/id\" || -L \"$MAXIEMS_DEPLOY_LOCK/id\" ]] || return 0");
        StringAssert.Contains(operations, "begun|staged|activated)");
        Assert.IsFalse(sway.Contains("maxiems-kiosk-session-start", StringComparison.Ordinal));
        StringAssert.Contains(supervisor, "discover_wayland");
        StringAssert.Contains(supervisor, "stat.S_ISSOCK");
        StringAssert.Contains(supervisor, "entry.stat(follow_symlinks=False)");
        StringAssert.Contains(supervisor, "environment[\"WAYLAND_DISPLAY\"]");
        StringAssert.Contains(supervisor,
            "(\"/snap/bin/chromium\", \"/usr/bin/chromium\", \"/usr/bin/chromium-browser\")");

        StringAssert.Contains(deployOperations, "snapshot_path \"$transaction\" 'kiosk-user-unit'");
        StringAssert.Contains(deployOperations, "snapshot_path \"$transaction\" 'kiosk-user-enabled-link'");
        StringAssert.Contains(deployOperations, "snapshot_service \"$transaction\" 'maxiems-kiosk.service'");
        StringAssert.Contains(deployOperations, "restore_service \"$transaction\" 'maxiems-kiosk.service'");
        StringAssert.Contains(deployOperations, "snapshot_path \"$transaction\" 'kiosk-getty-dropin'");
        StringAssert.Contains(deployOperations, "snapshot_path \"$transaction\" 'kiosk-profile-launcher'");
        StringAssert.Contains(deployOperations, "snapshot_service \"$transaction\" 'getty@tty1.service'");
        StringAssert.Contains(deployOperations, "restore_tty1_service()");
        StringAssert.Contains(deployOperations, "restore_tty1_service \"$transaction\"");
        StringAssert.Contains(deployOperations,
            "/usr/bin/chmod 0555 -- \"$staging/helpers/maxiems-keyboard-controller\"");
        StringAssert.Contains(deployOperations, "kiosk-kbd-was-absent");
        StringAssert.Contains(deployOperations, "kiosk-admin-openems-membership-snapshotted");
        StringAssert.Contains(deployOperations, "kiosk-admin-was-openems-member");
        StringAssert.Contains(deployOperations, "/usr/bin/gpasswd --delete admin openems");
        StringAssert.Contains(deployOperations, "tty1 is in use; restored getty settings apply after logout or reboot");
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
