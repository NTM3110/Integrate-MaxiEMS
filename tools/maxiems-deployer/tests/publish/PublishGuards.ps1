Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Import-Module (Join-Path $root 'publish-support.psm1') -Force

$partialRejected = $false
try {
	Invoke-ReleaseSigning -Executable 'missing.exe' -TimestampUrl 'http://timestamp.invalid' | Out-Null
} catch {
	$partialRejected = $_.Exception.Message -like '*without a certificate*'
}
if (-not $partialRejected) { throw 'Partial signing configuration was accepted.' }

$guard = Join-Path $root 'artifacts\publish\unsafe-replace-test'
New-Item -ItemType Directory -Path $guard -Force | Out-Null
New-Item -ItemType File -Path (Join-Path $guard 'user-data.txt') -Force | Out-Null
try {
	$guardRejected = $false
	try { Assert-ReplaceableReleaseDirectory $guard } catch {
		$guardRejected = $_.Exception.Message -like '*non-release entries*'
	}
	if (-not $guardRejected) { throw 'Unsafe publish replacement was accepted.' }
} finally {
	Remove-Item -LiteralPath $guard -Recurse -Force
}

'Publish guard sensitivity tests passed.'
