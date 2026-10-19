using System.Collections.Immutable;
using System.IO.Compression;
using System.Text;

namespace Maxiems.Deployer.Infrastructure.Deployment;

public sealed record BootstrapPayloadAsset(string RemoteName, byte[] Content);

public static class BootstrapPayloadAssets
{
    private const string DeployHelperEntry = "remote/usr/local/libexec/maxiems-deploy-helper";
    private const string DeployOpsEntry = "remote/usr/local/libexec/maxiems-deploy-ops";
    private const string BmsSetupEntry = "remote/usr/local/libexec/maxiems-bms-setup";
    private const string SudoersEntry = "remote/etc/sudoers.d/maxiems-deployer";
    private const string KioskUnitEntry = "remote/etc/systemd/system/maxiems-kiosk.service";
    private const string KioskSwayEntry = "remote/etc/sway/maxiems-kiosk.conf";
    private const string KioskSupervisorEntry = "remote/usr/local/libexec/maxiems-kiosk-supervisor";
    private const string KioskGettyEntry =
        "remote/etc/systemd/system/getty@tty1.service.d/90-maxiems-kiosk-autologin.conf";
    private const string KioskProfileEntry = "remote/etc/profile.d/90-maxiems-kiosk-sway.sh";
    private const string KeyboardControllerEntry = "helpers/maxiems-keyboard-controller";
    private const string KeyboardBinaryEntry = "helpers/wvkbd-mobintl";
    private const string KeyboardHelperEntry = "remote/usr/local/sbin/maxiems-keyboard";

    private static readonly ImmutableArray<(string Entry, string RemoteName)> Required =
    [
        ("remote/usr/local/libexec/maxiems-bootstrap-helper", "maxiems-bootstrap-helper"),
        ("remote/usr/local/libexec/maxiems-deploy-helper", "maxiems-deploy-helper"),
        ("remote/usr/local/libexec/maxiems-deploy-ops", "maxiems-deploy-ops"),
        ("remote/usr/local/libexec/maxiems-helper-common", "maxiems-helper-common"),
        ("remote/usr/local/libexec/maxiems-edge-healthcheck", "maxiems-edge-healthcheck"),
        ("remote/usr/local/sbin/maxiems-network-helper", "maxiems-network-helper"),
        ("remote/usr/local/libexec/maxiems-network-ops", "maxiems-network-ops"),
        ("remote/usr/local/libexec/maxiems-prerequisite-ops", "maxiems-prerequisite-ops"),
        ("remote/usr/local/libexec/maxiems-runtime-config-ops", "maxiems-runtime-config-ops"),
        ("remote/usr/local/libexec/maxiems-kiosk-ops", "maxiems-kiosk-ops"),
        ("remote/usr/local/sbin/maxiems-time-helper", "maxiems-time-helper"),
        ("remote/usr/local/sbin/maxiems-system-helper", "maxiems-system-helper"),
        ("remote/usr/local/libexec/maxiems-bms-setup", "maxiems-bms-setup"),
        ("remote/usr/local/sbin/maxiems-wifi", "maxiems-wifi"),
        ("remote/usr/local/sbin/maxiems-netcfg", "maxiems-netcfg"),
        ("remote/usr/local/sbin/maxiems-gpio", "maxiems-gpio"),
        ("remote/usr/local/sbin/maxiems-keyboard", "maxiems-keyboard"),
        ("remote/etc/sudoers.d/maxiems-deployer", "maxiems-deployer.sudoers"),
        ("remote/etc/systemd/system/maxiems-edge.service", "maxiems-edge.service"),
        ("remote/etc/systemd/system/maxiems-gpio-mode.service", "maxiems-gpio-mode.service"),
        ("remote/etc/nginx/sites-available/maxiems", "maxiems.nginx"),
    ];

