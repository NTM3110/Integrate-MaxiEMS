[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDirectory -ErrorAction Stop).Path
$required = @('Maxiems.Deployer.exe','Maxiems.Deployer.exe.sha256','release-metadata.json','release-status.txt',
	'sbom.spdx.json','deployment-report.template.json','deployment-report.template.html')
foreach ($name in $required) {
	if (-not (Test-Path -LiteralPath (Join-Path $release $name) -PathType Leaf)) { throw "Missing release file: $name" }
}
$executables = @(Get-ChildItem -LiteralPath $release -File -Filter *.exe)
if ($executables.Count -ne 1 -or $executables[0].Name -ne 'Maxiems.Deployer.exe') {
	throw 'Release must contain exactly one executable named Maxiems.Deployer.exe.'
}
$exe = $executables[0].FullName
$metadata = Get-Content (Join-Path $release 'release-metadata.json') -Raw | ConvertFrom-Json
$checksumLine = (Get-Content (Join-Path $release 'Maxiems.Deployer.exe.sha256') -Raw).Trim()
$actualHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
if ($checksumLine -ne "$actualHash *Maxiems.Deployer.exe" -or $metadata.executable.sha256 -ne $actualHash) {
	throw 'Executable checksum verification failed.'
}
$sbom = Get-Content (Join-Path $release 'sbom.spdx.json') -Raw | ConvertFrom-Json
if ($sbom.spdxVersion -ne 'SPDX-2.3' -or @($sbom.packages).Count -eq 0) { throw 'SPDX dependency inventory is invalid.' }
Get-Content (Join-Path $release 'deployment-report.template.json') -Raw | ConvertFrom-Json | Out-Null
if ((Get-Content (Join-Path $release 'deployment-report.template.html') -Raw) -notmatch '<!doctype html>') {
	throw 'HTML report template is invalid.'
}
$signature = Get-AuthenticodeSignature -LiteralPath $exe
if ($metadata.signature.signed) {
	if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Thumbprint -ne $metadata.signature.thumbprint) {
		throw "Signed release verification failed: $($signature.Status)"
	}
} elseif ($signature.Status -ne 'NotSigned' -or
	(Get-Content (Join-Path $release 'release-status.txt') -Raw) -notmatch 'UNSIGNED INTERNAL BUILD') {
	throw 'Unsigned release is not labeled or unexpectedly contains a signature.'
}
$process = Start-Process -FilePath $exe -ArgumentList '--verify-payload' -WindowStyle Hidden -PassThru
if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'Published EXE payload verification timed out.' }
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw "Published EXE rejected its embedded payload with exit $($process.ExitCode)." }
[pscustomobject]@{ Status = 'passed'; Executable = $exe; Sha256 = $actualHash; Signed = [bool]$metadata.signature.signed;
	SbomPackages = @($sbom.packages).Count; EmbeddedPayload = 'verified' }
