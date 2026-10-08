Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Utf8File {
	param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Content)
	[IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Assert-SafePublishPath {
	param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$RepositoryRoot)
	$full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
	$root = [IO.Path]::GetPathRoot($full).TrimEnd('\', '/')
	$repository = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
	if ($full.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
		$full.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or
		$repository.StartsWith($full + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
		throw "Unsafe publish output path: $full"
	}
	return $full
}

function Assert-ReplaceableReleaseDirectory {
	param([Parameter(Mandatory)][string]$Path)
	if (-not (Test-Path -LiteralPath $Path)) { return }
	$allowed = @('Maxiems.Deployer.exe','Maxiems.Deployer.exe.sha256','release-metadata.json','release-status.txt',
		'sbom.spdx.json','deployment-report.template.json','deployment-report.template.html')
	$unexpected = @(Get-ChildItem -LiteralPath $Path -Force | Where-Object { $_.PSIsContainer -or $_.Name -notin $allowed })
	if ($unexpected.Count -ne 0) {
		throw "Refusing to replace a directory containing non-release entries: $($unexpected.Name -join ', ')"
	}
}

function Resolve-SignTool {
	param([string]$RequestedPath)
	if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
		$resolved = (Resolve-Path -LiteralPath $RequestedPath -ErrorAction Stop).Path
		if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "SignTool is not a file: $resolved" }
		return $resolved
	}
	$command = Get-Command signtool.exe -ErrorAction SilentlyContinue
	if ($command) { return $command.Source }
	$kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
	$candidate = Get-ChildItem $kits -Recurse -Filter signtool.exe -File -ErrorAction SilentlyContinue |
		Where-Object { $_.DirectoryName -match '\\x64$' } | Sort-Object FullName -Descending | Select-Object -First 1
	if (-not $candidate) { throw 'SignTool was requested but signtool.exe was not found.' }
	return $candidate.FullName
}

