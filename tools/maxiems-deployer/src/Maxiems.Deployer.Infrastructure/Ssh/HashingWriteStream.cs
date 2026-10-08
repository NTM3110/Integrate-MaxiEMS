using System.Security.Cryptography;

namespace Maxiems.Deployer.Infrastructure.Ssh;

internal sealed class HashingWriteStream : Stream
{
    private readonly IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);

    public override bool CanRead => false;

    public override bool CanSeek => false;

    public override bool CanWrite => true;

    public override long Length => this.BytesWritten;

    public override long Position
    {
        get => this.BytesWritten;
        set => throw new NotSupportedException();
    }

    public long BytesWritten
    {
        get; private set;
    }

    public string GetSha256() => Convert.ToHexString(this.hash.GetCurrentHash()).ToLowerInvariant();

    public override void Write(byte[] buffer, int offset, int count)
    {
        this.hash.AppendData(buffer, offset, count);
        this.BytesWritten += count;
    }

    public override void Write(ReadOnlySpan<byte> buffer)
    {
        this.hash.AppendData(buffer);
        this.BytesWritten += buffer.Length;
    }

    public override ValueTask WriteAsync(
        ReadOnlyMemory<byte> buffer,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        this.hash.AppendData(buffer.Span);
        this.BytesWritten += buffer.Length;
        return ValueTask.CompletedTask;
    }

    public override Task WriteAsync(
        byte[] buffer,
        int offset,
        int count,
        CancellationToken cancellationToken)
    {
        this.Write(buffer, offset, count);
        return Task.CompletedTask;
    }

    public override void Flush()
    {
    }

    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            this.hash.Dispose();
        }
        base.Dispose(disposing);
    }
}
