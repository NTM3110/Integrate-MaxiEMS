[CmdletBinding()]
param(
	[Parameter(Mandatory)]
	[ValidateSet('FixtureSelfTest', 'FirstInstall', 'IdempotentRedeploy', 'Upgrade', 'FailureMatrix', 'SecurityMatrix')]
	[string[]]$Scenario,
	[ValidateSet('amd64', 'arm64')]
	[string[]]$Architecture = @('amd64', 'arm64'),
	[string]$ArtifactsDirectory,
	[string]$DeployerExeV1,
	[string]$DeployerExeV2,
	[switch]$KeepFixture
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# The managed Windows runner can expose both Path and PATH. .NET folds environment
# names case-insensitively when Start-Process builds a child environment and throws
# before QEMU or the seed HTTP server can start. Normalize the process environment.
$processPath = [Environment]::GetEnvironmentVariable('Path', 'Process')
if ($env:OS -eq 'Windows_NT' -and -not [string]::IsNullOrWhiteSpace($processPath)) {
	[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
	[Environment]::SetEnvironmentVariable('Path', $null, 'Process')
	[Environment]::SetEnvironmentVariable('Path', $processPath, 'Process')
}
if ([string]::IsNullOrWhiteSpace($ArtifactsDirectory)) {
	$ArtifactsDirectory = Join-Path $PSScriptRoot 'artifacts'
}
Import-Module (Join-Path $PSScriptRoot 'providers\QemuProvider.psm1') -Force

$lock = Get-Content (Join-Path $PSScriptRoot 'images.lock.json') -Raw | ConvertFrom-Json
$vmRoot = Join-Path $env:TEMP 'maxiems-vm-fixtures'
$cache = Join-Path $env:TEMP 'maxiems-vm-image-cache'
New-Item -ItemType Directory -Force $ArtifactsDirectory,$vmRoot | Out-Null
$run = [ordered]@{
	schemaVersion = 1
	startedAt = [DateTimeOffset]::UtcNow.ToString('O')
	provider = 'qemu'
	qemuHome = Resolve-QemuHome
	imageRelease = $lock.release
	scenarios = @()
}

function Invoke-FixtureSelfTest {
	param([Parameter(Mandatory)][string]$TargetArchitecture)
	$session = $null
	$started = [DateTimeOffset]::UtcNow
	try {
		$fixtureParameters = @{
			Architecture = $TargetArchitecture
			VmRoot = $vmRoot
			TemplatesRoot = Join-Path $PSScriptRoot 'cloud-init'
			ImageLock = $lock
			CacheDirectory = $cache
		}
		$session = New-QemuFixture @fixtureParameters
		$bootTimeoutMinutes = if ($TargetArchitecture -eq 'arm64') { 12 } else { 4 }
		Wait-QemuFixtureReady $session ([TimeSpan]::FromMinutes($bootTimeoutMinutes))
		$marker = Assert-FixtureMarker $session
		$identity = Invoke-FixtureSsh $session 'source /etc/os-release; echo $VERSION_ID; dpkg --print-architecture; uname -m'
		$parts = @($identity.Output -split "`r?`n")
		if ($parts[0] -ne '24.04') { throw "Expected Ubuntu 24.04, got $($parts[0])." }
		if ($parts[1] -ne $session.ExpectedArchitecture) {
			throw "Expected $($session.ExpectedArchitecture), got $($parts[1])."
		}
		$fingerprint = (& 'C:\Windows\System32\OpenSSH\ssh-keygen.exe' -lf $session.KnownHostsPath -E sha256) -join "`n"
		if ([string]::IsNullOrWhiteSpace($fingerprint)) { throw 'SSH fingerprint was not captured.' }

		Save-QemuFixtureSnapshot $session
		Assert-FixtureMarker $session | Out-Null
		Invoke-FixtureSsh $session 'sudo touch /var/lib/maxiems-fixture-reset-sentinel' | Out-Null
		Reset-QemuFixtureSnapshot $session
		Assert-FixtureMarker $session | Out-Null
		$sentinel = Invoke-FixtureSsh $session 'test ! -e /var/lib/maxiems-fixture-reset-sentinel' -AllowFailure
		if ($sentinel.ExitCode -ne 0) { throw 'Snapshot reset did not remove the scenario sentinel.' }

		Invoke-FixtureSsh $session 'sudo rm /etc/maxiems-fixture/marker.json' | Out-Null
		$guardRejected = $false
		try { Assert-FixtureMarker $session | Out-Null } catch { $guardRejected = $_.Exception.Message -like '*refusing mutation*' }
		if (-not $guardRejected) { throw 'Fixture guard accepted a host without a valid marker.' }
		Reset-QemuFixtureSnapshot $session
		Assert-FixtureMarker $session | Out-Null

		return [ordered]@{
			scenario = 'FixtureSelfTest'
			architecture = $TargetArchitecture
			status = 'passed'
			fixtureId = $session.FixtureId
			marker = $marker
			identity = $identity.Output
			sshFingerprint = $fingerprint
			guardRejectedUnmarkedHost = $true
			snapshotResetVerified = $true
			durationSeconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 3)
		}
	} finally {
		if ($session) {
			foreach ($logName in @('console.log', 'qemu.stdout.log', 'qemu.stderr.log',
				'seed-server.stdout.log', 'seed-server.stderr.log', 'session.json', 'ssh-keyscan.log', 'ssh-attempt.log')) {
				$source = Join-Path $session.InstanceRoot $logName
				if (Test-Path $source) {
					Copy-Item -LiteralPath $source -Destination (Join-Path $ArtifactsDirectory "$TargetArchitecture-$logName") -Force
				}
			}
		}
		if ($session -and -not $KeepFixture) { Remove-QemuFixture $session }
	}
}

function Invoke-DeploymentDriver {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$Executable,
		[Parameter(Mandatory)][string]$ScenarioName
	)
	$password = 'E2E-Only-Admin-Password!42'
	$inputPath = Join-Path $Session.InstanceRoot "driver-$ScenarioName-input.json"
	$resultPath = Join-Path $Session.InstanceRoot "driver-$ScenarioName-result.json"
	$reports = Join-Path $Session.InstanceRoot 'reports'
	$input = [ordered]@{
		host = '127.0.0.1'
		port = $Session.Port
		bootstrapUsername = 'fixture-admin'
		privateKeyPath = $Session.SshKeyPath
		privateKeyPassphrase = ''
		administratorPassword = $password
		manifestPath = (Resolve-Path (Join-Path $PSScriptRoot '..\..\fixtures\openems-components.modbus-readonly.v1.json')).Path
		configureTailscale = $true
		simulateTailscale = $true
		confirmObservedHostKey = $true
		trustStorePath = Join-Path $Session.InstanceRoot 'driver-ssh-trust.json'
		reportsDirectory = $reports
		resultPath = $resultPath
	}
	[System.IO.File]::WriteAllText($inputPath, ($input | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
	try {
		$parameters = @{
			FilePath = $Executable
			ArgumentList = "--test-driver `"$inputPath`""
			WindowStyle = 'Hidden'
			PassThru = $true
		}
		$process = Start-Process @parameters
		if (-not $process.WaitForExit(1800000)) {
			$process.Kill()
			$process.WaitForExit()
			throw "Deployment test driver timed out for $ScenarioName."
		}
		if (-not (Test-Path $resultPath)) { throw "Deployment test driver produced no result for $ScenarioName." }
		$result = Get-Content $resultPath -Raw | ConvertFrom-Json
		Copy-Item -LiteralPath $resultPath -Destination (Join-Path $ArtifactsDirectory "$($Session.Architecture)-$ScenarioName-result.json") -Force
		if ($process.ExitCode -ne 0 -or $result.status -ne 'Succeeded') {
			$errorText = if ($result.PSObject.Properties.Name -contains 'error') { $result.error } else { $result.status }
			throw "Deployment test driver failed for $ScenarioName with exit $($process.ExitCode): $errorText"
		}
		if ($result.schemaVersion -ne 1 -or $result.record.reportFormatVersion -ne 1 `
			-or $result.record.finalState -ne 0 -or @($result.record.phases | Where-Object outcome -ne 2).Count -ne 0 `
			-or @($result.record.checks | Where-Object passed -ne $true).Count -ne 0) {
			throw "Deployment result/report contract is invalid for $ScenarioName."
		}
		$requiredPhases = 0..12
		$actualPhases = @($result.record.phases | ForEach-Object { [int]$_.phase })
		if (@($requiredPhases | Where-Object { $_ -notin $actualPhases }).Count -ne 0 -or 14 -notin $actualPhases) {
			throw "Deployment result omitted a required phase for $ScenarioName."
		}
		if (@($result.events | Where-Object { $_.phase -eq 'ConfigureTailscale' -and $_.tailscaleLoginAvailable }).Count -ne 1) {
			throw "Simulated Tailscale login flow was not surfaced exactly once for $ScenarioName."
		}
		$serialized = Get-Content $resultPath -Raw
		if ($serialized.IndexOf($password, [StringComparison]::Ordinal) -ge 0) {
			throw "Deployment test result leaked the administrator password for $ScenarioName."
		}
		Copy-Item -LiteralPath $result.jsonReportPath -Destination (Join-Path $ArtifactsDirectory "$($Session.Architecture)-$ScenarioName-report.json") -Force
		Copy-Item -LiteralPath $result.htmlReportPath -Destination (Join-Path $ArtifactsDirectory "$($Session.Architecture)-$ScenarioName-report.html") -Force
		$report = Get-Content $result.jsonReportPath -Raw | ConvertFrom-Json
		if ($report.reportFormatVersion -ne 1 -or $report.deploymentId -ne $result.record.deploymentId) {
			throw "Exported JSON report does not match the deployment for $ScenarioName."
		}
		$html = Get-Content $result.htmlReportPath -Raw
		if ($html -notlike '*MAXiEMS*' -or $html.IndexOf($password, [StringComparison]::Ordinal) -ge 0) {
			throw "Exported HTML report is invalid or leaked a secret for $ScenarioName."
		}
		return $result
	} finally {
		if (Test-Path $inputPath) { Remove-Item -LiteralPath $inputPath -Force }
	}
}

function Invoke-OpenEmsJsonRpc {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$Username,
		[Parameter(Mandatory)][string]$Password
	)
	$id = [guid]::NewGuid().ToString()
	$body = @{ jsonrpc = '2.0'; id = $id; method = 'getEdgeConfig'; params = @{} } | ConvertTo-Json -Compress
	$credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:${Password}"))
	$response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Session.HttpPort)/jsonrpc" `
		-Method Post -Headers @{ Authorization = "Basic $credential" } -ContentType 'application/json' `
		-Body $body -TimeoutSec 30
	$rpc = $response.Content | ConvertFrom-Json
	if ($response.StatusCode -ne 200 -or $rpc.id -ne $id -or -not $rpc.result.components) {
		throw 'Authenticated JSON-RPC proxy returned an invalid response.'
	}
	return $rpc
}

function Assert-DefaultPasswordsRejected {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	foreach ($pair in @(@('Admin','admin'), @('Installer','installer'), @('Owner','owner'), @('Guest','user'))) {
		try {
			Invoke-OpenEmsJsonRpc $Session $pair[0] $pair[1] | Out-Null
			throw "Repository default password remained valid for $($pair[0])."
		} catch {
			if ($_.Exception.Message -like 'Repository default password remained valid*') { throw }
			$status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
			if ($status -notin @(401,403,500)) { throw "Default credential probe failed inconclusively for $($pair[0]): $($_.Exception.Message)" }
		}
	}
}

function Assert-WebSocketUpgrade {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$client = [Net.Sockets.TcpClient]::new('127.0.0.1', $Session.HttpPort)
	try {
		$stream = $client.GetStream()
		$stream.ReadTimeout = 15000
		$key = [Convert]::ToBase64String([Guid]::NewGuid().ToByteArray())
		$request = "GET /websocket HTTP/1.1`r`nHost: 127.0.0.1:$($Session.HttpPort)`r`nUpgrade: websocket`r`nConnection: Upgrade`r`nSec-WebSocket-Key: $key`r`nSec-WebSocket-Version: 13`r`n`r`n"
		$bytes = [Text.Encoding]::ASCII.GetBytes($request)
		$stream.Write($bytes, 0, $bytes.Length)
		$buffer = New-Object byte[] 4096
		$count = $stream.Read($buffer, 0, $buffer.Length)
		$response = [Text.Encoding]::ASCII.GetString($buffer, 0, $count)
		if (-not $response.StartsWith('HTTP/1.1 101 ', [StringComparison]::Ordinal)) {
			throw "WebSocket proxy did not accept an upgrade: $response"
		}
	} finally { $client.Dispose() }
}

function Assert-ModbusProtocol {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$client = [Net.Sockets.TcpClient]::new('127.0.0.1', $Session.ModbusPort)
	try {
		$stream = $client.GetStream()
		$stream.ReadTimeout = 15000
		$request = [byte[]](0,1,0,0,0,6,1,3,0,1,0,1)
		$stream.Write($request, 0, $request.Length)
		$buffer = New-Object byte[] 260
		$count = $stream.Read($buffer, 0, $buffer.Length)
		if ($count -lt 9 -or $buffer[0] -ne 0 -or $buffer[1] -ne 1 -or $buffer[6] -ne 1 `
			-or $buffer[7] -notin @(3,131)) { throw 'Modbus/TCP protocol response was invalid.' }
	} finally { $client.Dispose() }
}

function Invoke-FixtureBashScript {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$Script,
		[switch]$AllowFailure
	)
	$normalized = $Script.Replace("`r`n", "`n").Replace("`r", "`n")
	$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($normalized))
	return Invoke-FixtureSsh $Session "printf %s $encoded | base64 --decode | bash" -AllowFailure:$AllowFailure
}

