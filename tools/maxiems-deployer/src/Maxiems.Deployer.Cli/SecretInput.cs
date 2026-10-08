namespace Maxiems.Deployer.Cli;

public static class SecretInput
{
    public static char[] Read(Func<ConsoleKeyInfo> readKey, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(readKey);
        var buffer = new char[4096];
        var length = 0;
        try
        {
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var key = readKey();
                if (key.Key == ConsoleKey.Enter)
                {
                    return buffer[..length];
                }
                if (key.Key == ConsoleKey.Backspace)
                {
                    if (length > 0)
                    {
                        buffer[--length] = '\0';
                    }
                }
                else if (!char.IsControl(key.KeyChar))
                {
                    if (length == buffer.Length)
                    {
                        throw new ArgumentException("Secret is too long.");
                    }
                    buffer[length++] = key.KeyChar;
                }
            }
        }
        finally
        {
            Array.Clear(buffer);
        }
    }
}