function Invoke-ReleaseSigning {
	param(
		[Parameter(Mandatory)][string]$Executable,
		[string]$CertificatePath,
		[Security.SecureString]$CertificatePassword,
		[string]$CertificateThumbprint,
		[string]$TimestampUrl,
		[string]$SignToolPath
	)
	$pathRequested = -not [string]::IsNullOrWhiteSpace($CertificatePath)
	$thumbprintRequested = -not [string]::IsNullOrWhiteSpace($CertificateThumbprint)
	if (-not $pathRequested -and -not $thumbprintRequested) {
		if ($CertificatePassword -or -not [string]::IsNullOrWhiteSpace($TimestampUrl) -or
			-not [string]::IsNullOrWhiteSpace($SignToolPath)) {
			throw 'Signing password, timestamp, or SignTool path was supplied without a certificate.'
		}
		return [pscustomobject]@{ Signed = $false; Thumbprint = $null; Status = 'NotSigned' }
	}
	if ($pathRequested -and $thumbprintRequested) { throw 'Choose CertificatePath or CertificateThumbprint, not both.' }
	$timestamp = $null
	if (-not [Uri]::TryCreate($TimestampUrl, [UriKind]::Absolute, [ref]$timestamp) -or
		$timestamp.Scheme -notin @('http', 'https')) { throw 'Signing requires an absolute HTTP(S) TimestampUrl.' }

	$tool = Resolve-SignTool $SignToolPath
	$storeName = 'My'
	$storeLocation = 'CurrentUser'
	$temporaryStore = $null
	$thumbprint = $null
	try {
		if ($pathRequested) {
			$pfx = (Resolve-Path -LiteralPath $CertificatePath -ErrorAction Stop).Path
			$temporaryStore = "MaxiemsDeployer-$([Guid]::NewGuid().ToString('N'))"
			$storePath = "Cert:\CurrentUser\$temporaryStore"
			$store = [Security.Cryptography.X509Certificates.X509Store]::new(
				$temporaryStore, [Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
			try { $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite) } finally { $store.Dispose() }
			$parameters = @{ FilePath = $pfx; CertStoreLocation = $storePath }
			if ($CertificatePassword) { $parameters.Password = $CertificatePassword }
			$imported = @(Import-PfxCertificate @parameters)
			$signingCertificate = $imported | Where-Object HasPrivateKey | Select-Object -First 1
			if (-not $signingCertificate) { throw 'The supplied certificate file has no private signing key.' }
			$thumbprint = $signingCertificate.Thumbprint
			$storeName = $temporaryStore
		} else {
			$thumbprint = [regex]::Replace($CertificateThumbprint, '\s', '').ToUpperInvariant()
			if ($thumbprint -notmatch '^[0-9A-F]{40}$') { throw 'CertificateThumbprint must be a SHA-1 thumbprint.' }
			$certificate = Get-ChildItem Cert:\CurrentUser\My,Cert:\LocalMachine\My -ErrorAction SilentlyContinue |
				Where-Object { $_.Thumbprint -eq $thumbprint -and $_.HasPrivateKey } | Select-Object -First 1
			if (-not $certificate) { throw "No signing certificate with thumbprint $thumbprint and private key was found." }
			if ($certificate.PSParentPath -like '*LocalMachine*') { $storeLocation = 'LocalMachine' }
		}

		$arguments = @('sign', '/sha1', $thumbprint, '/s', $storeName, '/fd', 'SHA256', '/tr',
			$timestamp.AbsoluteUri, '/td', 'SHA256')
		if ($storeLocation -eq 'LocalMachine') { $arguments += '/sm' }
		$arguments += $Executable
		& $tool @arguments | Out-Host
		if ($LASTEXITCODE -ne 0) { throw "SignTool failed with exit $LASTEXITCODE." }
		$signature = Get-AuthenticodeSignature -LiteralPath $Executable
		if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or
			$signature.SignerCertificate.Thumbprint -ne $thumbprint) {
			throw "Authenticode verification failed: $($signature.Status) $($signature.StatusMessage)"
		}
		return [pscustomobject]@{ Signed = $true; Thumbprint = $thumbprint; Status = $signature.Status.ToString() }
	} finally {
		if ($temporaryStore) {
			$cleanup = [Security.Cryptography.X509Certificates.X509Store]::new(
				$temporaryStore, [Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
			try {
				$cleanup.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
				foreach ($certificate in @($cleanup.Certificates)) { $cleanup.Remove($certificate) }
			} finally { $cleanup.Dispose() }
			$registryPath = "HKCU:\Software\Microsoft\SystemCertificates\$temporaryStore"
			if (Test-Path -LiteralPath $registryPath) {
				Remove-Item -LiteralPath $registryPath -Recurse -Force
			}
		}
	}
}

function New-SpdxDocument {
	param([Parameter(Mandatory)][string]$AssetsPath, [Parameter(Mandatory)][string]$Version)
	$assets = Get-Content -LiteralPath $AssetsPath -Raw | ConvertFrom-Json
	$packages = @()
	$relationships = @()
	foreach ($library in $assets.libraries.PSObject.Properties | Where-Object { $_.Value.type -eq 'package' } |
		Sort-Object Name) {
		$slash = $library.Name.LastIndexOf('/')
		$name = $library.Name.Substring(0, $slash)
		$packageVersion = $library.Name.Substring($slash + 1)
		$id = 'SPDXRef-Package-' + ([regex]::Replace("$name-$packageVersion", '[^A-Za-z0-9.-]', '-'))
		$packages += [ordered]@{ name = $name; SPDXID = $id; versionInfo = $packageVersion;
			downloadLocation = 'NOASSERTION'; filesAnalyzed = $false; licenseConcluded = 'NOASSERTION';
			licenseDeclared = 'NOASSERTION'; copyrightText = 'NOASSERTION' }
		$relationships += [ordered]@{ spdxElementId = 'SPDXRef-DOCUMENT'; relationshipType = 'DESCRIBES'; relatedSpdxElement = $id }
	}
	return [ordered]@{ spdxVersion = 'SPDX-2.3'; dataLicense = 'CC0-1.0'; SPDXID = 'SPDXRef-DOCUMENT';
		name = "MAXiEMS-Deployer-$Version"; documentNamespace = "https://maxiems.local/spdx/$Version/$([Guid]::NewGuid())";
		creationInfo = [ordered]@{ created = [DateTimeOffset]::UtcNow.ToString('O'); creators = @('Tool: MAXiEMS publish.ps1') };
		packages = $packages; relationships = $relationships }
}

Export-ModuleMember -Function Write-Utf8File, Assert-SafePublishPath, Assert-ReplaceableReleaseDirectory,
	Invoke-ReleaseSigning, New-SpdxDocument
