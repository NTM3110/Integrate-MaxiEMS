using System.Collections.Immutable;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Releases;

public sealed class ReleasePackageException(string message, Exception? inner = null) : Exception(message, inner);

public sealed record ReleaseCompatibilityContext(
    Version DeployerVersion,
    string UbuntuVersion,
    SupportedArchitecture Architecture);

public sealed record ReleaseCatalogSelection(
    LocalReleaseDescriptor Descriptor,
    string PackagePath);

public sealed record ReleasePackageImportOptions(
    long MaximumPackageBytes,
    long MaximumMetadataBytes,
    long MaximumPayloadBytes)
{
    public static ReleasePackageImportOptions Default
    {
        get;
    } = new(
        2200L * 1024 * 1024,
        1024L * 1024,
        2L * 1024 * 1024 * 1024);
}

internal sealed record ReleasePackageManifest(
    int SchemaVersion,
    ReleaseId ReleaseId,
    string Version,
    string PayloadSha256,
    ImmutableArray<string> SupportedUbuntuVersions,
    ImmutableArray<SupportedArchitecture> SupportedArchitectures,
    Version MinimumDeployerVersion);