    public static ImmutableArray<BootstrapPayloadAsset> Read(string payloadPath, string publicKey)
    {
        using var archive = ZipFile.OpenRead(payloadPath);
        var entries = archive.Entries.ToDictionary(entry => entry.FullName, StringComparer.Ordinal);
        ValidateDeploymentProfileProtocol(entries);
        var result = ImmutableArray.CreateBuilder<BootstrapPayloadAsset>(Required.Length + 1);
        foreach (var required in Required)
        {
            if (!entries.TryGetValue(required.Entry, out var entry))
            {
                throw new InvalidDataException($"Payload is missing bootstrap asset '{required.Entry}'.");
            }
            using var source = entry.Open();
            using var destination = new MemoryStream();
            source.CopyTo(destination);
            result.Add(new(required.RemoteName, destination.ToArray()));
        }
        foreach (var entry in archive.Entries.Where(entry =>
            entry.FullName.StartsWith("prerequisites/ubuntu-24.04/", StringComparison.Ordinal)
            && entry.FullName.EndsWith(".deb", StringComparison.Ordinal)))
        {
            var parts = entry.FullName.Split('/');
            if (parts.Length != 4 || (parts[2] != "amd64" && parts[2] != "arm64")
                || !System.Text.RegularExpressions.Regex.IsMatch(parts[3], @"^[A-Za-z0-9][A-Za-z0-9.+_:~%-]*\.deb$"))
                throw new InvalidDataException("Invalid offline package path.");
            using var source = entry.Open();
            using var destination = new MemoryStream();
            source.CopyTo(destination);
            var content = destination.ToArray();
            var name = parts[2] + "-" + parts[3];
            result.Add(new(name, content));
            result.Add(new(name + ".sha256", System.Text.Encoding.ASCII.GetBytes(
                Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(content)).ToLowerInvariant())));
        }
        result.Add(new("maxiems-deploy.pub", System.Text.Encoding.UTF8.GetBytes(publicKey + "\n")));
        return result.ToImmutable();
    }

    public static void ValidateCompatibility(string payloadPath)
    {
        using var archive = ZipFile.OpenRead(payloadPath);
        ValidateDeploymentProfileProtocol(
            archive.Entries.ToDictionary(entry => entry.FullName, StringComparer.Ordinal));
    }

    private static void ValidateDeploymentProfileProtocol(
        Dictionary<string, ZipArchiveEntry> entries)
    {
        var sudoers = ReadText(entries, SudoersEntry);
        var deployHelper = ReadText(entries, DeployHelperEntry);
        var deployOps = ReadText(entries, DeployOpsEntry);
        var bmsSetup = ReadText(entries, BmsSetupEntry);
        var kioskUnit = ReadText(entries, KioskUnitEntry);
        var kioskSway = ReadText(entries, KioskSwayEntry);
        var kioskSupervisor = ReadText(entries, KioskSupervisorEntry);
        var kioskGetty = ReadText(entries, KioskGettyEntry);
        var kioskProfile = ReadText(entries, KioskProfileEntry);
        var keyboardController = ReadText(entries, KeyboardControllerEntry);
        var keyboardHelper = ReadText(entries, KeyboardHelperEntry);
        if (!entries.TryGetValue(KeyboardBinaryEntry, out var keyboardBinaryEntry))
            throw new InvalidDataException($"Payload is missing bootstrap asset '{KeyboardBinaryEntry}'.");
        using var keyboardBinaryStream = keyboardBinaryEntry.Open();
        Span<byte> keyboardHeader = stackalloc byte[20];
        if (keyboardBinaryStream.Read(keyboardHeader) != keyboardHeader.Length
            || !keyboardHeader[..6].SequenceEqual(new byte[] { 0x7f, (byte)'E', (byte)'L', (byte)'F', 2, 1 })
            || System.Buffers.Binary.BinaryPrimitives.ReadUInt16LittleEndian(keyboardHeader[18..20]) != 183)
            throw new InvalidDataException("Payload on-screen keyboard is not an AArch64 ELF64 binary.");
        var bmsSetupSudoCommands = sudoers
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.StartsWith(
                "/usr/local/libexec/maxiems-deploy-helper bms-setup", StringComparison.Ordinal))
            .Select(line => line.TrimEnd('\\').TrimEnd().TrimEnd(','))
            .ToArray();
        var exactSudoers = bmsSetupSudoCommands.Length == 2
            && bmsSetupSudoCommands.Contains(
                "/usr/local/libexec/maxiems-deploy-helper bms-setup real", StringComparer.Ordinal)
            && bmsSetupSudoCommands.Contains(
                "/usr/local/libexec/maxiems-deploy-helper bms-setup demo", StringComparer.Ordinal);
        var diagnosticsSudoers = sudoers
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.TrimEnd('\\').TrimEnd().TrimEnd(','))
            .Contains(
                "/usr/local/libexec/maxiems-deploy-helper diagnostics",
                StringComparer.Ordinal);
        var kioskSudoCommands = sudoers
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.StartsWith(
                "/usr/local/libexec/maxiems-deploy-helper runtime-config", StringComparison.Ordinal))
            .Select(line => line.TrimEnd('\\').TrimEnd().TrimEnd(','))
            .ToArray();
        var kioskSudoers = kioskSudoCommands.Length == 2
            && kioskSudoCommands.Contains(
                "/usr/local/libexec/maxiems-deploy-helper runtime-config managed", StringComparer.Ordinal)
            && kioskSudoCommands.Contains(
                "/usr/local/libexec/maxiems-deploy-helper runtime-config preserve", StringComparer.Ordinal);
        var keyboardSudoCommands = sudoers
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.StartsWith(
                "/usr/local/sbin/maxiems-keyboard", StringComparison.Ordinal))
            .Select(line => line.TrimEnd('\\').TrimEnd().TrimEnd(','))
            .ToArray();
        var keyboardSudoers = keyboardSudoCommands.Length == 3
            && keyboardSudoCommands.Contains(
                "/usr/local/sbin/maxiems-keyboard normal", StringComparer.Ordinal)
            && keyboardSudoCommands.Contains(
                "/usr/local/sbin/maxiems-keyboard dialer", StringComparer.Ordinal)
            && keyboardSudoCommands.Contains(
                "/usr/local/sbin/maxiems-keyboard hide", StringComparer.Ordinal);

        var compatible = exactSudoers
            && diagnosticsSudoers
            && kioskSudoers
            && ContainsExecutableLine(deployHelper, "require_arg_count 2 \"$#\"")
            && ContainsExecutableLine(deployHelper,
                "[[ \"$2\" == 'managed' || \"$2\" == 'preserve' ]] || die 'invalid kiosk mode'")
            && ContainsExecutableLine(deployHelper,
                "[[ \"$2\" == 'real' || \"$2\" == 'demo' ]] || die 'invalid BMS deployment profile'")
            && ContainsExecutableLine(deployHelper, "status|diagnostics)")
            && ContainsExecutableLine(deployOps,
                "bms-setup) /usr/local/libexec/maxiems-bms-setup setup \"$2\" ;;")
            && ContainsExecutableLine(deployOps, "if [[ \"$2\" == 'managed' ]]; then")
            && ContainsExecutableLine(deployOps, "diagnostics)")
            && ContainsExecutableLine(bmsSetup,
                "if os.geteuid() != 0 or (sys.argv[1:] not in (['setup', 'real'], ['setup', 'demo'], ['ready'])):")
            && ContainsExecutableLine(kioskUnit, "User=admin")
            && ContainsExecutableLine(kioskUnit, "Group=admin")
            && !ContainsExecutableLine(kioskUnit, "User=root")
            && !ContainsExecutableLine(kioskUnit, "Group=root")
            && !kioskUnit.Contains("NoNewPrivileges=", StringComparison.Ordinal)
            && ContainsExecutableLine(kioskUnit, "WantedBy=multi-user.target")
            && kioskUnit.Contains("maxiems-kiosk-supervisor", StringComparison.Ordinal)
            && kioskSway.Contains("maxiems-keyboard-controller", StringComparison.Ordinal)
            && ContainsExecutableLine(kioskSway, "output DSI-1 transform 270")
            && ContainsExecutableLine(kioskSway,
                "calibration_matrix 0 -1 1 1 0 0")
            && !kioskSway.Contains("maxiems-kiosk-session-start", StringComparison.Ordinal)
            && kioskSupervisor.Contains("http://localhost:80", StringComparison.Ordinal)
            && kioskSupervisor.Contains("WAYLAND_DISPLAY", StringComparison.Ordinal)
            && kioskSupervisor.Contains("stat.S_ISSOCK", StringComparison.Ordinal)
            && ContainsExecutableLine(kioskGetty, "ExecStart=")
            && ContainsExecutableLine(kioskGetty,
                "ExecStart=-/sbin/agetty --autologin admin --noclear %I $TERM")
            && ContainsExecutableLine(kioskProfile,
                "if [ \"$(/usr/bin/id -un)\" = admin ] && \\")
            && ContainsExecutableLine(kioskProfile,
                "[ -z \"${SSH_CONNECTION:-}\" ] && \\")
            && ContainsExecutableLine(kioskProfile,
                "[ -z \"${SSH_TTY:-}\" ] && \\")
            && ContainsExecutableLine(kioskProfile,
                "[ \"$maxiems_kiosk_tty\" = /dev/tty1 ] && \\")
            && ContainsExecutableLine(kioskProfile,
                "[ -z \"${WAYLAND_DISPLAY:-}\" ] && \\")
            && ContainsExecutableLine(kioskProfile,
                "[ -z \"${DISPLAY:-}\" ]; then")
            && ContainsExecutableLine(kioskProfile, "exec /usr/bin/sway");
        compatible = compatible
            && keyboardController.Contains("/var/lib/maxiems/kbd/kbd-command", StringComparison.Ordinal)
            && keyboardController.Contains("helpers/wvkbd-mobintl", StringComparison.Ordinal)
            && ContainsExecutableLine(keyboardController,
                "\"$keyboard\" --non-exclusive & keyboard_pid=$!")
            && ContainsExecutableLine(keyboardController,
                "\"$keyboard\" --non-exclusive -l dialer & keyboard_pid=$!")
            && keyboardHelper.Contains("normal|dialer|hide", StringComparison.Ordinal)
            && keyboardSudoers;

        if (!compatible)
        {
            throw new InvalidDataException(
                "Payload bootstrap protocol does not support the required deployment profile, kiosk mode, and failure diagnostics; build a new full release. A CLI-only build is insufficient.");
        }
    }

    private static bool ContainsExecutableLine(string source, string expected)
    {
        return source
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Any(line => !line.StartsWith('#') && line.Equals(expected, StringComparison.Ordinal));
    }

    private static string ReadText(
        Dictionary<string, ZipArchiveEntry> entries,
        string path)
    {
        if (!entries.TryGetValue(path, out var entry))
        {
            throw new InvalidDataException($"Payload is missing bootstrap asset '{path}'.");
        }
        using var reader = new StreamReader(entry.Open(), Encoding.UTF8, true);
        return reader.ReadToEnd();
    }
}
