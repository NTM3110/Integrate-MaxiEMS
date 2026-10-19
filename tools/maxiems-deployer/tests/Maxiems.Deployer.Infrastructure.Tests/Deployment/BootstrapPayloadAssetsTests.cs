using System.IO.Compression;
using Maxiems.Deployer.Infrastructure.Deployment;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Deployment;

[TestClass]
public sealed class BootstrapPayloadAssetsTests
{
    [TestMethod]
    public void ProfileAwareBootstrapProtocolIsAccepted()
    {
        using var payload = CreatePayload(ProfileAwareSudoers);
        BootstrapPayloadAssets.ValidateCompatibility(payload.Path);
    }

    [TestMethod]
    public void PayloadWithoutKioskModeProtocolIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers.Replace(
            "/usr/local/libexec/maxiems-deploy-helper runtime-config preserve, \\\n",
            string.Empty, StringComparison.Ordinal));

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
        StringAssert.Contains(exception.Message, "kiosk mode");
    }

    [TestMethod]
    public void ArgumentlessBmsSetupSudoersIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(
            "/usr/local/libexec/maxiems-deploy-helper bms-setup, \\\n");

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "build a new full release");
        StringAssert.Contains(exception.Message, "CLI-only build is insufficient");
    }

    [TestMethod]
    public void WildcardBmsSetupSudoersIsRejected()
    {
        using var payload = CreatePayload(
            ProfileAwareSudoers + "/usr/local/libexec/maxiems-deploy-helper bms-setup *\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void ProtocolSnippetsInCommentsDoNotBypassCompatibilityGate()
    {
        using var payload = CreatePayload(ProfileAwareSudoers,
            deployHelper: """
                # require_arg_count 2 "$#"
                # [[ "$2" == 'real' || "$2" == 'demo' ]] || die 'invalid BMS deployment profile'
                """);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "build a new full release");
    }

    [TestMethod]
    public void PayloadWithoutFailureDiagnosticsProtocolIsRejected()
    {
        using var payload = CreatePayload(
            ProfileAwareSudoers.Replace(
                "/usr/local/libexec/maxiems-deploy-helper diagnostics, \\\n",
                string.Empty,
                StringComparison.Ordinal));

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "failure diagnostics");
        StringAssert.Contains(exception.Message, "new full release");
    }

    [TestMethod]
    public void PayloadWithoutChromiumKioskAssetsIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers, includeKiosk: false);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "maxiems-kiosk.service");
    }

    [TestMethod]
    public void PayloadWithRootKioskSystemServiceIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers, kioskUnit: """
            User=root
            ExecStart=/usr/bin/python3 /opt/maxiems/current/remote/usr/local/libexec/maxiems-kiosk-supervisor
            WantedBy=multi-user.target
            """);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "new full release");
    }

    [TestMethod]
    public void PayloadWithRootKioskSystemServiceGroupIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers, kioskUnit: """
            User=admin
            Group=root
            ExecStart=/usr/bin/python3 /opt/maxiems/current/remote/usr/local/libexec/maxiems-kiosk-supervisor
            WantedBy=multi-user.target
            """);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "new full release");
    }

    [TestMethod]
    public void PayloadThatBlocksSnapChromiumPrivilegeTransitionIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers, kioskUnit: """
            User=admin
            Group=admin
            ExecStart=/usr/bin/python3 /opt/maxiems/current/remote/usr/local/libexec/maxiems-kiosk-supervisor
            NoNewPrivileges=true
            WantedBy=multi-user.target
            """);

        var exception = Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));

        StringAssert.Contains(exception.Message, "new full release");
    }

    [TestMethod]
    public void CommentOnlySshGuardDoesNotSatisfyKioskProfileContract()
    {
        using var payload = CreatePayload(ProfileAwareSudoers, kioskProfile: """
            # if [ "$(/usr/bin/id -un)" = admin ] && \
            # [ -z "${SSH_CONNECTION:-}" ] && \
            # [ -z "${SSH_TTY:-}" ] && \
            # [ "$maxiems_kiosk_tty" = /dev/tty1 ] && \
            # [ -z "${WAYLAND_DISPLAY:-}" ] && \
            # [ -z "${DISPLAY:-}" ]; then
            exec /usr/bin/sway
            """);

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void ReversedDsiPanelTransformIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers,
            kioskSway: "output DSI-1 transform 90\n" +
            "input type:touch {\ncalibration_matrix 0 -1 1 1 0 0\n}\n" +
            "exec /opt/maxiems/current/helpers/maxiems-keyboard-controller\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void MissingTouchCalibrationForRotatedPanelIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers,
            kioskSway: "output DSI-1 transform 270\n" +
            "exec /opt/maxiems/current/helpers/maxiems-keyboard-controller\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void ExclusiveKeyboardThatResizesChromiumIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers,
            keyboardController: "fifo=/var/lib/maxiems/kbd/kbd-command\n" +
            "keyboard=/opt/maxiems/current/helpers/wvkbd-mobintl\n" +
            "\"$keyboard\" & keyboard_pid=$!\n" +
            "\"$keyboard\" -l dialer & keyboard_pid=$!\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    [TestMethod]
    public void WildcardKeyboardSudoersIsRejectedBeforeTargetMutation()
    {
        using var payload = CreatePayload(ProfileAwareSudoers +
            "/usr/local/sbin/maxiems-keyboard *\n");

        Assert.Throws<InvalidDataException>(
            () => BootstrapPayloadAssets.ValidateCompatibility(payload.Path));
    }

    private const string ProfileAwareSudoers =
        "/usr/local/libexec/maxiems-deploy-helper runtime-config managed, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper runtime-config preserve, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper bms-setup real, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper bms-setup demo, \\\n" +
        "/usr/local/libexec/maxiems-deploy-helper diagnostics, \\\n" +
        "/usr/local/sbin/maxiems-keyboard normal, \\\n" +
        "/usr/local/sbin/maxiems-keyboard dialer, \\\n" +
        "/usr/local/sbin/maxiems-keyboard hide\n";

    private static TemporaryPayload CreatePayload(
        string sudoers, string? deployHelper = null, bool includeKiosk = true,
        string? kioskUnit = null, string? kioskProfile = null,
        string? kioskSway = null, string? keyboardController = null)
    {
        var payload = new TemporaryPayload();
        using var archive = ZipFile.Open(payload.Path, ZipArchiveMode.Create);
        Add(archive, "remote/etc/sudoers.d/maxiems-deployer", sudoers);
        Add(archive, "remote/usr/local/libexec/maxiems-deploy-helper", deployHelper ?? """
            runtime-config)
                require_arg_count 2 "$#"
                [[ "$2" == 'managed' || "$2" == 'preserve' ]] || die 'invalid kiosk mode'
                ;;
            bms-setup)
                require_arg_count 2 "$#"
                [[ "$2" == 'real' || "$2" == 'demo' ]] || die 'invalid BMS deployment profile'
                ;;
            status|diagnostics)
                require_arg_count 1 "$#"
                ;;
            """);
        Add(archive, "remote/usr/local/libexec/maxiems-deploy-ops",
            "if [[ \"$2\" == 'managed' ]]; then\n" +
            "bms-setup) /usr/local/libexec/maxiems-bms-setup setup \"$2\" ;;\n" +
            "diagnostics)\n  diagnostics\n  ;;\n");
        Add(archive, "remote/usr/local/libexec/maxiems-bms-setup",
            "if os.geteuid() != 0 or (sys.argv[1:] not in (['setup', 'real'], ['setup', 'demo'], ['ready'])):\n");
        if (includeKiosk)
        {
            Add(archive, "remote/etc/systemd/system/maxiems-kiosk.service",
                kioskUnit ?? "User=admin\n" +
                "Group=admin\n" +
                "ExecStart=/usr/bin/python3 /opt/maxiems/current/remote/usr/local/libexec/maxiems-kiosk-supervisor\n" +
                "WantedBy=multi-user.target\n");
            Add(archive, "remote/etc/sway/maxiems-kiosk.conf",
                kioskSway ?? "output DSI-1 transform 270\n" +
                "input type:touch {\ncalibration_matrix 0 -1 1 1 0 0\n}\n" +
                "exec /opt/maxiems/current/helpers/maxiems-keyboard-controller\n");
            Add(archive, "remote/usr/local/libexec/maxiems-kiosk-supervisor",
                "URL = \"http://localhost:80\"\nWAYLAND_DISPLAY = True\nstat.S_ISSOCK(value)\n");
            Add(archive,
                "remote/etc/systemd/system/getty@tty1.service.d/90-maxiems-kiosk-autologin.conf",
                "[Service]\nExecStart=\n" +
                "ExecStart=-/sbin/agetty --autologin admin --noclear %I $TERM\n");
            Add(archive, "remote/etc/profile.d/90-maxiems-kiosk-sway.sh", kioskProfile ??
                "maxiems_kiosk_tty=$(/usr/bin/tty 2>/dev/null) || maxiems_kiosk_tty=\n" +
                "if [ \"$(/usr/bin/id -un)\" = admin ] && \\\n" +
                "    [ -z \"${SSH_CONNECTION:-}\" ] && \\\n" +
                "    [ -z \"${SSH_TTY:-}\" ] && \\\n" +
                "    [ \"$maxiems_kiosk_tty\" = /dev/tty1 ] && \\\n" +
                "    [ -z \"${WAYLAND_DISPLAY:-}\" ] && \\\n" +
                "    [ -z \"${DISPLAY:-}\" ]; then\n" +
                "    exec /usr/bin/sway\nfi\n");
            Add(archive, "helpers/maxiems-keyboard-controller",
                keyboardController ?? "fifo=/var/lib/maxiems/kbd/kbd-command\n" +
                "keyboard=/opt/maxiems/current/helpers/wvkbd-mobintl\n" +
                "\"$keyboard\" --non-exclusive & keyboard_pid=$!\n" +
                "\"$keyboard\" --non-exclusive -l dialer & keyboard_pid=$!\n");
            Add(archive, "remote/usr/local/sbin/maxiems-keyboard",
                "case \"$1\" in normal|dialer|hide) ;; esac\n");
            var keyboard = new byte[20];
            keyboard[0] = 0x7f;
            keyboard[1] = (byte)'E';
            keyboard[2] = (byte)'L';
            keyboard[3] = (byte)'F';
            keyboard[4] = 2;
            keyboard[5] = 1;
            keyboard[18] = 183;
            Add(archive, "helpers/wvkbd-mobintl", keyboard);
        }
        return payload;
    }

    private static void Add(ZipArchive archive, string path, string content)
    {
        var entry = archive.CreateEntry(path);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(content);
    }

    private static void Add(ZipArchive archive, string path, byte[] content)
    {
        var entry = archive.CreateEntry(path);
        using var stream = entry.Open();
        stream.Write(content);
    }

    private sealed class TemporaryPayload : IDisposable
    {
        public string Path
        {
            get;
        } = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(), $"maxiems-bootstrap-{Guid.NewGuid():N}.zip");

        public void Dispose() => File.Delete(Path);
    }
}
