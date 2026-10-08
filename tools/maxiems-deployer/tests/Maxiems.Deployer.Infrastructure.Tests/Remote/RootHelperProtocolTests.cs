using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Remote;

[TestClass]
[TestCategory("RootHelper")]
public sealed class RootHelperProtocolTests
{
    private static readonly string[] HelperNames =
    [
        "maxiems-bootstrap-helper",
        "maxiems-deploy-helper",
        "maxiems-deploy-ops",
        "maxiems-helper-common",
        "maxiems-edge-healthcheck",
        "maxiems-network-helper",
        "maxiems-network-ops",
        "maxiems-prerequisite-ops",
        "maxiems-runtime-config-ops",
    ];

    [TestMethod]
    public void EveryShellEntrypointUsesStrictModeAndContainsNoDynamicShell()
    {
        foreach (var name in HelperNames)
        {
            var text = ReadHelper(name);
            StringAssert.StartsWith(text, "#!/usr/bin/env bash\nset -euo pipefail\n");
            Assert.IsFalse(text.Contains("eval ", StringComparison.Ordinal), name);
            Assert.IsFalse(text.Contains("bash -c", StringComparison.Ordinal), name);
            Assert.IsFalse(text.Contains("sh -c", StringComparison.Ordinal), name);
        }
    }

    [TestMethod]
    public void PrerequisitesUseVerifiedLocalPackagesWithoutOnlineDownloads()
    {
        var script = ReadHelper("maxiems-prerequisite-ops");
        StringAssert.Contains(script, "file:");
        StringAssert.Contains(script, "Dir::Etc::sourceparts=-");
        StringAssert.Contains(script, "--no-upgrade");
        StringAssert.Contains(script, "DPkg::Lock::Timeout=120");
        StringAssert.Contains(script, "systemctl enable --now postgresql.service");
        Assert.IsFalse(script.Contains("apt-get update", StringComparison.Ordinal));
        Assert.IsFalse(script.Contains("/usr/bin/curl --", StringComparison.Ordinal));
        Assert.IsFalse(script.Contains("npm install", StringComparison.Ordinal));
        Assert.IsFalse(script.Contains("ip_forward", StringComparison.Ordinal));
    }

    [TestMethod]
    public void PublicHelpersHaveClosedOperationSetsAndRevalidateArguments()
    {
        var deploy = ReadHelper("maxiems-deploy-helper");
        foreach (var operation in new[] {
            "install-prerequisites", "runtime-config", "bootstrap-status", "bootstrap-config", "begin", "stage",
            "quiesce-legacy", "current-release", "activate", "commit", "abort", "rollback", "transaction-status", "service", "nginx",
            "tailscale", "status", "diagnostics",
        })
        {
            StringAssert.Contains(deploy, operation);
        }
        StringAssert.Contains(deploy, "unknown deploy operation");
        StringAssert.Contains(deploy, "validate_release_id");
        StringAssert.Contains(deploy, "validate_upload_token");
        StringAssert.Contains(deploy, "validate_sha256");

        var network = ReadHelper("maxiems-network-helper");
        foreach (var operation in new[] { "validate", "apply", "confirm", "rollback", "status" })
        {
            StringAssert.Contains(network, operation);
        }
        StringAssert.Contains(network, "unknown network operation");
        StringAssert.Contains(network, "validate_uuid");
    }

    [TestMethod]
    public void BootstrapConfigIsHashVerifiedCreateOnlyAndRestrictedToFourFiles()
    {
        var deploy = ReadHelper("maxiems-deploy-ops");
        StringAssert.Contains(deploy, "bootstrap archive hash mismatch");
        StringAssert.Contains(deploy, "bootstrap archive entries are invalid");
        StringAssert.Contains(deploy, "bootstrap config already exists");
        StringAssert.Contains(deploy, "Core/ComponentManager.config");
        StringAssert.Contains(deploy, "Core/User.config");
        StringAssert.Contains(deploy, "Controller/Api/Rest/ReadWrite/maxiems.config");
        StringAssert.Contains(deploy, "Controller/Api/Websocket/maxiems.config");
        StringAssert.Contains(deploy, "/var/lib/maxiems/shared/config");
        Assert.IsFalse(deploy.Contains("adminPassword=\"admin\"", StringComparison.Ordinal));
    }