function Get-EdgeStartCount {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$result = Invoke-FixtureBashScript $Session "sudo journalctl -u maxiems-edge.service --no-pager | grep -c 'Starting maxiems-edge.service' || true"
	return [int]$result.Output.Trim()
}

function Assert-LifecycleTarget {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$ExpectedRelease,
		[Parameter(Mandatory)][int]$ExpectedReleaseCount,
		[string]$ExpectedPreviousRelease
	)
	$marker = Assert-FixtureMarker $Session
	$unit = Invoke-FixtureSsh $Session 'sudo systemctl show maxiems-edge.service -p User -p Group -p AmbientCapabilities -p CapabilityBoundingSet'
	$unitValues = @{}
	foreach ($line in @($unit.Output -split "`r?`n")) {
		if ($line -match '^([^=]+)=(.*)$') { $unitValues[$Matches[1]] = $Matches[2] }
	}
	if ($unitValues.User -ne 'openems' -or $unitValues.Group -ne 'openems' `
		-or $unitValues.AmbientCapabilities -ne 'cap_net_bind_service' `
		-or $unitValues.CapabilityBoundingSet -ne 'cap_net_bind_service') {
		throw "Edge identity/capability policy is invalid: $($unit.Output)"
	}
	$edgePid = (Invoke-FixtureSsh $Session 'sudo systemctl show maxiems-edge.service -p MainPID --value').Output.Trim()
	$status = Invoke-FixtureSsh $Session "sudo cat /proc/$edgePid/status"
	$capEff = ($status.Output -split "`r?`n" | Where-Object { $_ -like 'CapEff:*' }) -replace '^CapEff:\s*',''
	if ($capEff -ne '0000000000000400') { throw "Unexpected Edge effective capabilities: $capEff" }
	$current = (Invoke-FixtureSsh $Session 'sudo readlink -f /opt/maxiems/current').Output.Trim()
	if (-not $current.EndsWith("/$ExpectedRelease", [StringComparison]::Ordinal)) {
		throw "Expected release $ExpectedRelease, got $current."
	}
	$releaseCount = [int](Invoke-FixtureSsh $Session 'sudo find /opt/maxiems/releases -mindepth 1 -maxdepth 1 -type d -printf x').Output.Length
	if ($releaseCount -ne $ExpectedReleaseCount) { throw "Expected $ExpectedReleaseCount releases, got $releaseCount." }
	$remotePolicyScript = @'
