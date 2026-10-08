[CmdletBinding()]
param(
	[Parameter(Mandatory)][string]$BasePayload,
	[Parameter(Mandatory)][string]$Output,
	[Parameter(Mandatory)]
	[ValidateSet('ChecksumMismatch', 'TraversalManifest', 'NginxInvalid', 'EdgeCrash')]
	[string]$Fault
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Sha256([string]$Path) {
	(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-CanonicalHash([object[]]$Files) {
	$builder = New-Object Text.StringBuilder
	foreach ($file in $Files | Sort-Object path) {
		[void]$builder.Append($file.path).Append("`0").Append($file.length).Append("`0")
		[void]$builder.Append($file.sha256).Append("`n")
	}
	$sha = [Security.Cryptography.SHA256]::Create()
	try {
		$bytes = [Text.Encoding]::UTF8.GetBytes($builder.ToString())
		([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
	} finally { $sha.Dispose() }
}

function Write-Archive([string]$Root, [string]$Destination) {
	$temporary = "$Destination.$([Guid]::NewGuid().ToString('N')).tmp"
	$stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite)
	try {
		$archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
		try {
			$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
			foreach ($file in Get-ChildItem -LiteralPath $rootPath -File -Recurse | Sort-Object FullName) {
				$relative = $file.FullName.Substring($rootPath.Length).TrimStart('\', '/').Replace('\', '/')
				$entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
				$entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
				$target = $entry.Open()
				try {
					$source = [IO.File]::OpenRead($file.FullName)
					try { $source.CopyTo($target) } finally { $source.Dispose() }
				} finally { $target.Dispose() }
			}
		} finally { $archive.Dispose() }
	} finally { $stream.Dispose() }
	if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
	Move-Item -LiteralPath $temporary -Destination $Destination
}

$base = (Resolve-Path $BasePayload).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$working = Join-Path $outputDirectory ('.fault-payload-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($working) | Out-Null
try {
	$archive = [IO.Compression.ZipFile]::OpenRead($base)
	try {
		foreach ($entry in $archive.Entries) {
			if ([string]::IsNullOrEmpty($entry.Name)) { continue }
			$target = [IO.Path]::GetFullPath((Join-Path $working $entry.FullName))
			if (-not $target.StartsWith($working + [IO.Path]::DirectorySeparatorChar,
				[StringComparison]::OrdinalIgnoreCase)) { throw 'Base payload contains an unsafe path.' }
			[IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
			$source = $entry.Open()
			try {
				$destination = [IO.File]::Create($target)
				try { $source.CopyTo($destination) } finally { $destination.Dispose() }
			} finally { $source.Dispose() }
		}
	} finally { $archive.Dispose() }

	$manifestPath = Join-Path $working 'manifest.json'
	$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
	switch ($Fault) {
		'ChecksumMismatch' {
			Add-Content -LiteralPath (Join-Path $working 'bootstrap\README.md') -Value 'tampered' -Encoding UTF8
		}
		'TraversalManifest' {
			$manifest.files[0].path = '../escape'
			[IO.File]::WriteAllText($manifestPath,
				(($manifest | ConvertTo-Json -Depth 20) -replace "`r`n", "`n") + "`n",
				[Text.UTF8Encoding]::new($false))
		}
		'NginxInvalid' {
			[IO.File]::WriteAllText((Join-Path $working 'remote\etc\nginx\sites-available\maxiems'),
				"server { this-is-not-valid; }`n", [Text.UTF8Encoding]::new($false))
		}
		'EdgeCrash' {
			$unitPath = Join-Path $working 'remote\etc\systemd\system\maxiems-edge.service'
			$unit = (Get-Content -LiteralPath $unitPath -Raw) -replace '(?m)^ExecStart=.*$', 'ExecStart=/usr/bin/false'
			[IO.File]::WriteAllText($unitPath, ($unit -replace "`r`n", "`n"), [Text.UTF8Encoding]::new($false))
		}
	}

	if ($Fault -in @('NginxInvalid', 'EdgeCrash')) {
		$files = @(
			Get-ChildItem -LiteralPath $working -File -Recurse |
				Where-Object Name -ne 'manifest.json' |
				ForEach-Object {
					$relative = $_.FullName.Substring($working.Length).TrimStart('\', '/').Replace('\', '/')
					[pscustomobject][ordered]@{ path = $relative; length = [long]$_.Length; sha256 = Get-Sha256 $_.FullName }
				} | Sort-Object path
		)
		$payloadHash = Get-CanonicalHash $files
		$offset = if ($Fault -eq 'NginxInvalid') { 10 } else { 20 }
		$timestamp = [DateTimeOffset]::Parse($manifest.sourceTimestampUtc).ToUniversalTime().AddSeconds($offset)
		$manifest.sourceTimestampUtc = $timestamp.ToString('yyyy-MM-ddTHH:mm:ssZ')
		$manifest.payloadHash = $payloadHash
		$manifest.releaseId = $timestamp.ToString('yyyyMMddHHmmss') + '-' + $payloadHash.Substring(0, 12)
		$manifest.files = $files
		[IO.File]::WriteAllText($manifestPath,
			(($manifest | ConvertTo-Json -Depth 20) -replace "`r`n", "`n") + "`n",
			[Text.UTF8Encoding]::new($false))
	}
	Write-Archive $working $outputPath
	[pscustomobject]@{
		fault = $Fault
		output = $outputPath
		releaseId = if ($Fault -in @('NginxInvalid', 'EdgeCrash')) { $manifest.releaseId } else { $null }
		archiveSha256 = Get-Sha256 $outputPath
	} | ConvertTo-Json
} finally {
	if (Test-Path -LiteralPath $working) { Remove-Item -LiteralPath $working -Recurse -Force }
}
