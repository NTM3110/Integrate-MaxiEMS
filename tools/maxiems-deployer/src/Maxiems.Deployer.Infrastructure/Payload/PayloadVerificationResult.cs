using System.Collections.Immutable;

namespace Maxiems.Deployer.Infrastructure.Payload;

public sealed record PayloadMismatch(string Path, string Reason);

public sealed record PayloadVerificationResult(
    bool IsValid,
    string? ReleaseId,
    ImmutableArray<PayloadMismatch> Mismatches,
    string? PayloadSha256 = null);
