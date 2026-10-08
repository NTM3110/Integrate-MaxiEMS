[CmdletBinding()]
param(
	[Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')][string]$Version,
	[string]$EdgeJar = (Join-Path $PSScriptRoot '..\..\build\openems.jar'),
	[string]$UiDist = (Join-Path $PSScriptRoot '..\..\ui\dist'),
	[string]$OutputDirectory = (Join-Path $PSScriptRoot 'artifacts\publish'),
	[string]$DotNetPath = 'dotnet',
	[string]$CertificatePath,
	[Security.SecureString]$CertificatePassword,
	[string]$CertificateThumbprint,
	[string]$TimestampUrl,
	[string]$SignToolPath,
	[switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'publish-support.psm1') -Force
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$output = Assert-SafePublishPath $OutputDirectory $repositoryRoot
$edge = (Resolve-Path -LiteralPath $EdgeJar -ErrorAction Stop).Path
$ui = (Resolve-Path -LiteralPath $UiDist -ErrorAction Stop).Path
$dotnet = if (Test-Path -LiteralPath $DotNetPath -PathType Leaf) {
	(Resolve-Path -LiteralPath $DotNetPath).Path
} else { (Get-Command $DotNetPath -ErrorAction Stop).Source }
if ((Get-Item $edge).Length -eq 0) { throw 'Edge JAR must not be empty.' }
if (-not (Get-Item $ui).PSIsContainer -or @(Get-ChildItem $ui -Recurse -File).Count -eq 0) {
	throw 'UI dist must be a non-empty directory.'
}
if (Test-Path -LiteralPath $output) {
	if (-not $Force) { throw "Output already exists; use -Force to replace generated release: $output" }
	Assert-ReplaceableReleaseDirectory $output
	Remove-Item -LiteralPath $output -Recurse -Force
}
$parent = Split-Path $output -Parent
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$work = Join-Path $parent ".publish-$([Guid]::NewGuid().ToString('N'))"
$release = Join-Path $work 'release'
$published = Join-Path $work 'app'
$payload = Join-Path $work 'payload.zip'
New-Item -ItemType Directory -Path $release,$published -Force | Out-Null
try {
	$solution = Join-Path $PSScriptRoot 'Maxiems.Deployer.slnx'
	$appProject = Join-Path $PSScriptRoot 'src\Maxiems.Deployer.App\Maxiems.Deployer.App.csproj'
	& $dotnet restore $solution --locked-mode -p:NuGetAudit=false
	if ($LASTEXITCODE -ne 0) { throw "dotnet restore failed with exit $LASTEXITCODE." }
	& $dotnet build $solution -c Release --no-restore -p:NuGetAudit=false
	if ($LASTEXITCODE -ne 0) { throw "dotnet build failed with exit $LASTEXITCODE." }
	& $dotnet test $solution -c Release --no-build -p:NuGetAudit=false
	if ($LASTEXITCODE -ne 0) { throw "dotnet test failed with exit $LASTEXITCODE." }

	$revision = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -First 1)
	if ([string]::IsNullOrWhiteSpace($revision)) { $revision = 'unknown' }
	$publishedAt = [DateTimeOffset]::UtcNow.ToString('O')
	& (Join-Path $PSScriptRoot 'build-payload.ps1') -EdgeJar $edge -UiDist $ui -Output $payload `
		-GitRevision $revision -BuildTimestampUtc $publishedAt
	& $dotnet publish $appProject -c Release -r win-x64 --self-contained true --no-restore -o $published `
		-p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None `
		-p:DebugSymbols=false -p:NuGetAudit=false -p:Version=$Version -p:MaxiemsPayloadPath=$payload
	if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit $LASTEXITCODE." }
	$publishedFiles = @(Get-ChildItem -LiteralPath $published -File)
	if ($publishedFiles.Count -ne 1 -or $publishedFiles[0].Name -ne 'Maxiems.Deployer.exe') {
		throw "Single-file publish emitted unexpected files: $($publishedFiles.Name -join ', ')"
	}
	$exe = Join-Path $release 'Maxiems.Deployer.exe'
	Copy-Item -LiteralPath $publishedFiles[0].FullName -Destination $exe
	$signing = Invoke-ReleaseSigning -Executable $exe -CertificatePath $CertificatePath `
		-CertificatePassword $CertificatePassword -CertificateThumbprint $CertificateThumbprint `
		-TimestampUrl $TimestampUrl -SignToolPath $SignToolPath
	$exeHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
	$payloadHash = (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant()
	Write-Utf8File (Join-Path $release 'Maxiems.Deployer.exe.sha256') "$exeHash *Maxiems.Deployer.exe`n"
	$status = if ($signing.Signed) { "SIGNED RELEASE`nThumbprint: $($signing.Thumbprint)`n" } else {
		"UNSIGNED INTERNAL BUILD`nDo not represent this artifact as Authenticode-signed.`n"
	}
	Write-Utf8File (Join-Path $release 'release-status.txt') $status
	$assets = Join-Path $PSScriptRoot 'src\Maxiems.Deployer.App\obj\project.assets.json'
	$spdx = New-SpdxDocument $assets $Version
	Write-Utf8File (Join-Path $release 'sbom.spdx.json') ($spdx | ConvertTo-Json -Depth 10)
	$reportTemplate = [ordered]@{ reportFormatVersion = 2; deploymentId = '00000000-0000-0000-0000-000000000000';
		finalState = 'NotStarted'; startedAt = $null; completedAt = $null; duration = '00:00:00'; releaseId = $null;
		artifactHashes = [ordered]@{}; versions = [ordered]@{}; phases = @(); logs = @(); checks = @();
		hostTrust = $null; rollback = $null; evidence = @(); manufacturing = $null }
	Write-Utf8File (Join-Path $release 'deployment-report.template.json') ($reportTemplate | ConvertTo-Json -Depth 8)
	$html = '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>MAXiEMS deployment report</title></head>' +
		'<body><h1>MAXiEMS deployment report</h1><p>The installer replaces this template with redacted run evidence.</p></body></html>'
	Write-Utf8File (Join-Path $release 'deployment-report.template.html') $html
	$metadata = [ordered]@{ schemaVersion = 1; product = 'MAXiEMS Deployer'; version = $Version; gitRevision = $revision;
		publishedAt = $publishedAt; runtimeIdentifier = 'win-x64'; selfContained = $true; singleFile = $true;
		executable = [ordered]@{ file = 'Maxiems.Deployer.exe'; sha256 = $exeHash; bytes = (Get-Item $exe).Length };
		payload = [ordered]@{ embedded = $true; sha256 = $payloadHash };
		signature = [ordered]@{ signed = [bool]$signing.Signed; status = $signing.Status; thumbprint = $signing.Thumbprint };
		policy = [ordered]@{ vulnerabilityAudit = 'skipped-by-operator-instruction' } }
	Write-Utf8File (Join-Path $release 'release-metadata.json') ($metadata | ConvertTo-Json -Depth 8)
	& (Join-Path $PSScriptRoot 'verify-release.ps1') -ReleaseDirectory $release | Out-Host
	Move-Item -LiteralPath $release -Destination $output
	Get-ChildItem -LiteralPath $output -File | Sort-Object Name | Select-Object Name,Length
} finally {
	if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
