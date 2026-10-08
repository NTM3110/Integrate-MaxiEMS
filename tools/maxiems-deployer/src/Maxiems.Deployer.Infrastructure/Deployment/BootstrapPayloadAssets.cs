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
        ("remote/usr/local/sbin/maxiems-time-helper", "maxiems-time-helper"),
        ("remote/usr/local/sbin/maxiems-system-helper", "maxiems-system-helper"),
        ("remote/usr/local/libexec/maxiems-bms-setup", "maxiems-bms-setup"),
        ("remote/usr/local/sbin/maxiems-wifi", "maxiems-wifi"),
        ("remote/usr/local/sbin/maxiems-netcfg", "maxiems-netcfg"),
        ("remote/etc/sudoers.d/maxiems-deployer", "maxiems-deployer.sudoers"),
        ("remote/etc/systemd/system/maxiems-edge.service", "maxiems-edge.service"),
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
        IReadOnlyDictionary<string, ZipArchiveEntry> entries)
    {
        var sudoers = ReadText(entries, SudoersEntry);
        var deployHelper = ReadText(entries, DeployHelperEntry);
        var deployOps = ReadText(entries, DeployOpsEntry);
        var bmsSetup = ReadText(entries, BmsSetupEntry);
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

        var compatible = exactSudoers
            && diagnosticsSudoers
            && ContainsExecutableLine(deployHelper, "require_arg_count 2 \"$#\"")
            && ContainsExecutableLine(deployHelper,
                "[[ \"$2\" == 'real' || \"$2\" == 'demo' ]] || die 'invalid BMS deployment profile'")
            && ContainsExecutableLine(deployHelper, "status|diagnostics)")
            && ContainsExecutableLine(deployOps,
                "bms-setup) /usr/local/libexec/maxiems-bms-setup setup \"$2\" ;;")
            && ContainsExecutableLine(deployOps, "diagnostics)")
            && ContainsExecutableLine(bmsSetup,
                "if os.geteuid() != 0 or (sys.argv[1:] not in (['setup', 'real'], ['setup', 'demo'], ['ready'])):");

        if (!compatible)
        {
            throw new InvalidDataException(
                "Payload bootstrap protocol does not support the required deployment profile and failure diagnostics operations; build a new full release. A CLI-only build is insufficient.");
        }
    }

    private static bool ContainsExecutableLine(string source, string expected)
    {
        return source
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Any(line => !line.StartsWith('#') && line.Equals(expected, StringComparison.Ordinal));
    }

    private static string ReadText(
        IReadOnlyDictionary<string, ZipArchiveEntry> entries,
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