set -eu
getent passwd maxiems-deploy | grep -Eq '^maxiems-deploy:x:991:991::/var/lib/maxiems-deploy:/bin/bash$'
getent passwd openems | grep -Eq '^openems:x:993:[0-9]+::/var/lib/openems:/usr/sbin/nologin$'
test "$(id -gn openems)" = openems
test "$(getent passwd maxiems-deploy | wc -l)" = 1
test "$(getent passwd openems | wc -l)" = 1
test "$(sudo stat -c '%U:%G:%a' /etc/sudoers.d/maxiems-deployer)" = root:root:440
sudo visudo -cf /etc/sudoers.d/maxiems-deployer >/dev/null
if sudo -u maxiems-deploy sudo -n /usr/bin/id -u >/dev/null 2>&1; then exit 91; fi
test "$(systemctl is-active maxiems-edge.service)" = active
test "$(systemctl is-active nginx.service)" = active
sudo test -f /opt/maxiems/current/.maxiems-known-good
sudo jq -e '.formatVersion == 2 and (.buildTimestampUtc | type) == "string" and (.files | length) > 0' /opt/maxiems/current/manifest.json >/dev/null
dpkg-query -W -f='${Package}=${Version}\n' openjdk-21-jre-headless nginx jq unzip tailscale
/usr/local/bin/node --version
sudo sha256sum /etc/sudoers.d/maxiems-deployer | cut -d' ' -f1
'@
	$remotePolicy = Invoke-FixtureBashScript $Session $remotePolicyScript
	$policyLines = @($remotePolicy.Output -split "`r?`n")
	foreach ($package in @('openjdk-21-jre-headless=', 'nginx=', 'jq=', 'unzip=', 'tailscale=')) {
		if (-not ($policyLines -like "$package*")) { throw "Required package version is missing: $package" }
	}
	if (-not ($policyLines -like 'v24.*')) { throw 'Pinned Node.js 24 runtime is missing.' }
	$previous = (Invoke-FixtureSsh $Session 'if test -L /opt/maxiems/previous; then readlink -f /opt/maxiems/previous; else printf absent; fi').Output.Trim()
	if ($ExpectedPreviousRelease) {
		if (-not $previous.EndsWith("/$ExpectedPreviousRelease", [StringComparison]::Ordinal)) {
			throw "Expected previous release $ExpectedPreviousRelease, got $previous."
		}
	} elseif ($previous -ne 'absent') { throw "Unexpected previous release link: $previous" }
	$response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Session.HttpPort)/" -TimeoutSec 20
	if ($response.StatusCode -ne 200 -or $response.Content -notlike '*<html*') { throw 'nginx did not serve the UI.' }
	$deep = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Session.HttpPort)/e2e/deep/route" -TimeoutSec 20
	if ($deep.StatusCode -ne 200) { throw 'nginx SPA fallback failed.' }
	$rpc = Invoke-OpenEmsJsonRpc $Session 'Admin' 'E2E-Only-Admin-Password!42'
	$modbusComponents = @($rpc.result.components.PSObject.Properties | Where-Object Name -eq 'ctrlApiModbusTcp0')
	if ($modbusComponents.Count -ne 1 -or $modbusComponents[0].Value.factoryId -ne 'Controller.Api.ModbusTcp.ReadOnly') {
		throw 'OpenEMS component did not survive restart or was duplicated.'
	}
	Assert-DefaultPasswordsRejected $Session
	Assert-WebSocketUpgrade $Session
	Assert-ModbusProtocol $Session
	$report = [ordered]@{
		fixtureId = $marker.fixtureId
		release = $ExpectedRelease
		edgePid = [int]$edgePid
		capEff = $capEff
		user = $unitValues.User
		group = $unitValues.Group
		httpStatus = $response.StatusCode
		spaStatus = $deep.StatusCode
		releaseCount = $releaseCount
		previousRelease = $previous
		packageVersions = @($policyLines | Where-Object { $_ -like '*=*' -or $_ -like 'v24.*' })
		sudoersSha256 = $policyLines[-1]
		jsonRpc = 'authenticated'
		webSocket = 'upgraded'
		modbus = 'protocol-response'
	}
	return $report
}

