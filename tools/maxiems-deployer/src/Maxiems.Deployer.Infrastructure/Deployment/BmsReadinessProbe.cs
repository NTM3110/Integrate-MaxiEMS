using System.Net.WebSockets;
using System.Text.Json;
using Maxiems.Deployer.Infrastructure.Ssh;

namespace Maxiems.Deployer.Infrastructure.Deployment;

internal static class BmsReadinessProbe
{
    public static async Task WaitAsync(SshNetTransportSession session, bool verifyDefaultLogin,
        CancellationToken cancellationToken)
    {
        using var forward = session.OpenLocalForward(8085);
        var deadline = DateTimeOffset.UtcNow.AddMinutes(3);
        while (DateTimeOffset.UtcNow < deadline)
        {
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(15));
                using var socket = new ClientWebSocket();
                await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{forward.LocalPort}"), timeout.Token)
                    .ConfigureAwait(false);
                await VerifyAsync((method, parameters) => CallAsync(socket, method, parameters, timeout.Token),
                    verifyDefaultLogin).ConfigureAwait(false);
                return;
            }
            catch (Exception exception) when (exception is WebSocketException or IOException or JsonException
                or InvalidOperationException or OperationCanceledException)
            {
                cancellationToken.ThrowIfCancellationRequested();
            }
            await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
        }
        // Never include an authentication response/token in exception/report text.
        throw new TimeoutException("BMS WebSocket/database authentication readiness failed.");
    }

    internal static async Task VerifyAsync(Func<string, object, Task<JsonElement>> call, bool verifyDefaultLogin)
    {
        var ready = await call("platformReady", new
        {
        }).ConfigureAwait(false);
        if (!ready.TryGetProperty("ready", out var available) || available.ValueKind != JsonValueKind.True)
        {
            throw new InvalidOperationException("BMS database/authentication is unavailable.");
        }
        if (!verifyDefaultLogin)
            return;
        var login = await call("platformAuthenticate", new
        {
            username = "admin",
            password = "admin"
        })
            .ConfigureAwait(false);
        if (!login.TryGetProperty("token", out var value) || value.ValueKind != JsonValueKind.String
            || string.IsNullOrWhiteSpace(value.GetString()))
        {
            throw new InvalidOperationException("BMS administrator login did not return a session.");
        }
        var token = value.GetString()!;
        try
        {
            var principal = await call("platformAuthenticateWithToken", new
            {
                token
            }).ConfigureAwait(false);
            if (!principal.TryGetProperty("roles", out var roles) || roles.ValueKind != JsonValueKind.Array
                || !roles.EnumerateArray().Any(role => role.ValueKind == JsonValueKind.String && role.GetString() == "PLATFORM_ADMIN"))
            {
                throw new InvalidOperationException("BMS bootstrap account has no administrator role.");
            }
        }
        finally
        {
            await call("platformLogout", new
            {
                token
            }).ConfigureAwait(false);
        }
    }

    internal static string NewRequestId() => Guid.NewGuid().ToString("D");

    private static async Task<JsonElement> CallAsync(ClientWebSocket socket, string method, object parameters,
        CancellationToken cancellationToken)
    {
        var id = NewRequestId();
        var request = JsonSerializer.SerializeToUtf8Bytes(new
        {
            jsonrpc = "2.0",
            id,
            method,
            @params = parameters
        });
        await socket.SendAsync(request.AsMemory(), WebSocketMessageType.Text, true, cancellationToken).ConfigureAwait(false);
        var buffer = new byte[4096];
        while (true)
        {
            using var message = new MemoryStream();
            ValueWebSocketReceiveResult received;
            do
            {
                received = await socket.ReceiveAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
                if (received.MessageType != WebSocketMessageType.Text || message.Length + received.Count > 65536)
                {
                    throw new IOException("Invalid BMS readiness response.");
                }
                message.Write(buffer, 0, received.Count);
            } while (!received.EndOfMessage);
            using var document = JsonDocument.Parse(message.ToArray());
            var response = document.RootElement;
            if (!response.TryGetProperty("id", out var responseId) || responseId.ValueKind != JsonValueKind.String
                || responseId.GetString() != id)
                continue;
            if (response.TryGetProperty("error", out _) || !response.TryGetProperty("result", out var result))
            {
                throw new InvalidOperationException("BMS readiness request rejected.");
            }
            return result.Clone();
        }
    }
}
