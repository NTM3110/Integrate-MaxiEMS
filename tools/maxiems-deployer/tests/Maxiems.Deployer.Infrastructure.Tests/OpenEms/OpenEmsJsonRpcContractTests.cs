using System.Collections.Immutable;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class OpenEmsJsonRpcContractTests
{
    private static readonly string[] CreateSequence = ["getEdgeConfig", "createComponentConfig", "getEdgeConfig"];
    private static readonly string[] UpdateSequence = ["getEdgeConfig", "updateComponentConfig", "getEdgeConfig"];
    private static readonly string[] DeleteCheckpointSequence =
        ["getEdgeConfig", "getEdgeConfig", "deleteComponentConfig", "getEdgeConfig"];
    private static readonly string[] RestoreCheckpointSequence =
        ["getEdgeConfig", "updateComponentConfig", "getEdgeConfig"];
    private static readonly string[] SecondFailureSequence = [
        "getEdgeConfig", "updateComponentConfig", "getEdgeConfig", "createComponentConfig",
        "getEdgeConfig", "updateComponentConfig", "getEdgeConfig",
    ];

    [TestMethod]
    public async Task AbsentComponentUsesExactCreateContractAndReadbackConverges()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: "{}"),
            Request(result: EdgeConfig(502)));
        var reconciler = CreateReconciler(handler);

        var result = await reconciler.ReconcileAsync(Manifest(502), CancellationToken.None);

        CollectionAssert.AreEqual(CreateSequence,
            handler.Methods.ToArray());
        Assert.AreEqual(ComponentUpsertAction.Created, result.Components[0].Action);
        var create = handler.Requests[1].RootElement;
        Assert.AreEqual("Controller.Api.ModbusTcp.ReadOnly",
            create.GetProperty("params").GetProperty("factoryPid").GetString());
        var properties = create.GetProperty("params").GetProperty("properties");
        Assert.AreEqual("id", properties[0].GetProperty("name").GetString());
        Assert.AreEqual(JsonValueKind.Number, properties[1].GetProperty("value").ValueKind);
        Assert.IsTrue(handler.Paths.All(path => path == "/jsonrpc"));
    }

    [TestMethod]
    public async Task ExistingComponentMigratesManagedPropertyAndPreservesOtherProperties()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfigWithAudit(501, "before", "operator", "*****")),
            Request(result: "{}"),
            Request(result: EdgeConfigWithAudit(502, "after", "admin: Admin", "*****")));

        var result = await CreateReconciler(handler).ReconcileAsync(Manifest(502), CancellationToken.None);

        CollectionAssert.AreEqual(UpdateSequence,
            handler.Methods.ToArray());
        Assert.AreEqual(ComponentUpsertAction.Updated, result.Components[0].Action);
        Assert.AreEqual("ctrlApiModbusTcp0",
            handler.Requests[1].RootElement.GetProperty("params").GetProperty("componentId").GetString());
        var migrated = handler.Requests[1].RootElement.GetProperty("params").GetProperty("properties")
            .EnumerateArray().ToDictionary(
                property => property.GetProperty("name").GetString()!,
                property => property.GetProperty("value"));
        Assert.AreEqual(502, migrated["port"].GetInt32());
        CollectionAssert.DoesNotContain(migrated.Keys.ToArray(), "enabled");
        CollectionAssert.DoesNotContain(migrated.Keys.ToArray(), "apiKey");
        CollectionAssert.DoesNotContain(migrated.Keys.ToArray(), "_lastChangeAt");
        CollectionAssert.DoesNotContain(migrated.Keys.ToArray(), "_lastChangeBy");
    }

    [TestMethod]
    public async Task UpdateReadbackRejectsLossOfOperatorPropertyAndRestoresCheckpoint()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfig(501)),
            Request(result: "{}"),
            Request(result: EdgeConfigWithoutEnabled(502)),
            Request(result: "{}"),
            Request(result: EdgeConfig(501)));

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(Manifest(502), CancellationToken.None));

        CollectionAssert.Contains(exception.DifferingProperties.ToArray(), "enabled");
        Assert.AreEqual(2, handler.Methods.Count(method => method == "updateComponentConfig"));
        var restored = handler.Requests[3].RootElement.GetProperty("params").GetProperty("properties")
            .EnumerateArray().ToDictionary(
                property => property.GetProperty("name").GetString()!,
                property => property.GetProperty("value"));
        Assert.AreEqual(501, restored["port"].GetInt32());
        Assert.IsTrue(restored["enabled"].GetBoolean());
    }

    [TestMethod]
    public async Task FailedMigrationRollbackIgnoresChangingFrameworkAuditMetadata()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfigWithAudit(501, "before", "operator")),
            Request(result: "{}"),
            Request(result: EdgeConfigWithAudit(503, "after-update", "admin: Admin")),
            Request(result: "{}"),
            Request(result: EdgeConfigWithAudit(501, "after-rollback", "admin: Admin")));

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(Manifest(502), CancellationToken.None));

        Assert.IsNull(exception.RollbackException);
        Assert.AreEqual(2, handler.Methods.Count(method => method == "updateComponentConfig"));
    }

    [TestMethod]
    public async Task UpdateOnlyMissingComponentIsSkippedWithoutMutationOrRollbackOwnership()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: "{\"components\":{}}"));
        var reconciler = CreateReconciler(handler);
        var manifest = new ComponentManifest(1,
        [
            new("Factory.Managed", "managed0", [new("enabled", new ComponentBooleanValue(true))], UpdateOnly: true),
        ]);

        var checkpoint = await reconciler.CaptureAsync(manifest, CancellationToken.None);
        var result = await reconciler.ReconcileAsync(manifest, CancellationToken.None);

        Assert.AreEqual(0, checkpoint.Entries.Length);
        Assert.AreEqual(ComponentUpsertAction.SkippedMissing, result.Components[0].Action);
        Assert.IsTrue(handler.Methods.All(method => method == "getEdgeConfig"));
    }

    [TestMethod]
    public async Task UpdateOnlyExistingIdWithDifferentFactoryIsRejectedBeforeMutation()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{\"managed0\":{\"factoryId\":\"Factory.Custom\",\"properties\":{\"enabled\":true}}}}"));
        var manifest = new ComponentManifest(1,
        [
            new("Factory.Managed", "managed0", [new("enabled", new ComponentBooleanValue(true))], UpdateOnly: true),
        ]);

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(manifest, CancellationToken.None));

        CollectionAssert.Contains(exception.DifferingProperties.ToArray(), "factoryPid");
        Assert.AreEqual(1, handler.Methods.Count);
        Assert.AreEqual("getEdgeConfig", handler.Methods[0]);
    }

    [TestMethod]
    public async Task MutationReadbackWaitsForEdgeConfigToConvergeWithoutRepeatingMutation()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: "{}"),
            Request(result: EdgeConfig(503)),
            Request(result: EdgeConfig(502)));
        var reconciler = new OpenEmsComponentReconciler(
            new OpenEmsJsonRpcClient(Client(handler), new("https://localhost/jsonrpc"), TimeSpan.FromSeconds(5)),
            readBackAttempts: 2,
            readBackDelay: TimeSpan.Zero);

        var result = await reconciler.ReconcileAsync(Manifest(502), CancellationToken.None);

        Assert.AreEqual(ComponentUpsertAction.Created, result.Components[0].Action);
        Assert.AreEqual(1, handler.Methods.Count(method => method == "createComponentConfig"));
        Assert.AreEqual(3, handler.Methods.Count(method => method == "getEdgeConfig"));
    }

    [TestMethod]
    public async Task MissingEdgeConfigEventUsesOneRecoveryWithoutRepeatingMutation()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: "{}"),
            Request(result: "{\"components\":{}}"),
            Request(result: EdgeConfig(502)));
        var reconciler = new OpenEmsComponentReconciler(
            new OpenEmsJsonRpcClient(Client(handler), new("https://localhost/jsonrpc"), TimeSpan.FromSeconds(5)),
            readBackAttempts: 2,
            readBackDelay: TimeSpan.Zero,
            recoveryAttempt: 1);
        var recoveryInvocations = 0;

        var result = await reconciler.ReconcileAsync(Manifest(502), _ =>
        {
            recoveryInvocations++;
            return Task.CompletedTask;
        }, CancellationToken.None);

        Assert.AreEqual(ComponentUpsertAction.Created, result.Components[0].Action);
        Assert.AreEqual(1, recoveryInvocations);
        Assert.AreEqual(1, handler.Methods.Count(method => method == "createComponentConfig"));
        Assert.AreEqual(0, handler.Methods.Count(method => method == "updateComponentConfig"));
        Assert.AreEqual(3, handler.Methods.Count(method => method == "getEdgeConfig"));
    }

    [TestMethod]
    public async Task CheckpointDeletesComponentCreatedAfterCaptureAndVerifiesAbsence()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: EdgeConfig(502)),
            Request(result: "{}"),
            Request(result: "{\"components\":{}}"));
        var reconciler = CreateReconciler(handler);

        var checkpoint = await reconciler.CaptureAsync(Manifest(502), CancellationToken.None);
        await reconciler.RestoreAsync(checkpoint, CancellationToken.None);
        await reconciler.VerifyAsync(checkpoint, CancellationToken.None);

        CollectionAssert.AreEqual(DeleteCheckpointSequence,
            handler.Methods.ToArray());
    }

    [TestMethod]
    public async Task CheckpointRestoresPriorManagedPropertiesAndVerifiesSnapshot()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfig(501)),
            Request(result: "{}"),
            Request(result: EdgeConfig(501)));
        var reconciler = CreateReconciler(handler);

        var checkpoint = await reconciler.CaptureAsync(Manifest(502), CancellationToken.None);
        await reconciler.RestoreAsync(checkpoint, CancellationToken.None);
        await reconciler.VerifyAsync(checkpoint, CancellationToken.None);

        CollectionAssert.AreEqual(RestoreCheckpointSequence,
            handler.Methods.ToArray());
        var properties = handler.Requests[1].RootElement.GetProperty("params").GetProperty("properties");
        Assert.AreEqual(501, properties[0].GetProperty("value").GetInt32());
    }

    [TestMethod]
    public async Task CheckpointRemovesManagedPropertyThatDidNotExistBeforeMigration()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfigWithoutEnabled(501)),
            Request(result: "{}"),
            Request(result: EdgeConfigWithoutEnabled(501)));
        var manifest = Manifest(502,
            new ComponentProperty("enabled", new ComponentBooleanValue(true)));
        var reconciler = CreateReconciler(handler);

        var checkpoint = await reconciler.CaptureAsync(manifest, CancellationToken.None);
        await reconciler.RestoreAsync(checkpoint, CancellationToken.None);
        await reconciler.VerifyAsync(checkpoint, CancellationToken.None);

        var properties = handler.Requests[1].RootElement.GetProperty("params").GetProperty("properties")
            .EnumerateArray().ToDictionary(
                property => property.GetProperty("name").GetString()!,
                property => property.GetProperty("value"));
        Assert.AreEqual(501, properties["port"].GetInt32());
        Assert.AreEqual(JsonValueKind.Null, properties["enabled"].ValueKind);
    }

    [TestMethod]
    public async Task CheckpointIgnoresChangingOpenEmsAuditMetadata()
    {
        var handler = new ScriptedHandler(
            Request(result: EdgeConfigWithAudit(1502, "before", "operator")),
            Request(result: "{}"),
            Request(result: EdgeConfigWithAudit(1502, "after", "admin: Admin")));
        var reconciler = CreateReconciler(handler);

        var checkpoint = await reconciler.CaptureAsync(Manifest(502), CancellationToken.None);
        await reconciler.RestoreAsync(checkpoint, CancellationToken.None);
        await reconciler.VerifyAsync(checkpoint, CancellationToken.None);

        var restoredProperties = handler.Requests[1].RootElement
            .GetProperty("params").GetProperty("properties");
        var restoredNames = restoredProperties.EnumerateArray()
            .Select(property => property.GetProperty("name").GetString()).ToArray();
        CollectionAssert.DoesNotContain(restoredNames, "_lastChangeAt");
        CollectionAssert.DoesNotContain(restoredNames, "_lastChangeBy");
        CollectionAssert.Contains(restoredNames, "port");
    }

    [TestMethod]
    public async Task PartialReadbackFailsWithoutSecondMutationOrSecretDisclosure()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(result: "{}"),
            Request(result: EdgeConfig(503)),
            Request(result: EdgeConfig(503)),
            Request(result: "{}"),
            Request(result: "{\"components\":{}}"));
        var manifest = Manifest(502,
            new ComponentProperty("apiKey", new ComponentStringValue("do-not-report")));

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(manifest, CancellationToken.None));

        Assert.AreEqual(1, handler.Methods.Count(method => method == "createComponentConfig"));
        Assert.AreEqual(0, handler.Methods.Count(method => method == "updateComponentConfig"));
        Assert.AreEqual(1, handler.Methods.Count(method => method == "deleteComponentConfig"));
        Assert.IsFalse(exception.Message.Contains("do-not-report", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task JsonRpcMutationErrorIsTypedAndNeverRetried()
    {
        var handler = new ScriptedHandler(
            Request(result: "{\"components\":{}}"),
            Request(error: "{\"code\":-32602,\"message\":\"bad config\"}"),
            Request(result: "{\"components\":{}}"),
            Request(result: "{\"components\":{}}"));

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(Manifest(502), CancellationToken.None));

        Assert.IsInstanceOfType<OpenEmsJsonRpcException>(exception.InnerException);
        Assert.AreEqual(1, handler.Methods.Count(method => method == "createComponentConfig"));
        Assert.AreEqual(0, handler.Methods.Count(method => method == "deleteComponentConfig"));
    }

    [TestMethod]
    public async Task OnlySafeReadRetriesTransientHttpFailure()
    {
        var handler = new ScriptedHandler(
            _ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable),
            Request(result: EdgeConfig(502)));

        var result = await CreateReconciler(handler).ReconcileAsync(Manifest(502), CancellationToken.None);

        Assert.AreEqual(ComponentUpsertAction.Unchanged, result.Components[0].Action);
        Assert.AreEqual(2, handler.Requests.Count);
        Assert.IsTrue(handler.Methods.All(method => method == "getEdgeConfig"));
    }

    [TestMethod]
    public async Task FailureOnSecondComponentRestoresFirstAndLeavesUnmanagedUntouched()
    {
        var before = TwoComponentEdgeConfig(firstPort: 501, includeSecond: false);
        var afterFirst = TwoComponentEdgeConfig(firstPort: 502, includeSecond: false);
        var handler = new ScriptedHandler(
            Request(result: before),
            Request(result: "{}"),
            Request(result: afterFirst),
            Request(error: "{\"code\":-32602,\"message\":\"second failed\"}"),
            Request(result: afterFirst),
            Request(result: "{}"),
            Request(result: before));
        var manifest = new ComponentManifest(1,
        [
            new("Factory.First", "managedFirst", [new("port", new ComponentNumberValue(502))]),
            new("Factory.Second", "managedSecond", [new("enabled", new ComponentBooleanValue(true))]),
        ]);

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsReconciliationException>(
            () => CreateReconciler(handler).ReconcileAsync(manifest, CancellationToken.None));

        Assert.AreEqual("managedSecond", exception.ComponentKey);
        Assert.IsNull(exception.RollbackException);
        CollectionAssert.AreEqual(SecondFailureSequence, handler.Methods.ToArray());
        var rollbackProperties = handler.Requests[^2].RootElement.GetProperty("params").GetProperty("properties");
        Assert.AreEqual(501, rollbackProperties[0].GetProperty("value").GetInt32());
        Assert.IsFalse(handler.Requests.Any(request => request.RootElement.ToString().Contains("unmanagedSecret",
            StringComparison.Ordinal)));
    }

    [TestMethod]
    public void ConsoleAndUnauthenticatedEndpointsAreRejected()
    {
        using var authenticated = Client(new ScriptedHandler());
        Assert.ThrowsExactly<ArgumentException>(() => new OpenEmsJsonRpcClient(
            authenticated, new("https://localhost/system/console"), TimeSpan.FromSeconds(1)));
        using var unauthenticated = new HttpClient(new ScriptedHandler());
        Assert.ThrowsExactly<ArgumentException>(() => new OpenEmsJsonRpcClient(
            unauthenticated, new("https://localhost/jsonrpc"), TimeSpan.FromSeconds(1)));
    }

    private static OpenEmsComponentReconciler CreateReconciler(ScriptedHandler handler) => new(
            new OpenEmsJsonRpcClient(Client(handler), new("https://localhost/jsonrpc"), TimeSpan.FromSeconds(5)),
            readBackAttempts: 1,
            readBackDelay: TimeSpan.Zero);

    private static HttpClient Client(HttpMessageHandler handler)
    {
        var client = new HttpClient(handler);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", "fixture-only");
        return client;
    }

    private static ComponentManifest Manifest(int port, params ComponentProperty[] extra) => new(1,
        [
            new("Controller.Api.ModbusTcp.ReadOnly", "ctrlApiModbusTcp0",
                [new("port", new ComponentNumberValue(port)), .. extra]),
        ]);

    private static string EdgeConfig(int port, bool reverseProperties = false)
    {
        var properties = reverseProperties
            ? $"\"enabled\":true,\"port\":{port}"
            : $"\"port\":{port},\"enabled\":true";
        return "{\"components\":{\"ctrlApiModbusTcp0\":{\"alias\":\"Modbus\"," +
            "\"factoryId\":\"Controller.Api.ModbusTcp.ReadOnly\",\"properties\":{" +
            properties + "}}}}";
    }

    private static string EdgeConfigWithAudit(
        int port,
        string changedAt,
        string changedBy,
        string? apiKey = null) =>
        "{\"components\":{\"ctrlApiModbusTcp0\":{\"alias\":\"Modbus\"," +
        "\"factoryId\":\"Controller.Api.ModbusTcp.ReadOnly\",\"properties\":{" +
        $"\"_lastChangeAt\":\"{changedAt}\",\"_lastChangeBy\":\"{changedBy}\"," +
        $"\"port\":{port},\"enabled\":true" +
        (apiKey is null ? string.Empty : $",\"apiKey\":\"{apiKey}\"") + "}}}}";

    private static string EdgeConfigWithoutEnabled(int port) =>
        "{\"components\":{\"ctrlApiModbusTcp0\":{\"alias\":\"Modbus\"," +
        "\"factoryId\":\"Controller.Api.ModbusTcp.ReadOnly\",\"properties\":{" +
        $"\"port\":{port}" + "}}}}";

    private static string TwoComponentEdgeConfig(int firstPort, bool includeSecond)
    {
        var second = includeSecond
            ? ",\"managedSecond\":{\"factoryId\":\"Factory.Second\",\"properties\":{\"enabled\":true}}"
            : string.Empty;
        return "{\"components\":{" +
            $"\"managedFirst\":{{\"factoryId\":\"Factory.First\",\"properties\":{{\"port\":{firstPort}}}}}," +
            "\"unmanaged\":{\"factoryId\":\"Fixture.Unmanaged\",\"properties\":{\"hash\":\"unmanagedSecret\"}}" +
            second + "}}";
    }

    private static Func<JsonDocument, HttpResponseMessage> Request(string? result = null, string? error = null) => request =>
    {
        var id = request.RootElement.GetProperty("id").GetString();
        var payload = error is null
            ? $"{{\"jsonrpc\":\"2.0\",\"id\":\"{id}\",\"result\":{result}}}"
            : $"{{\"jsonrpc\":\"2.0\",\"id\":\"{id}\",\"error\":{error}}}";
        return new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json"),
        };

    };

    private sealed class ScriptedHandler(params Func<JsonDocument, HttpResponseMessage>[] responses)
        : HttpMessageHandler
    {
        private readonly Queue<Func<JsonDocument, HttpResponseMessage>> responses = new(responses);

        public List<JsonDocument> Requests { get; } = [];
        public List<string> Methods { get; } = [];
        public List<string> Paths { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var json = await request.Content!.ReadAsStringAsync(cancellationToken);
            var document = JsonDocument.Parse(json);
            this.Requests.Add(document);
            this.Methods.Add(document.RootElement.GetProperty("method").GetString()!);
            this.Paths.Add(request.RequestUri!.AbsolutePath);
            return this.responses.Dequeue()(document);
        }
    }
}
