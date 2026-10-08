namespace Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;

public sealed class HardwareInventoryException(string message, Exception? inner = null) : Exception(message, inner);
