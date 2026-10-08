using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class OpenEmsReadinessProbeTests
{
    [TestMethod]
    public async Task AuthenticatedGetEdgeConfigReturnsReadyState()
    {
        var probe = CreateProbe(new RepeatingHandler(request => Response(request, HttpStatusCode.OK,
            "{\"components\":{}}")));

        var result = await probe.WaitAsync(TimeSpan.FromSeconds(1), TimeSpan.FromMilliseconds(1));

        Assert.AreEqual(0, result.Count);
    }

    [TestMethod]
    public async Task AuthenticationFailureIsNotRetriedOrReportedAsTimeout()
    {
        var handler = new RepeatingHandler(request => Response(request, HttpStatusCode.Unauthorized, "{}"));
        var probe = CreateProbe(handler);

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsJsonRpcException>(() =>
            probe.WaitAsync(TimeSpan.FromSeconds(1), TimeSpan.FromMilliseconds(1)));

        Assert.AreEqual(HttpStatusCode.Unauthorized, exception.StatusCode);
        Assert.AreEqual(1, handler.CallCount);
    }

    [TestMethod]
    public async Task OpenEmsLegacyHttp500AuthenticationFailureIsReportedImmediately()
    {
        var handler = new RepeatingHandler(request => Response(request, HttpStatusCode.InternalServerError,
            "{\"message\":\"Authentication failed\"}"));
        var probe = CreateProbe(handler);

        var exception = await Assert.ThrowsExactlyAsync<OpenEmsJsonRpcException>(() =>
            probe.WaitAsync(TimeSpan.FromSeconds(1), TimeSpan.FromMilliseconds(1)));

        Assert.AreEqual(HttpStatusCode.Forbidden, exception.StatusCode);
        StringAssert.Contains(exception.Message, "current OpenEMS administrator password");
        Assert.AreEqual(1, handler.CallCount);
    }

    [TestMethod]
    public async Task ApiFailureEventuallyReturnsTypedTimeout()
    {
        var handler = new RepeatingHandler(request => Response(request, HttpStatusCode.ServiceUnavailable, "{}"));
        var probe = CreateProbe(handler);

        var exception = await Assert.ThrowsExactlyAsync<TimeoutException>(() =>
            probe.WaitAsync(TimeSpan.FromMilliseconds(25), TimeSpan.FromMilliseconds(2)));

        Assert.IsInstanceOfType<OpenEmsJsonRpcException>(exception.InnerException);
        Assert.IsTrue(handler.CallCount >= 2);
    }

    private static OpenEmsReadinessProbe CreateProbe(HttpMessageHandler handler)
    {
        var client = new HttpClient(handler);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", "fixture-only");
        return new(new OpenEmsJsonRpcClient(client, new("https://localhost/jsonrpc"), TimeSpan.FromSeconds(1)));
    }

    private static HttpResponseMessage Response(
        JsonDocument request,
        HttpStatusCode statusCode,
        string result)
    {
        var id = request.RootElement.GetProperty("id").GetString();
        return new(statusCode)
        {
            Content = new StringContent(
                $"{{\"jsonrpc\":\"2.0\",\"id\":\"{id}\",\"result\":{result}}}",
                Encoding.UTF8,
                "application/json"),
        };
    }

    private sealed class RepeatingHandler(Func<JsonDocument, HttpResponseMessage> response) : HttpMessageHandler
    {
        public int CallCount
        {
            get; private set;
        }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            this.CallCount++;
            var json = await request.Content!.ReadAsStringAsync(cancellationToken);
            using var document = JsonDocument.Parse(json);
            return response(document);
        }
    }
}
