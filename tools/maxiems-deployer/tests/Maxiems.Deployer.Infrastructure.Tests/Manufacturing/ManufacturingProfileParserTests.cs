using System.Text.Json;
using System.Text.Json.Nodes;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing;

[TestClass]
[TestCategory("ManufacturingProfile")]
public sealed class ManufacturingProfileParserTests
{
    private static readonly string FixturePath = Path.Combine(
        AppContext.BaseDirectory, "fixtures", "manufacturing-profile.example.v1.json");

    [TestMethod]
    public void SanitizedFixtureParsesAsDraftWithOptionalModulesAndComputedHash()
    {
        var profile = ManufacturingProfileParser.Parse(File.ReadAllText(FixturePath));

        Assert.AreEqual("maxiems-edge-example", profile.Reference.ProfileId);
        Assert.AreEqual(ManufacturingProfileState.Draft, profile.State);
        Assert.AreEqual(64, profile.Reference.Sha256.Length);
        Assert.AreEqual(2, profile.EthernetPorts.Length);
        Assert.AreEqual("LAN2", profile.EthernetPorts.Single(port => port.IsSerialSource).PhysicalLabel);
        Assert.AreEqual(4, profile.Modules.Length);
        Assert.IsTrue(profile.Modules.All(module => module.Mode == ModuleRequirementMode.Optional));
    }

    [TestMethod]
    public void CanonicalHashIgnoresWhitespaceAndObjectPropertyOrder()
    {
        var original = JsonNode.Parse(File.ReadAllText(FixturePath))!.AsObject();
        var reordered = new JsonObject
        {
            ["deployment"] = original["deployment"]!.DeepClone(),
            ["hardware"] = original["hardware"]!.DeepClone(),
            ["profile"] = original["profile"]!.DeepClone(),
            ["schemaVersion"] = original["schemaVersion"]!.DeepClone(),
        };

        var first = ManufacturingProfileParser.Parse(original.ToJsonString(new()
        {
            WriteIndented = true
        }));
        var second = ManufacturingProfileParser.Parse(reordered.ToJsonString());

        Assert.AreEqual(first.Reference.Sha256, second.Reference.Sha256);
    }

    [TestMethod]
    public void SchemaFileIsValidAndIdentifiesVersionOne()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "schemas", "manufacturing-profile.v1.schema.json");
        using var document = JsonDocument.Parse(File.ReadAllText(path));

        Assert.AreEqual("MAXiEMS Manufacturing Profile v1", document.RootElement.GetProperty("title").GetString());
        Assert.AreEqual(1, document.RootElement.GetProperty("properties")
            .GetProperty("schemaVersion").GetProperty("const").GetInt32());
    }

    [TestMethod]
    public void UnknownDuplicateAndUnsupportedVersionFieldsAreRejected()
    {
        AssertRejected(Mutate(root => root["unexpected"] = true), "Unknown field 'unexpected'");
        AssertRejected(File.ReadAllText(FixturePath).Replace(
            "\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"schemaVersion\": 1,",
            StringComparison.Ordinal), "Duplicate field 'schemaVersion'");
        AssertRejected(Mutate(root => root["schemaVersion"] = 2), "schemaVersion");
    }

    [TestMethod]
    public void InvalidRangesAndEthernetMappingAreRejected()
    {
        AssertRejected(Mutate(root => root["hardware"]!["memoryBytes"]!["minimum"] = 10_000_000_000L),
            "memoryBytes maximum");
        AssertRejected(Mutate(root => root["hardware"]!["ethernetPorts"]!.AsArray().RemoveAt(1)),
            "exactly two entries");
        AssertRejected(Mutate(root => root["hardware"]!["ethernetPorts"]![0]!["serialSource"] = true),
            "exactly one serial source");
        AssertRejected(Mutate(root => root["hardware"]!["ethernetPorts"]![1]!["physicalLabel"] = "lan1"),
            "Duplicate Ethernet physical label");
    }

    [TestMethod]
    public void ModuleRulesRequireUniqueIdsAndStableMatcherEvidence()
    {
        AssertRejected(Mutate(root => root["hardware"]!["modules"]![1]!["ruleId"] = "cellular-4g"),
            "Duplicate module rule id");
        AssertRejected(Mutate(root =>
        {
            var matcher = root["hardware"]!["modules"]![0]!["matchers"]![0]!.AsObject();
            matcher.Remove("vendorId");
            matcher.Remove("productId");
        }), "stable identity evidence");
        AssertRejected(Mutate(root => root["hardware"]!["modules"]![0]!["modelPattern"] = ".*"),
            "Unknown field 'modelPattern'");
    }

    [TestMethod]
    public void ExactKernelPolicyRequiresKernelVersion()
    {
        AssertRejected(Mutate(root => root["hardware"]!["operatingSystem"]!["requireExactKernel"] = true),
            "kernelVersion is required");
    }

    private static string Mutate(Action<JsonObject> mutation)
    {
        var root = JsonNode.Parse(File.ReadAllText(FixturePath))!.AsObject();
        mutation(root);
        return root.ToJsonString();
    }

    private static void AssertRejected(string json, string expectedMessage)
    {
        var exception = Assert.ThrowsExactly<ManufacturingProfileException>(() => ManufacturingProfileParser.Parse(json));
        StringAssert.Contains(exception.Message, expectedMessage);
    }
}
