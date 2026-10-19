using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Preflight;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests.Preflight;

[TestClass]
[TestCategory("UbuntuPreflight")]
public sealed class UbuntuPreflightEvaluatorTests
{
    private static readonly DateTimeOffset Now = new(2026, 7, 19, 12, 0, 0, TimeSpan.Zero);

    [TestMethod]
    public void OfflineTargetDoesNotRequireExternalDns()
    {
        Assert.IsTrue(UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            DnsAvailable = false
        }, Now).IsSupported);
    }

    [TestMethod]
    [DataRow("amd64", SupportedArchitecture.Amd64)]
    [DataRow("arm64", SupportedArchitecture.Arm64)]
    public void CleanUbuntu2404ArchitecturesPass(string value, SupportedArchitecture expected)
    {
        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Architecture = value
        }, Now);

        Assert.IsTrue(result.IsSupported);
        Assert.AreEqual(expected, result.Architecture);
    }

    [TestMethod]
    public void WrongOsVersionAndArchitectureAreRejectedTogether()
    {
        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            OperatingSystemId = "debian",
            VersionId = "22.04",
            Architecture = "riscv64",
        }, Now);

        CollectionAssert.AreEquivalent(
            new[] {
                PreflightIssueCode.UnsupportedOperatingSystem,
                PreflightIssueCode.UnsupportedVersion,
                PreflightIssueCode.UnsupportedArchitecture,
            },
            result.Issues.Select(issue => issue.Code).ToArray());
    }

    [TestMethod]
    public void ClockSkewWarnsWhileResourceAndSudoFailuresStillBlock()
    {
        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            FreeDiskBytes = UbuntuPreflightEvaluator.MinimumDiskBytes - 1,
            TotalMemoryBytes = UbuntuPreflightEvaluator.MinimumMemoryBytes - 1,
            RemoteTime = Now.AddMinutes(6),
            DnsAvailable = false,
            NonInteractiveSudoAvailable = false,
        }, Now);

        Assert.IsFalse(result.IsSupported);
        Assert.AreEqual(3, result.Issues.Length);
        Assert.AreEqual(PreflightIssueCode.ClockSkew, result.Warnings.Single().Code);
        Assert.IsTrue(result.Issues.Any(issue => issue.Code == PreflightIssueCode.NonInteractiveSudoUnavailable));
    }

    [TestMethod]
    [DataRow(-6)]
    [DataRow(6)]
    public void ClockSkewAloneWarnsWithoutBlockingDeployment(int minutes)
    {
        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            RemoteTime = Now.AddMinutes(minutes)
        }, Now);

        Assert.IsTrue(result.IsSupported);
        Assert.IsEmpty(result.Issues);
        Assert.AreEqual(PreflightIssueCode.ClockSkew, result.Warnings.Single().Code);
    }

    [TestMethod]
    public void ClockSkewAtThresholdDoesNotWarn()
    {
        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            RemoteTime = Now.Add(UbuntuPreflightEvaluator.MaximumClockSkew)
        }, Now);

        Assert.IsTrue(result.IsSupported);
        Assert.IsEmpty(result.Warnings);
    }

    [TestMethod]
    public void RequiredPortConflictIsAllowedOnlyForRepeatedOwnedServices()
    {
        var listeners = ImmutableArray.Create(new PortListener(80, "0.0.0.0"), new PortListener(502, "0.0.0.0"));
        var fresh = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Listeners = listeners
        }, Now);
        var repeated = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Listeners = listeners,
            NginxActive = true,
            OpenEmsActive = true,
        }, Now);

        Assert.AreEqual(2, fresh.Issues.Count(issue => issue.Code == PreflightIssueCode.RequiredPortOccupied));
        Assert.IsTrue(repeated.IsSupported);
    }

    [TestMethod]
    public void StandardProfileIgnoresLegacyAndUnusedModbusPortsButProtectsCurrentApiPorts()
    {
        var listeners = ImmutableArray.Create(
            new PortListener(8080, "0.0.0.0"),
            new PortListener(502, "0.0.0.0"),
            new PortListener(8084, "127.0.0.1"),
            new PortListener(8085, "127.0.0.1"));

        var result = UbuntuPreflightEvaluator.Evaluate(
            Valid() with
            {
                Listeners = listeners
            }, Now, requiresModbusPort: false);

        var conflicts = result.Issues.Where(issue => issue.Code == PreflightIssueCode.RequiredPortOccupied)
            .Select(issue => issue.Subject).ToArray();
        Assert.AreEqual(3, conflicts.Length);
        Assert.IsTrue(conflicts.Contains("8080", StringComparer.Ordinal));
        Assert.IsTrue(conflicts.Contains("8084", StringComparer.Ordinal));
        Assert.IsTrue(conflicts.Contains("8085", StringComparer.Ordinal));
    }

    [TestMethod]
    public void ManagedUpdateOwnsCurrentApiAndSelectedModbusPorts()
    {
        var listeners = ImmutableArray.Create(
            new PortListener(502, "0.0.0.0"),
            new PortListener(8080, "0.0.0.0"),
            new PortListener(8084, "127.0.0.1"),
            new PortListener(8085, "0.0.0.0"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Listeners = listeners,
            OpenEmsActive = true,
        }, Now, requiresModbusPort: true);

        Assert.IsTrue(result.IsSupported);
    }

    [TestMethod]
    public void ActiveLegacyOpenEmsOwnsMigrationPorts()
    {
        var listeners = ImmutableArray.Create(
            new PortListener(502, "0.0.0.0"),
            new PortListener(8080, "0.0.0.0"),
            new PortListener(8084, "0.0.0.0"),
            new PortListener(8085, "0.0.0.0"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Listeners = listeners,
            LegacyOpenEmsActive = true,
        }, Now, requiresModbusPort: true);

        Assert.IsTrue(result.IsSupported);
    }

    [TestMethod]
    public void InactiveLegacyOpenEmsDoesNotOwnOccupiedPorts()
    {
        var listeners = ImmutableArray.Create(new PortListener(8084, "0.0.0.0"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            Listeners = listeners,
            LegacyOpenEmsEnabled = true,
        }, Now, requiresModbusPort: false);

        Assert.AreEqual(PreflightIssueCode.RequiredPortOccupied, result.Issues.Single().Code);
    }

    [TestMethod]
    public void ExistingIncompatibleIdentityIsRejected()
    {
        var accounts = ImmutableArray.Create(new LinuxAccount("openems", 1000, "/home/openems", "/bin/bash"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            ExistingAccounts = accounts
        }, Now);

        Assert.AreEqual(PreflightIssueCode.IncompatibleAccount, result.Issues.Single().Code);
    }

    [TestMethod]
    [DataRow("/var/lib/openems")]
    [DataRow("/var/lib/maxiems")]
    public void ExistingSystemIdentityMayUseAnyAvailableSystemUid(string home)
    {
        var accounts = ImmutableArray.Create(
            new LinuxAccount("maxiems-deploy", 981, "/var/lib/maxiems-deploy", "/bin/bash"),
            new LinuxAccount("openems", 982, home, "/usr/sbin/nologin"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            ExistingAccounts = accounts
        }, Now);

        Assert.IsTrue(result.IsSupported);
    }

    [TestMethod]
    public void ExistingNormalUserUidIsRejectedForManagedIdentity()
    {
        var accounts = ImmutableArray.Create(
            new LinuxAccount("maxiems-deploy", 1000, "/var/lib/maxiems-deploy", "/bin/bash"));

        var result = UbuntuPreflightEvaluator.Evaluate(Valid() with
        {
            ExistingAccounts = accounts
        }, Now);

        Assert.AreEqual(PreflightIssueCode.IncompatibleAccount, result.Issues.Single().Code);
    }

    private static UbuntuPreflightSnapshot Valid() => new(
        "ubuntu",
        "24.04",
        "amd64",
        UbuntuPreflightEvaluator.MinimumDiskBytes,
        UbuntuPreflightEvaluator.MinimumMemoryBytes,
        Now,
        true,
        true,
        [],
        false,
        false,
        false,
        false,
        []);
}
