using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using Maxiems.Deployer.Core.Security;

namespace Maxiems.Deployer.Infrastructure.OpenEms;

public interface IBootstrapEntropy
{
    byte[] GetBytes(int count);
}

public sealed class CryptographicBootstrapEntropy : IBootstrapEntropy
{
    public byte[] GetBytes(int count) => RandomNumberGenerator.GetBytes(count);
}

public sealed record OpenEmsBootstrapFile(string Name, byte[] Content);

public sealed record OpenEmsBootstrapConfiguration(ImmutableArray<OpenEmsBootstrapFile> Files)
{
    private static readonly string[] KnownDefaultPasswords = ["admin", "installer", "owner", "user"];

    public static OpenEmsBootstrapConfiguration Create(
        ReadOnlySpan<char> administratorPassword,
        IBootstrapEntropy? entropy = null)
    {
        OpenEmsManagementPassword.RequireNonEmpty(administratorPassword, nameof(administratorPassword));
        foreach (var knownDefault in KnownDefaultPasswords)
        {
            if (administratorPassword.SequenceEqual(knownDefault))
            {
                throw new ArgumentException("A repository default OpenEMS password is forbidden.",
                    nameof(administratorPassword));
            }
        }

        entropy ??= new CryptographicBootstrapEntropy();
        var administrator = DeriveAdministrator(administratorPassword, entropy);
        var installer = DeriveDisabledRole(entropy);
        var owner = DeriveDisabledRole(entropy);
        var guest = DeriveDisabledRole(entropy);
        return new([
            File("Core/ComponentManager.config", ComponentManager()),
            File("Core/User.config", Users(administrator, installer, owner, guest)),
            File("Controller/Api/Rest/ReadWrite/maxiems.config", Rest()),
            File("Controller/Api/Websocket/maxiems.config", Websocket()),
        ]);
    }

    private static CredentialHash DeriveAdministrator(ReadOnlySpan<char> password, IBootstrapEntropy entropy)
    {
        var salt = RequireEntropy(entropy, OpenEmsPasswordHasher.SaltLengthBytes);
        return new(Convert.ToBase64String(OpenEmsPasswordHasher.Derive(password, salt)), Convert.ToBase64String(salt));
    }

    private static CredentialHash DeriveDisabledRole(IBootstrapEntropy entropy)
    {
        var salt = RequireEntropy(entropy, OpenEmsPasswordHasher.SaltLengthBytes);
        var password = RequireEntropy(entropy, 32);
        try
        {
            return new(Convert.ToBase64String(OpenEmsPasswordHasher.Derive(password, salt)),
                Convert.ToBase64String(salt));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(password);
        }
    }

    private static byte[] RequireEntropy(IBootstrapEntropy entropy, int count)
    {
        var value = entropy.GetBytes(count);
        if (value.Length != count)
        {
            throw new InvalidOperationException("The bootstrap entropy source returned an invalid byte count.");
        }
        return value;
    }

    private static OpenEmsBootstrapFile File(string name, string content) =>
        new(name, Encoding.UTF8.GetBytes(content.Replace("\r\n", "\n", StringComparison.Ordinal)));

    private static string ConfigAdminString(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("\"", "\\\"", StringComparison.Ordinal)
        .Replace("=", "\\=", StringComparison.Ordinal);

    private static string ComponentManager() => """
		:org.apache.felix.configadmin.revision:=L"1"
		alias="Core.ComponentManager"
		id="_componentManager"
		service.bundleLocation="?"
		service.pid="Core.ComponentManager"
		""";

    private static string Users(params CredentialHash[] users) => $"""
		:org.apache.felix.configadmin.revision:=L"1"
		adminPassword="{ConfigAdminString(users[0].Password)}"
		adminSalt="{ConfigAdminString(users[0].Salt)}"
		guestPassword="{ConfigAdminString(users[3].Password)}"
		guestSalt="{ConfigAdminString(users[3].Salt)}"
		installerPassword="{ConfigAdminString(users[1].Password)}"
		installerSalt="{ConfigAdminString(users[1].Salt)}"
		ownerPassword="{ConfigAdminString(users[2].Password)}"
		ownerSalt="{ConfigAdminString(users[2].Salt)}"
		service.bundleLocation="?"
		service.pid="Core.User"
		users=""
		""";

    private static string Rest() => """
		:org.apache.felix.configadmin.revision:=L"1"
		alias=""
		apiTimeout=I"60"
		bindAddress="127.0.0.1"
		connectionlimit=I"5"
		debugMode=B"false"
		enabled=B"true"
		id="ctrlApiRest0"
		port=I"8084"
		service.bundleLocation="?"
		service.factoryPid="Controller.Api.Rest.ReadWrite"
		service.pid="Controller.Api.Rest.ReadWrite.maxiems"
		""";

    private static string Websocket() => """
		:org.apache.felix.configadmin.revision:=L"1"
		alias=""
		apiTimeout=I"60"
		bindAddress="0.0.0.0"
		debugMode=B"false"
		enabled=B"true"
		id="ctrlApiWebsocket0"
		port=I"8085"
		service.bundleLocation="?"
		service.factoryPid="Controller.Api.Websocket"
		service.pid="Controller.Api.Websocket.maxiems"
		""";

    private sealed record CredentialHash(string Password, string Salt);
}
