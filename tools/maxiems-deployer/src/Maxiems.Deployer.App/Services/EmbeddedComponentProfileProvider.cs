using System.IO;
using System.Reflection;
using Maxiems.Deployer.App.ViewModels;

namespace Maxiems.Deployer.App.Services;

public sealed class EmbeddedComponentProfileProvider(string directory) : IWizardComponentProfileProvider
{
    private const string ResourceName = "Maxiems.Deployer.Profiles.ModbusReadOnly.json";
    private readonly string directory = Path.GetFullPath(directory);

    public string? ResolveManifest(bool enableModbusProfile)
    {
        if (!enableModbusProfile)
            return null;
        Directory.CreateDirectory(this.directory);
        var path = Path.Combine(this.directory, "modbus-readonly.v1.json");
        using var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName)
            ?? throw new InvalidDataException("The built-in Modbus component profile is missing.");
        using var memory = new MemoryStream();
        resource.CopyTo(memory);
        var content = memory.ToArray();
        if (File.Exists(path) && File.ReadAllBytes(path).AsSpan().SequenceEqual(content))
            return path;
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, content);
            File.Move(temporary, path, true);
            return path;
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }
}
