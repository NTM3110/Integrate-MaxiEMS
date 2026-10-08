using System.Globalization;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Deployment;

namespace Maxiems.Deployer.Cli;

public sealed record CliOptions(HostEndpoint Endpoint, string Username, string Payload,
    string Identity, string? Manifest, bool RequireReboot, DeploymentTargetProfile TargetProfile,
    bool KeepFailedReleaseForInspection)
{
    public const string Usage = "maxiems-deployer deploy --host <host> [--port 22] --user <bootstrap-user> --payload <payload.zip> --identity <private-key> [--manifest <components.json>] [--deploy-option real|demo] [--require-reboot] [--keep-failed-release]";

    public static CliOptions Parse(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        if (args.Length == 0 || args[0] != "deploy")
        {
            throw new ArgumentException(Usage);
        }
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        var reboot = false;
        var keepFailedRelease = false;
        for (var index = 1; index < args.Length; index++)
        {
            var name = args[index];
            if (name == "-deploy-option")
            {
                name = "--deploy-option";
            }
            if (name == "--require-reboot" && !reboot)
            {
                reboot = true;
                continue;
            }
            if (name == "--keep-failed-release" && !keepFailedRelease)
            {
                keepFailedRelease = true;
                continue;
            }
            if (name is not ("--host" or "--port" or "--user" or "--payload" or "--identity" or "--manifest" or "--deploy-option" or "-deploy-option")
                || index + 1 >= args.Length || args[index + 1].StartsWith("--", StringComparison.Ordinal)
                || !values.TryAdd(name, args[++index]))
            {
                throw new ArgumentException("Unknown, duplicate or incomplete option. " + Usage);
            }
        }
        string Required(string key) => values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value)
            ? value : throw new ArgumentException("Missing required option: " + key);
        var host = Required("--host");
        if (Uri.CheckHostName(host) == UriHostNameType.Unknown)
        {
            throw new ArgumentException("Invalid host name or IP address.");
        }
        var port = 22;
        if (values.TryGetValue("--port", out var text) && (!int.TryParse(text, NumberStyles.None,
            CultureInfo.InvariantCulture, out port) || port is < 1 or > 65535))
        {
            throw new ArgumentException("SSH port must be between 1 and 65535.");
        }
        var targetProfile = values.GetValueOrDefault("--deploy-option") ?? "real";
        if (targetProfile is not ("real" or "demo"))
        {
            throw new ArgumentException("Deploy option must be real or demo.");
        }
        return new(new(host, port), Required("--user"), Path.GetFullPath(Required("--payload")),
            Path.GetFullPath(Required("--identity")), values.TryGetValue("--manifest", out var manifest)
                ? Path.GetFullPath(manifest) : null, reboot,
            targetProfile == "demo" ? DeploymentTargetProfile.Demo : DeploymentTargetProfile.Real,
            keepFailedRelease);
    }

    public static int ExitCode(DeploymentFinalState state) => state switch
    {
        DeploymentFinalState.Succeeded => 0,
        DeploymentFinalState.RolledBack => 3,
        DeploymentFinalState.Cancelled => 130,
        _ => 1,
    };
}
