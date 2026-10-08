using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class ComponentManifestParserTests
{
    [TestMethod]
    public void ValidManifestPreservesOrderAndJsonPropertyTypes()
    {
        const string json = """
			{
			  "schemaVersion": 1,
			  "components": [{
			    "factoryPid": "Controller.Api.ModbusTcp.ReadOnly",
			    "id": "ctrlApiModbusTcp0",
			    "properties": { "enabled": true, "port": 502, "component.ids": ["_sum"] }
			  }]
			}
			""";

        var manifest = ComponentManifestParser.Parse(json);

        Assert.AreEqual(1, manifest.SchemaVersion);
        Assert.AreEqual("enabled", manifest.Components[0].Properties[0].Name);
        Assert.IsInstanceOfType<ComponentBooleanValue>(manifest.Components[0].Properties[0].Value);
        Assert.IsInstanceOfType<ComponentNumberValue>(manifest.Components[0].Properties[1].Value);
        Assert.IsInstanceOfType<ComponentArrayValue>(manifest.Components[0].Properties[2].Value);
    }

    [TestMethod]
    [DataRow("{}")]
    [DataRow("{\"schemaVersion\":2,\"components\":[{}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{},\"delete\":true}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{\"port\":null}}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{\"port\":{\"integer\":502}}}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"id\":\"a\",\"properties\":{}},{\"factoryPid\":\"y\",\"id\":\"a\",\"properties\":{}}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{\"_lastChangeAt\":\"now\"}}]}")]
    [DataRow("{\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{\"service.pid\":\"x.pid\"}}]}")]
    [DataRow("{\"schemaVersion\":1,\"schemaVersion\":1,\"components\":[{\"factoryPid\":\"x\",\"properties\":{}}]}")]
    public void MalformedDuplicateUnknownAndDeleteShapesAreRejected(string json)
    {
        Assert.ThrowsExactly<ComponentManifestException>(() => ComponentManifestParser.Parse(json));
    }

    [TestMethod]
    public void NumericLookingStringIsNeverCoercedToNumber()
    {
        const string json = """
			{"schemaVersion":1,"components":[{"factoryPid":"x","properties":{"port":"502"}}]}
			""";

        var value = ComponentManifestParser.Parse(json).Components[0].Properties[0].Value;

        Assert.IsInstanceOfType<ComponentStringValue>(value);
    }

    [TestMethod]
    public void UpdateOnlyRequiresExplicitIdAndPreservesFlag()
    {
        const string valid = """
            {"schemaVersion":1,"components":[{"factoryPid":"x","id":"x0","updateOnly":true,"properties":{}}]}
            """;
        const string missingId = """
            {"schemaVersion":1,"components":[{"factoryPid":"x","updateOnly":true,"properties":{}}]}
            """;

        Assert.IsTrue(ComponentManifestParser.Parse(valid).Components[0].UpdateOnly);
        Assert.ThrowsExactly<ComponentManifestException>(() => ComponentManifestParser.Parse(missingId));
    }

    [TestMethod]
    public void DuplicateOrOverlappingFactorySelectorsAreRejected()
    {
        const string duplicateSelector = """
            {"schemaVersion":1,"components":[
              {"factoryPid":"Factory.Managed","properties":{"port":8085}},
              {"factoryPid":"Factory.Managed","properties":{"enabled":true}}
            ]}
            """;
        const string selectorAndExactId = """
            {"schemaVersion":1,"components":[
              {"factoryPid":"Factory.Managed","properties":{"port":8085}},
              {"factoryPid":"Factory.Managed","id":"managed0","properties":{"enabled":true}}
            ]}
            """;

        Assert.ThrowsExactly<ComponentManifestException>(() => ComponentManifestParser.Parse(duplicateSelector));
        Assert.ThrowsExactly<ComponentManifestException>(() => ComponentManifestParser.Parse(selectorAndExactId));
    }
}