function Invoke-DeploymentLifecycle {
	param(
		[Parameter(Mandatory)][string]$TargetArchitecture,
		[Parameter(Mandatory)][string[]]$RequestedScenarios
	)
	$session = $null
	try {
		$session = New-QemuFixture -Architecture $TargetArchitecture -VmRoot $vmRoot `
			-TemplatesRoot (Join-Path $PSScriptRoot 'cloud-init') -ImageLock $lock -CacheDirectory $cache
		$bootMinutes = if ($TargetArchitecture -eq 'arm64') { 12 } else { 4 }
		Wait-QemuFixtureReady $session ([TimeSpan]::FromMinutes($bootMinutes))
		$results = @()
		$v1Release = $null
		foreach ($requested in $RequestedScenarios) {
			$startsBefore = Get-EdgeStartCount $session
			switch ($requested) {
				'FirstInstall' {
					$driver = Invoke-DeploymentDriver $session $DeployerExeV1 $requested
					$v1Release = $driver.record.releaseId.value
					$proof = Assert-LifecycleTarget $session $v1Release 1
					$expectedStarts = 2
				}
				'IdempotentRedeploy' {
					if (-not $v1Release) { throw 'IdempotentRedeploy requires FirstInstall in the same run.' }
					$driver = Invoke-DeploymentDriver $session $DeployerExeV1 $requested
					if ($driver.record.releaseId.value -ne $v1Release) { throw 'Idempotent redeploy changed release ID.' }
					$proof = Assert-LifecycleTarget $session $v1Release 1
					$expectedStarts = 1
				}
				'Upgrade' {
					if (-not $v1Release) { throw 'Upgrade requires FirstInstall in the same run.' }
					$driver = Invoke-DeploymentDriver $session $DeployerExeV2 $requested
					$v2Release = $driver.record.releaseId.value
					if ($v2Release -eq $v1Release) { throw 'Upgrade did not change release ID.' }
					$proof = Assert-LifecycleTarget $session $v2Release 2 $v1Release
					$expectedStarts = 1
				}
				default { throw "Unsupported lifecycle scenario '$requested'." }
			}
			$deploymentStarts = (Get-EdgeStartCount $session) - $startsBefore
			if ($deploymentStarts -ne $expectedStarts) {
				throw "$requested performed $deploymentStarts Edge starts; expected exactly $expectedStarts."
			}
			$proof['edgeStarts'] = $deploymentStarts
			$results += [ordered]@{ scenario = $requested; status = 'passed'; proof = $proof; deployment = $driver.record }
		}
		return $results
	} finally {
		if ($session) {
			foreach ($logName in @('console.log','qemu.stderr.log','seed-server.stderr.log','session.json','ssh-attempt.log')) {
				$source = Join-Path $session.InstanceRoot $logName
				if (Test-Path $source) { Copy-Item $source (Join-Path $ArtifactsDirectory "$TargetArchitecture-lifecycle-$logName") -Force }
			}
		}
		if ($session -and -not $KeepFixture) { Remove-QemuFixture $session }
	}
}

$script:MatrixHarnessRoot = $PSScriptRoot
. (Join-Path $PSScriptRoot 'MatrixScenarios.ps1')

try {
	$fixtureScenarios = @($Scenario | Where-Object { $_ -eq 'FixtureSelfTest' })
	$lifecycleScenarios = @($Scenario | Where-Object { $_ -in @('FirstInstall','IdempotentRedeploy','Upgrade') })
	$failureMatrixRequested = 'FailureMatrix' -in $Scenario
	$securityMatrixRequested = 'SecurityMatrix' -in $Scenario
	foreach ($requested in $fixtureScenarios) {
		foreach ($targetArchitecture in $Architecture) {
			$run.scenarios += Invoke-FixtureSelfTest $targetArchitecture
		}
	}
	if ($lifecycleScenarios.Count) {
		if (-not $DeployerExeV1) { $DeployerExeV1 = Join-Path $PSScriptRoot '..\..\artifacts\e2e\v1\Maxiems.Deployer.exe' }
		if (-not $DeployerExeV2) { $DeployerExeV2 = Join-Path $PSScriptRoot '..\..\artifacts\e2e\v2\Maxiems.Deployer.exe' }
		$DeployerExeV1 = (Resolve-Path $DeployerExeV1).Path
		$DeployerExeV2 = (Resolve-Path $DeployerExeV2).Path
		$lifecycleArchitectures = if ($PSBoundParameters.ContainsKey('Architecture')) { $Architecture } else { @('amd64') }
		foreach ($targetArchitecture in $lifecycleArchitectures) {
			$run.scenarios += Invoke-DeploymentLifecycle $targetArchitecture $lifecycleScenarios
		}
	}
	if ($failureMatrixRequested) {
		$matrixExecutable = if ($DeployerExeV2) { $DeployerExeV2 } else {
			Join-Path $PSScriptRoot '..\..\artifacts\e2e\matrix\Maxiems.Deployer.exe'
		}
		$matrixArchitectures = if ($PSBoundParameters.ContainsKey('Architecture')) { $Architecture } else { @('amd64') }
		foreach ($targetArchitecture in $matrixArchitectures) {
			$run.scenarios += Invoke-FailureMatrix -TargetArchitecture $targetArchitecture `
				-Executable $matrixExecutable -VmRoot $vmRoot -TemplatesRoot (Join-Path $PSScriptRoot 'cloud-init') `
				-ImageLock $lock -CacheDirectory $cache -ArtifactsDirectory $ArtifactsDirectory -KeepFixture:$KeepFixture
		}
	}
	if ($securityMatrixRequested) {
		$matrixExecutable = if ($DeployerExeV2) { $DeployerExeV2 } else {
			Join-Path $PSScriptRoot '..\..\artifacts\e2e\matrix\Maxiems.Deployer.exe'
		}
		$matrixExecutable = (Resolve-Path $matrixExecutable).Path
		$matrixArchitectures = if ($PSBoundParameters.ContainsKey('Architecture')) { $Architecture } else { @('amd64') }
		foreach ($targetArchitecture in $matrixArchitectures) {
			$run.scenarios += Invoke-SecurityMatrix -TargetArchitecture $targetArchitecture `
				-Executable $matrixExecutable -VmRoot $vmRoot -TemplatesRoot (Join-Path $PSScriptRoot 'cloud-init') `
				-ImageLock $lock -CacheDirectory $cache -ArtifactsDirectory $ArtifactsDirectory -KeepFixture:$KeepFixture
		}
	}
	$run.status = 'passed'
} catch {
	$run.status = 'failed'
	$run.error = $_.Exception.Message
	throw
} finally {
	$run.completedAt = [DateTimeOffset]::UtcNow.ToString('O')
	$artifact = Join-Path $ArtifactsDirectory 'fixture-self-test.json'
	[System.IO.File]::WriteAllText($artifact, ($run | ConvertTo-Json -Depth 10),
		[System.Text.UTF8Encoding]::new($false))
}
