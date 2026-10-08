[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[string] $EdgeJar,

	[Parameter(Mandatory = $true)]
	[string] $UiDist,

	[Parameter(Mandatory = $true)]
	[string] $Output,

	[string] $GitRevision,

	[string] $SourceTimestampUtc,

	[string] $BuildTimestampUtc
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Sha256([string] $Path) {
	return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-RegularFile([System.IO.FileInfo] $File, [string] $Label) {
	if (-not $File.Exists) {
		throw "$Label does not exist: $($File.FullName)"
	}
	if ($File.Length -eq 0) {
		throw "$Label must not be empty: $($File.FullName)"
	}
	if (($File.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
		throw "$Label must not be a symbolic link or reparse point: $($File.FullName)"
	}
}

function Assert-SafeRelativePath([string] $Path) {
	if ([System.IO.Path]::IsPathRooted($Path) -or $Path.Contains('\') -or $Path.Split('/') -contains '..') {
		throw "Unsafe payload path: $Path"
	}
}

function Add-PayloadFile(
	[System.Collections.Generic.List[object]] $Files,
	[string] $Source,
	[string] $Path
) {
	Assert-SafeRelativePath $Path
	$file = Get-Item -LiteralPath $Source -Force
	Assert-RegularFile $file "Payload file '$Path'"
	$Files.Add([pscustomobject][ordered]@{
		Source = $file.FullName
		Path = $Path
		Length = [long]$file.Length
		Sha256 = Get-Sha256 $file.FullName
	})
}

function Add-PayloadTree(
	[System.Collections.Generic.List[object]] $Files,
	[string] $Root,
	[string] $Prefix
) {
	$rootItem = Get-Item -LiteralPath $Root -Force
	if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
		throw "Payload directory must not be a symbolic link or reparse point: $Root"
	}
	$rootPath = $rootItem.FullName.TrimEnd('\', '/')
	foreach ($directory in Get-ChildItem -LiteralPath $rootPath -Directory -Recurse -Force) {
		if (($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
			throw "Payload directory must not contain a symbolic link or reparse point: $($directory.FullName)"
		}
	}
	foreach ($file in Get-ChildItem -LiteralPath $rootPath -File -Recurse -Force | Sort-Object FullName) {
		$relative = $file.FullName.Substring($rootPath.Length).TrimStart('\', '/').Replace('\', '/')
		Add-PayloadFile $Files $file.FullName "$Prefix/$relative"
	}
}

function Get-CanonicalPayloadHash([object[]] $Files) {
	$builder = New-Object System.Text.StringBuilder
	foreach ($file in $Files | Sort-Object Path) {
		[void]$builder.Append($file.Path).Append("`0").Append($file.Length).Append("`0").Append($file.Sha256).Append("`n")
	}
	$bytes = [System.Text.Encoding]::UTF8.GetBytes($builder.ToString())
	$sha = [System.Security.Cryptography.SHA256]::Create()
	try {
		return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
	} finally {
		$sha.Dispose()
	}
}

function Assert-SourcesUnchanged([object[]] $Files) {
	foreach ($file in $Files) {
		$current = Get-Item -LiteralPath $file.Source -Force
		Assert-RegularFile $current "Payload file '$($file.Path)'"
		if ($current.Length -ne $file.Length -or (Get-Sha256 $current.FullName) -ne $file.Sha256) {
			throw "Payload source changed after manifest generation: $($file.Path)"
		}
	}
}

function Get-GitValue([string[]] $Arguments) {
	$value = & git @Arguments 2>$null
	if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
		throw "Unable to derive reproducible Git metadata; pass -GitRevision and -BuildTimestampUtc."
	}
	return ($value | Select-Object -First 1).Trim()
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path -LiteralPath $EdgeJar -PathType Leaf)) {
	throw "Edge JAR does not exist: $EdgeJar"
}
$edge = Get-Item -LiteralPath $EdgeJar -Force
Assert-RegularFile $edge 'Edge JAR'
if (-not (Test-Path -LiteralPath $UiDist -PathType Container)) {
	throw "UI dist is not a directory: $UiDist"
}
$ui = Get-Item -LiteralPath $UiDist -Force
if (-not $ui.PSIsContainer) {
	throw "UI dist is not a directory: $UiDist"
}
$indexPath = Join-Path $ui.FullName 'index.html'
if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
	throw "UI index.html does not exist: $indexPath"
}
$index = Get-Item -LiteralPath $indexPath -Force
Assert-RegularFile $index 'UI index.html'

if ([string]::IsNullOrWhiteSpace($GitRevision)) {
	$GitRevision = Get-GitValue @('rev-parse', 'HEAD')
}
$GitRevision = $GitRevision.ToLowerInvariant()
if ($GitRevision -notmatch '^[0-9a-f]{40}$') {
	throw 'GitRevision must be a 40-character hexadecimal commit ID.'
}
if ([string]::IsNullOrWhiteSpace($SourceTimestampUtc)) {
	$SourceTimestampUtc = Get-GitValue @('show', '-s', '--format=%cI', $GitRevision)
}
if ([string]::IsNullOrWhiteSpace($BuildTimestampUtc)) {
	$BuildTimestampUtc = [DateTimeOffset]::UtcNow.ToString('O', [Globalization.CultureInfo]::InvariantCulture)
}
$sourceTimestamp = [DateTimeOffset]::Parse($SourceTimestampUtc, [Globalization.CultureInfo]::InvariantCulture).ToUniversalTime()
$buildTimestamp = [DateTimeOffset]::Parse($BuildTimestampUtc, [Globalization.CultureInfo]::InvariantCulture).ToUniversalTime()

$files = New-Object 'System.Collections.Generic.List[object]'
Add-PayloadFile $files $edge.FullName 'edge/openems.jar'
Add-PayloadTree $files $ui.FullName 'ui'
Add-PayloadTree $files (Join-Path $scriptRoot 'payload\bootstrap') 'bootstrap'
Add-PayloadTree $files (Join-Path $scriptRoot 'remote\etc') 'remote/etc'
Add-PayloadTree $files (Join-Path $scriptRoot 'remote\usr') 'remote/usr'
Add-PayloadTree $files (Join-Path $scriptRoot 'prerequisites') 'prerequisites'
$sortedFiles = @($files | Sort-Object Path)
$payloadHash = Get-CanonicalPayloadHash $sortedFiles
$releaseId = $buildTimestamp.ToString('yyyyMMddHHmmss', [Globalization.CultureInfo]::InvariantCulture) + '-' + $payloadHash.Substring(0, 12)
$manifestFiles = @($sortedFiles | ForEach-Object {
	[pscustomobject][ordered]@{ path = $_.Path; length = $_.Length; sha256 = $_.Sha256 }
})
$manifest = [pscustomobject][ordered]@{
	formatVersion = 2
	releaseId = $releaseId
	gitRevision = $GitRevision
	sourceTimestampUtc = $sourceTimestamp.ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
	buildTimestampUtc = $buildTimestamp.ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
	sourcePaths = @(
		[pscustomobject][ordered]@{ kind = 'bootstrap'; path = 'tools/maxiems-deployer/payload/bootstrap' },
		[pscustomobject][ordered]@{ kind = 'edge'; path = 'build/openems.jar' },
		[pscustomobject][ordered]@{ kind = 'remote'; path = 'tools/maxiems-deployer/remote' },
		[pscustomobject][ordered]@{ kind = 'prerequisites'; path = 'tools/maxiems-deployer/prerequisites' },
		[pscustomobject][ordered]@{ kind = 'ui'; path = 'ui/dist' }
	)
	supportedTargets = [pscustomobject][ordered]@{
		ubuntuVersions = @('24.04')
		architectures = @('amd64', 'arm64')
	}
	payloadHash = $payloadHash
	files = $manifestFiles
}
$manifestJson = (($manifest | ConvertTo-Json -Depth 12) -replace "`r`n", "`n") + "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)

Assert-SourcesUnchanged $sortedFiles
$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$temporaryPath = Join-Path $outputDirectory ('.payload-' + [Guid]::NewGuid().ToString('N') + '.tmp')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Open($temporaryPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite)
try {
	$archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
	try {
		$entries = @([pscustomobject]@{ Path = 'manifest.json'; Source = $null }) + $sortedFiles
		foreach ($item in $entries | Sort-Object Path) {
			$entry = $archive.CreateEntry($item.Path, [System.IO.Compression.CompressionLevel]::Optimal)
			$entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
			$target = $entry.Open()
			try {
				if ($null -eq $item.Source) {
					$bytes = $utf8.GetBytes($manifestJson)
					$target.Write($bytes, 0, $bytes.Length)
				} else {
					$source = [System.IO.File]::OpenRead($item.Source)
					try { $source.CopyTo($target) } finally { $source.Dispose() }
				}
			} finally { $target.Dispose() }
		}
	} finally { $archive.Dispose() }
} finally { $stream.Dispose() }

if (Test-Path -LiteralPath $outputPath) {
	Remove-Item -LiteralPath $outputPath -Force
}
Move-Item -LiteralPath $temporaryPath -Destination $outputPath
$archiveSha256 = Get-Sha256 $outputPath
[pscustomobject]@{
	Output = $outputPath
	ReleaseId = $releaseId
	PayloadHash = $payloadHash
	ArchiveSha256 = $archiveSha256
	FileCount = $sortedFiles.Count
} | ConvertTo-Json
