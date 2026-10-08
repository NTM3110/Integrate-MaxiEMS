[CmdletBinding()]
param(
	[string] $Destination = (Join-Path $env:TEMP 'maxiems-openssh-fixture')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$version = '10.0.0.0p2-Preview'
$expectedSha256 = '23f50f3458c4c5d0b12217c6a5ddfde0137210a30fa870e98b29827f7b43aba5'
$archive = Join-Path $env:TEMP "OpenSSH-Win64-$version.zip"
$download = "https://github.com/PowerShell/Win32-OpenSSH/releases/download/$version/OpenSSH-Win64.zip"
$sshd = Join-Path $Destination 'OpenSSH-Win64\sshd.exe'

if (-not (Test-Path -LiteralPath $archive)) {
	Invoke-WebRequest -UseBasicParsing $download -OutFile $archive
}
$actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
	throw "Portable OpenSSH checksum mismatch. Expected $expectedSha256, got $actualSha256."
}
if (-not (Test-Path -LiteralPath $sshd)) {
	[System.IO.Directory]::CreateDirectory($Destination) | Out-Null
	Expand-Archive -LiteralPath $archive -DestinationPath $Destination -Force
}

[pscustomobject]@{
	Version = $version
	Directory = Split-Path -Parent $sshd
	Sha256 = $actualSha256
} | ConvertTo-Json
