using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public sealed class OpenEmsJsonRpcException(
    string message,
    int? code = null,
    HttpStatusCode? statusCode = null,
    Exception? innerException = null) : Exception(message, innerException)
{
    public int? Code { get; } = code;
    public HttpStatusCode? StatusCode { get; } = statusCode;
}

public sealed record OpenEmsComponentSnapshot(
    string Id,
    string FactoryPid,
    IReadOnlyDictionary<string, JsonElement> Properties);

public sealed class OpenEmsJsonRpcClient
{
    private const string AuthenticationFailureMarker = "Authentication failed";
    private const string GetEdgeConfigMethod = "getEdgeConfig";
    private const string CreateComponentMethod = "createComponentConfig";
    private const string UpdateComponentMethod = "updateComponentConfig";
    private const string DeleteComponentMethod = "deleteComponentConfig";

    private readonly HttpClient httpClient;
    private readonly Uri endpoint;

    public OpenEmsJsonRpcClient(HttpClient httpClient, Uri endpoint, TimeSpan timeout)
    {
        ArgumentNullException.ThrowIfNull(httpClient);
        ArgumentNullException.ThrowIfNull(endpoint);
        if (!endpoint.IsAbsoluteUri || endpoint.AbsolutePath.Contains("/system/console", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("A dedicated absolute JSON-RPC endpoint is required.", nameof(endpoint));
        }
        if (!endpoint.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
            && !(endpoint.Scheme.Equals(Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) && endpoint.IsLoopback))
        {
            throw new ArgumentException("JSON-RPC must use HTTPS or an HTTP loopback tunnel.", nameof(endpoint));
        }
        if (httpClient.DefaultRequestHeaders.Authorization is null)
        {
            throw new ArgumentException("The JSON-RPC client must have an Authorization header.", nameof(httpClient));
        }
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(timeout, TimeSpan.Zero);
        this.httpClient = httpClient;
        this.httpClient.Timeout = timeout;
        this.endpoint = endpoint;
    }

    public async Task<IReadOnlyDictionary<string, OpenEmsComponentSnapshot>> GetEdgeConfigAsync(
        CancellationToken cancellationToken = default)
    {
        var result = await SendAsync(GetEdgeConfigMethod, new JsonObject(), true, cancellationToken)
            .ConfigureAwait(false);
        if (!result.TryGetProperty("components", out var components) || components.ValueKind != JsonValueKind.Object)
        {
            throw new OpenEmsJsonRpcException("getEdgeConfig returned no components object.");
        }

        var parsed = new Dictionary<string, OpenEmsComponentSnapshot>(StringComparer.Ordinal);
        foreach (var component in components.EnumerateObject())
        {
            var value = component.Value;
            if (!value.TryGetProperty("factoryId", out var factory) || factory.ValueKind != JsonValueKind.String
                || !value.TryGetProperty("properties", out var properties) || properties.ValueKind != JsonValueKind.Object)
            {
                throw new OpenEmsJsonRpcException($"Component '{component.Name}' has an invalid shape.");
            }
            var propertyMap = properties.EnumerateObject().ToDictionary(
                property => property.Name,
                property => property.Value.Clone(),
                StringComparer.Ordinal);
            parsed.Add(component.Name, new(component.Name, factory.GetString()!, propertyMap));
        }
        return parsed;
    }

    public Task CreateComponentAsync(string factoryPid, JsonArray properties, CancellationToken cancellationToken) =>
        SendMutationAsync(CreateComponentMethod, new JsonObject
        {
            ["factoryPid"] = factoryPid,
            ["properties"] = properties,
        }, cancellationToken);

    public Task UpdateComponentAsync(string componentId, JsonArray properties, CancellationToken cancellationToken) =>
        SendMutationAsync(UpdateComponentMethod, new JsonObject
        {
            ["componentId"] = componentId,
            ["properties"] = properties,
        }, cancellationToken);

    public Task DeleteComponentAsync(string componentId, CancellationToken cancellationToken) =>
        SendMutationAsync(DeleteComponentMethod, new JsonObject
        {
            ["componentId"] = componentId,
        }, cancellationToken);

    private async Task SendMutationAsync(string method, JsonObject parameters, CancellationToken cancellationToken) =>
        _ = await SendAsync(method, parameters, false, cancellationToken).ConfigureAwait(false);

    private async Task<JsonElement> SendAsync(
        string method,
        JsonObject parameters,
        bool safeRead,
        CancellationToken cancellationToken)
    {
        var attempts = safeRead ? 2 : 1;
        for (var attempt = 1; attempt <= attempts; attempt++)
        {
            var id = Guid.NewGuid().ToString();
            var body = new JsonObject
            {
                ["jsonrpc"] = "2.0",
                ["id"] = id,
                ["method"] = method,
                ["params"] = parameters.DeepClone(),
            };
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Post, this.endpoint)
                {
                    Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
                };
                using var response = await this.httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    var responseBody = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
                    if (responseBody.Contains(AuthenticationFailureMarker, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new OpenEmsJsonRpcException(
                            "OpenEMS administrator password was rejected. For an existing device, enter its current OpenEMS administrator password.",
                            statusCode: HttpStatusCode.Forbidden);
                    }
                }
                if (safeRead && attempt < attempts && IsTransient(response.StatusCode))
                {
                    continue;
                }
                if (!response.IsSuccessStatusCode)
                {
                    throw new OpenEmsJsonRpcException(
                        $"OpenEMS request '{method}' returned HTTP {(int)response.StatusCode}.",
                        statusCode: response.StatusCode);
                }
                return await ParseResponseAsync(response, id, cancellationToken).ConfigureAwait(false);
            }
            catch (HttpRequestException) when (safeRead && attempt < attempts)
            {
                continue;
            }
            catch (HttpRequestException exception)
            {
                throw new OpenEmsJsonRpcException($"OpenEMS request '{method}' failed.", innerException: exception);
            }
        }
        throw new OpenEmsJsonRpcException($"OpenEMS read '{method}' exhausted its retry budget.");
    }

    private static async Task<JsonElement> ParseResponseAsync(
        HttpResponseMessage response,
        string expectedId,
        CancellationToken cancellationToken)
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
        var root = document.RootElement;
        if (!root.TryGetProperty("id", out var id) || id.GetString() != expectedId)
        {
            throw new OpenEmsJsonRpcException("OpenEMS returned a mismatched JSON-RPC response id.");
        }
        if (root.TryGetProperty("error", out var error))
        {
            var code = error.TryGetProperty("code", out var codeValue) ? codeValue.GetInt32() : (int?)null;
            throw new OpenEmsJsonRpcException("OpenEMS returned a JSON-RPC error.", code);
        }
        if (!root.TryGetProperty("result", out var result))
        {
            throw new OpenEmsJsonRpcException("OpenEMS returned neither result nor error.");
        }
        return result.Clone();
    }

    private static bool IsTransient(HttpStatusCode statusCode) =>
        statusCode is HttpStatusCode.RequestTimeout or HttpStatusCode.TooManyRequests
            || (int)statusCode >= 500;
}
