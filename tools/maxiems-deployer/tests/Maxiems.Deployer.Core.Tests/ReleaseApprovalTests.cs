using Maxiems.Deployer.Core.Contracts;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Core.Tests;

[TestClass]
public sealed class ReleaseApprovalTests
{
    private const string PayloadHash = "2cc3d276c6e40fe70c7f0415ababf3fbd42701872e9e2e00761bd80958302e91";

    [TestMethod]
    public void RebuiltDeployerAcceptsTheSameCanonicalPayload()
    {
        var approved = new ReleaseId("20260722092545-2cc3d276c6e4");
        var embedded = new ReleaseId("20260722094015-2cc3d276c6e4");

        Assert.IsTrue(ReleaseApproval.MatchesVerifiedPayload(approved, embedded, PayloadHash));
    }

    [TestMethod]
    public void ChangedPayloadStillRequiresAProfileRevision()
    {
        var approved = new ReleaseId("20260722092545-2cc3d276c6e4");
        var changed = new ReleaseId("20260722100000-aaaaaaaaaaaa");

        Assert.IsFalse(ReleaseApproval.MatchesVerifiedPayload(approved, changed, new string('a', 64)));
        Assert.IsFalse(ReleaseApproval.MatchesVerifiedPayload(approved,
            new("20260722100000-2cc3d276c6e4"), new string('a', 64)));
    }
}