    [TestMethod]
    public void ManagedWebsocketMigrationIsNarrowAndRollbackProtected()
    {
        var deploy = ReadHelper("maxiems-deploy-ops");
        StringAssert.Contains(deploy, "is_legacy_managed_websocket");
        StringAssert.Contains(deploy, "id=\"ctrlApiWebsocket0\"");
        StringAssert.Contains(deploy,
            "service\\.pid=\"Controller\\.Api\\.Websocket\\.maxiems\"");
        StringAssert.Contains(deploy,
            "s/^bindAddress=\"127\\.0\\.0\\.1\"$/bindAddress=\"0.0.0.0\"/");
        StringAssert.Contains(deploy,
            "snapshot_path \"$transaction\" 'managed-websocket-config' \"$MANAGED_WEBSOCKET_CONFIG\"");
        StringAssert.Contains(deploy,
            "restore_path \"$transaction\" 'managed-websocket-config' \"$MANAGED_WEBSOCKET_CONFIG\"");
    }

    [TestMethod]
    public void SudoersGrantsOnlyTypedHelpersAndNeverGeneralRootCommands()
    {
        var sudoers = File.ReadAllText(Path.Combine(RemoteRoot(), "etc", "sudoers.d", "maxiems-deployer"));
        StringAssert.Contains(sudoers, "maxiems-deploy-helper");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper quiesce-legacy *");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper bms-setup real");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper bms-setup demo");
        StringAssert.Contains(sudoers, "maxiems-deploy-helper diagnostics");
        Assert.IsFalse(sudoers.Contains("maxiems-deploy-helper bms-setup *", StringComparison.Ordinal));
        Assert.IsFalse(sudoers.Split('\n', StringSplitOptions.TrimEntries)
            .Any(line => line is "/usr/local/libexec/maxiems-deploy-helper bms-setup, \\"));
        StringAssert.Contains(sudoers, "maxiems-network-helper");
        Assert.IsFalse(sudoers.Contains("/bin/bash", StringComparison.Ordinal));
        Assert.IsFalse(sudoers.Contains("/bin/sh", StringComparison.Ordinal));
        Assert.IsFalse(sudoers.Contains("/usr/bin/id", StringComparison.Ordinal));
        Assert.IsFalse(sudoers.Contains("ALL=(ALL) ALL", StringComparison.Ordinal));
    }

    [TestMethod]
    public void BootstrapInstallsRootOwnedNonWritablePolicyFiles()
    {
        var bootstrap = ReadHelper("maxiems-bootstrap-helper");
        StringAssert.Contains(bootstrap, "--owner=root --group=root --mode=0755");
        StringAssert.Contains(bootstrap, "--owner=root --group=root --mode=0700");
        StringAssert.Contains(bootstrap, "--owner=root --group=root --mode=0644");
        StringAssert.Contains(bootstrap, "--owner=root --group=root --mode=0440");
        StringAssert.Contains(bootstrap, "/usr/sbin/visudo -cf");
    }

    [TestMethod]
    public void PublicRuntimeHelpersUseSingleSbinLayout()
    {
        var remote = RemoteRoot();
        foreach (var name in new[] { "maxiems-network-helper", "maxiems-time-helper", "maxiems-system-helper",
            "maxiems-wifi", "maxiems-netcfg" })
        {
            Assert.IsTrue(File.Exists(Path.Combine(remote, "usr", "local", "sbin", name)), name);
            Assert.IsFalse(File.Exists(Path.Combine(remote, "usr", "local", "libexec", name)), name);
        }
        Assert.IsTrue(File.Exists(Path.Combine(remote, "usr", "local", "libexec", "maxiems-network-ops")));
        var bootstrap = ReadHelper("maxiems-bootstrap-helper");
        foreach (var name in new[] { "maxiems-network-helper", "maxiems-time-helper", "maxiems-system-helper" })
        {
            StringAssert.Contains(bootstrap, $"/usr/local/libexec/{name}");
        }
    }

    [TestMethod]
    public void ArchiveAndNetworkPayloadsAreValidatedBeforeActivation()
    {
        var deploy = ReadHelper("maxiems-deploy-ops");
        StringAssert.Contains(deploy, "archive traversal is forbidden");
        StringAssert.Contains(deploy, "archive contains a link or special file");
        StringAssert.Contains(deploy, "/usr/bin/sha256sum");
        StringAssert.Contains(deploy, "canonical payload hash mismatch");
        StringAssert.Contains(deploy, "payload build timestamp and canonical payload hash");
        Assert.IsFalse(deploy.Contains("release ID does not match archive hash", StringComparison.Ordinal));
        StringAssert.Contains(deploy, "/usr/bin/jq -e");
        StringAssert.Contains(deploy, "remote payload hash mismatch");
        StringAssert.Contains(deploy, "payload file inventory mismatch");
        var network = ReadHelper("maxiems-network-ops");
        StringAssert.Contains(network, "/usr/bin/networkctl reload");
        StringAssert.Contains(network, "require_regular_no_symlink");
        StringAssert.Contains(network, "maxiems-network-rollback-${transaction_id}");
        StringAssert.Contains(network, "/usr/bin/mkdir -- \"$ACTIVE_LOCK\"");
        StringAssert.Contains(network, "--on-active=120s");
        Assert.IsFalse(network.Contains("systemctl restart", StringComparison.Ordinal));
    }

    [TestMethod]
    public void ReleaseTransactionSnapshotsSwitchesAtomicallyAndCleansOnlyManagedState()
    {
        var deploy = ReadHelper("maxiems-deploy-ops");
        StringAssert.Contains(deploy, "MAXIEMS_DEPLOY_LOCK");
        StringAssert.Contains(deploy, ".staging-${release_id}");
        StringAssert.Contains(deploy, "/usr/bin/sync -f");
        StringAssert.Contains(deploy, "/usr/bin/mv -Tf");
        StringAssert.Contains(deploy, "previous-release");
        StringAssert.Contains(deploy, "bootstrap-was-absent");
        StringAssert.Contains(deploy, "restore_path");
        StringAssert.Contains(deploy, "restore_service");
        StringAssert.Contains(deploy, "quiesce_legacy");
        StringAssert.Contains(deploy, "systemctl disable --now openems.service");
        StringAssert.Contains(deploy, "legacy-nginx-maxicom");
        StringAssert.Contains(deploy, "legacy-nginx-maxicom.present");
        StringAssert.Contains(deploy, "restore_service \"$transaction\" 'openems.service'");
        StringAssert.Contains(deploy, "public port ${port} is not listening");
        StringAssert.Contains(deploy, "listeners=\"$(/usr/bin/ss -H -lnt)\"");
        StringAssert.Contains(deploy, "listeners_for_port \"$port\" \"$listeners\"");
        StringAssert.Contains(deploy, "has_public_ipv4_listener \"$port\" \"$port_listeners\"");
        StringAssert.Contains(deploy, "/dev/tcp/127.0.0.1/${port}");
        StringAssert.Contains(deploy, "port ${port} has no IPv4-reachable wildcard listener");
        StringAssert.Contains(deploy, ".maxiems-known-good");
        StringAssert.Contains(deploy, "/var/lib/maxiems/shared/config");
        Assert.IsFalse(deploy.Contains("rm -rf -- /var/lib/openems/data", StringComparison.Ordinal));
        Assert.IsFalse(deploy.Contains("rm -rf -- /var/log/openems", StringComparison.Ordinal));
        var healthMarker = deploy.IndexOf(".maxiems-known-good", StringComparison.Ordinal);
        var prune = deploy.IndexOf("for path in \"${MAXIEMS_RELEASES}\"/*", StringComparison.Ordinal);
        Assert.IsTrue(healthMarker >= 0 && prune > healthMarker);
    }

    [TestMethod]
    public void FailureDiagnosticsAreReadOnlyBoundedAndArgumentless()
    {
        var helper = ReadHelper("maxiems-deploy-helper");
        var deploy = ReadHelper("maxiems-deploy-ops");
        var sudoers = File.ReadAllText(Path.Combine(RemoteRoot(), "etc", "sudoers.d", "maxiems-deployer"));

        StringAssert.Contains(helper, "status|diagnostics)");
        StringAssert.Contains(helper, "cd /\nexec \"$OPS\" \"$@\"");
        StringAssert.Contains(helper, "require_arg_count 1 \"$#\"");
        StringAssert.Contains(deploy, "/usr/bin/systemctl status maxiems-edge.service --no-pager --full --lines=30");
        StringAssert.Contains(deploy, "/usr/bin/journalctl --unit=maxiems-edge.service --no-pager --output=short-iso --lines=80");
        StringAssert.Contains(deploy, "/usr/bin/ss -H -lnt");
        StringAssert.Contains(deploy, "${output:0:32768}");
        StringAssert.Contains(sudoers, "/usr/local/libexec/maxiems-deploy-helper diagnostics");
        Assert.IsFalse(deploy.Contains("journalctl --follow", StringComparison.Ordinal));
    }

    private static string ReadHelper(string name)
    {
        var directory = name == "maxiems-network-helper" ? "sbin" : "libexec";
        return File.ReadAllText(Path.Combine(RemoteRoot(), "usr", "local", directory, name))
            .Replace("\r\n", "\n", StringComparison.Ordinal);
    }

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
        throw new DirectoryNotFoundException("Unable to locate tools/maxiems-deployer/remote.");
    }
}
