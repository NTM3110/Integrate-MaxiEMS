param(
    [Parameter(Mandatory = $true)][string]$PayloadPath,
    [Parameter(Mandatory = $true)][string]$Output,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string[]]$SupportedUbuntuVersions,
    [Parameter(Mandatory = $true)][ValidateSet('amd64', 'arm64')][string[]]$SupportedArchitectures,
    [Parameter(Mandatory = $true)][string]$MinimumDeployerVersion
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$payload = [IO.Path]::GetFullPath($PayloadPath)
$outputPath = [IO.Path]::GetFullPath($Output)
if (-not [IO.File]::Exists($payload)) { throw "Payload archive does not exist: $payload" }
if ([IO.FileInfo]::new($payload).Length -gt 2GB) { throw 'Payload archive exceeds the 2 GiB limit.' }

$payloadArchive = [IO.Compression.ZipFile]::OpenRead($payload)
try {
    $manifestEntry = $payloadArchive.GetEntry('manifest.json')
    if ($null -eq $manifestEntry -or $manifestEntry.Length -gt 1MB) {
        throw 'Payload manifest.json is missing or oversized.'
    }
    $reader = [IO.StreamReader]::new($manifestEntry.Open(), [Text.UTF8Encoding]::new($false), $true)
    try { $payloadManifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
} finally {
    $payloadArchive.Dispose()
}

if ($payloadManifest.formatVersion -ne 2 -or
    [string]$payloadManifest.buildTimestampUtc -notmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$') {
    throw 'Payload manifest must use format 2 with a UTC build timestamp.'
}
$parsedVersion = [Version]::new()
if (-not [Version]::TryParse($MinimumDeployerVersion, [ref]$parsedVersion) -or
    $MinimumDeployerVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw 'MinimumDeployerVersion must use major.minor.patch format.'
}

$payloadHash = (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant()
$buildTimestamp = [DateTimeOffset]::ParseExact([string]$payloadManifest.buildTimestampUtc,
    'yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
$releaseId = $buildTimestamp.ToUniversalTime().ToString('yyyyMMddHHmmss',
    [Globalization.CultureInfo]::InvariantCulture) + '-' + $payloadHash.Substring(0, 12)
$metadata = [ordered]@{
    schemaVersion = 1
    releaseId = $releaseId
    version = $Version
    payloadSha256 = $payloadHash
    supportedUbuntuVersions = @($SupportedUbuntuVersions)
    supportedArchitectures = @($SupportedArchitectures)
    minimumDeployerVersion = $MinimumDeployerVersion
}
$metadataBytes = [Text.UTF8Encoding]::new($false).GetBytes(($metadata | ConvertTo-Json -Depth 4 -Compress))
$directory = [IO.Path]::GetDirectoryName($outputPath)
[IO.Directory]::CreateDirectory($directory) | Out-Null
$temporary = [IO.Path]::Combine($directory, ".release-$([Guid]::NewGuid().ToString('N')).tmp")

try {
    $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $metadataEntry = $archive.CreateEntry('release.json', [IO.Compression.CompressionLevel]::Optimal)
            $metadataEntry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $metadataStream = $metadataEntry.Open()
            try { $metadataStream.Write($metadataBytes, 0, $metadataBytes.Length) } finally { $metadataStream.Dispose() }

            $payloadEntry = $archive.CreateEntry('payload.zip', [IO.Compression.CompressionLevel]::NoCompression)
            $payloadEntry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $payloadTarget = $payloadEntry.Open()
            $payloadSource = [IO.File]::OpenRead($payload)
            try { $payloadSource.CopyTo($payloadTarget) } finally { $payloadSource.Dispose(); $payloadTarget.Dispose() }
        } finally { $archive.Dispose() }
        $stream.Flush($true)
    } finally { $stream.Dispose() }
    if ([IO.File]::Exists($outputPath)) { throw "Output already exists: $outputPath" }
    [IO.File]::Move($temporary, $outputPath)
} finally {
    if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
}

Write-Output $outputPath
