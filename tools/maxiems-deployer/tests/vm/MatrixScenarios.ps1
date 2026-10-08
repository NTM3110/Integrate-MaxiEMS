Set-StrictMode -Version Latest

function Assert-MatrixCondition {
	param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
	if (-not $Condition) { throw $Message }
}

function New-MatrixEncryptedKey {
	param([Parameter(Mandatory)][pscustomobject]$Session, [Parameter(Mandatory)][string]$Passphrase)
	$target = Join-Path $Session.InstanceRoot 'matrix-driver-ed25519'
	$arguments = "-q -t ed25519 -N `"$Passphrase`" -C maxiems-matrix -f `"$target`""
	$process = Start-Process -FilePath 'C:\Windows\System32\OpenSSH\ssh-keygen.exe' `
		-ArgumentList $arguments -WindowStyle Hidden -PassThru -Wait
	if ($process.ExitCode -ne 0) { throw 'Unable to create encrypted matrix SSH key.' }
	Protect-FixturePrivateKey $target
	$publicKeyBytes = [Text.Encoding]::UTF8.GetBytes((Get-Content -LiteralPath ($target + '.pub') -Raw))
	$publicKeyBase64 = [Convert]::ToBase64String($publicKeyBytes)
	Invoke-FixtureSsh $Session `
		"printf '%s' '$publicKeyBase64' | base64 -d >> ~/.ssh/authorized_keys; chmod 0600 ~/.ssh/authorized_keys" | Out-Null
	return $target
}

function Invoke-MatrixDriver {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$Executable,
		[Parameter(Mandatory)][string]$ScenarioName,
		[Parameter(Mandatory)][string]$TrustStorePath,
		[Parameter(Mandatory)][string]$PrivateKeyPath,
		[Parameter(Mandatory)][string]$PrivateKeyPassphrase,
		[Parameter(Mandatory)][string]$AdministratorPassword,
		[Parameter(Mandatory)][string[]]$ExpectedStatus,
		[int[]]$ExpectedExitCode = @(0, 1, 2),
		[int]$Port = 0,
		[bool]$ConfirmObservedHostKey = $true,
		[string]$PayloadPath,
		[string]$ManifestPath,
		[bool]$ConfigureTailscale = $false,
		[bool]$SimulateTailscale = $true,
		[string]$SimulatedTailscaleOutcome = 'success',
		[int]$TailscaleTimeoutMilliseconds = 0
	)
	if ($Port -eq 0) { $Port = $Session.Port }
	$resultPath = Join-Path $Session.InstanceRoot "matrix-$ScenarioName-result.json"
	$reports = Join-Path $Session.InstanceRoot "matrix-$ScenarioName-reports"
	$inputPath = Join-Path $Session.InstanceRoot "matrix-$ScenarioName-input.json"
	$input = [ordered]@{
		host = '127.0.0.1'
		port = $Port
		bootstrapUsername = 'fixture-admin'
		privateKeyPath = $PrivateKeyPath
		privateKeyPassphrase = $PrivateKeyPassphrase
		administratorPassword = $AdministratorPassword
		payloadPath = if ($PayloadPath) { [IO.Path]::GetFullPath($PayloadPath) } else { $null }
		manifestPath = if ($ManifestPath) { [IO.Path]::GetFullPath($ManifestPath) } else { $null }
		configureTailscale = $ConfigureTailscale
		simulateTailscale = $SimulateTailscale
		simulatedTailscaleOutcome = $SimulatedTailscaleOutcome
		tailscaleTimeoutMilliseconds = if ($TailscaleTimeoutMilliseconds -gt 0) {
			$TailscaleTimeoutMilliseconds
		} else { $null }
		confirmObservedHostKey = $ConfirmObservedHostKey
		trustStorePath = $TrustStorePath
		reportsDirectory = $reports
		resultPath = $resultPath
	}
	[IO.File]::WriteAllText($inputPath, ($input | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
	$process = Start-Process -FilePath $Executable -ArgumentList "--test-driver `"$inputPath`"" `
		-WindowStyle Hidden -PassThru
	if (-not $process.WaitForExit(1800000)) {
		$process.Kill()
		$process.WaitForExit()
		throw "Matrix driver timed out for $ScenarioName."
	}
	$process.WaitForExit()
	if (-not (Test-Path -LiteralPath $resultPath)) { throw "Matrix driver produced no result for $ScenarioName." }
	$result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
	$artifactRoot = Join-Path $script:MatrixArtifacts $ScenarioName
	New-Item -ItemType Directory -Force $artifactRoot | Out-Null
	Copy-Item -LiteralPath $resultPath -Destination (Join-Path $artifactRoot 'result.json') -Force
	if ($result.PSObject.Properties.Name -contains 'jsonReportPath' -and $result.jsonReportPath) {
		Copy-Item -LiteralPath $result.jsonReportPath -Destination (Join-Path $artifactRoot 'report.json') -Force
		Copy-Item -LiteralPath $result.htmlReportPath -Destination (Join-Path $artifactRoot 'report.html') -Force
	}
	if ($process.ExitCode -notin $ExpectedExitCode) {
		throw "$ScenarioName exited $($process.ExitCode); expected $($ExpectedExitCode -join ',')."
	}
	if ($result.status -notin $ExpectedStatus) {
		throw "$ScenarioName returned '$($result.status)'; expected $($ExpectedStatus -join ',')."
	}
	return [pscustomobject]@{ ExitCode = $process.ExitCode; Result = $result; ArtifactRoot = $artifactRoot }
}

function Assert-PristineTarget {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$script = @'
set -eu
test ! -e /opt/maxiems/current
test ! -e /opt/maxiems/previous
test ! -e /etc/sudoers.d/maxiems-deployer
! getent passwd maxiems-deploy >/dev/null
! getent passwd openems >/dev/null
test ! -e /run/maxiems-deploy-transaction
'@
	$result = Invoke-FixtureBashScript $Session $script -AllowFailure
	if ($result.ExitCode -ne 0) { throw "Target was mutated before confirmation/preflight: $($result.Output)" }
}

function Get-InstalledInvariant {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$script = @'
set -eu
edge_pid="$(sudo systemctl show maxiems-edge.service -p MainPID --value)"
hash_tree() {
  if test -d "$1"; then
    sudo find "$1" -type f -print0 | sort -z | xargs -0 -r sudo sha256sum | sha256sum | cut -d' ' -f1
  else
    printf absent
  fi
}
printf 'current=%s\n' "$(basename "$(sudo readlink -f /opt/maxiems/current)")"
printf 'previous=%s\n' "$(if test -L /opt/maxiems/previous; then basename "$(sudo readlink -f /opt/maxiems/previous)"; else printf absent; fi)"
printf 'releases=%s\n' "$(sudo find /opt/maxiems/releases -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | paste -sd, -)"
printf 'edgeActive=%s\n' "$(systemctl is-active maxiems-edge.service)"
printf 'nginxActive=%s\n' "$(systemctl is-active nginx.service)"
printf 'edgeUser=%s\n' "$(sudo systemctl show maxiems-edge.service -p User --value)"
printf 'capEff=%s\n' "$(sudo awk '/^CapEff:/ {print $2}' "/proc/$edge_pid/status")"
printf 'sudoersHash=%s\n' "$(sudo sha256sum /etc/sudoers.d/maxiems-deployer | cut -d' ' -f1)"
printf 'unitHash=%s\n' "$(sudo sha256sum /etc/systemd/system/maxiems-edge.service | cut -d' ' -f1)"
printf 'nginxHash=%s\n' "$(sudo sha256sum /etc/nginx/sites-available/maxiems | cut -d' ' -f1)"
printf 'bootstrapHash=%s\n' "$(hash_tree /opt/maxiems/bootstrap)"
printf 'configHash=%s\n' "$(hash_tree /var/lib/maxiems/shared/config)"
printf 'unmanagedHash=%s\n' "$(sudo sha256sum /var/lib/maxiems/shared/unmanaged-matrix.sentinel | cut -d' ' -f1)"
printf 'ipForward=%s/%s\n' "$(cat /proc/sys/net/ipv4/ip_forward)" "$(cat /proc/sys/net/ipv6/conf/all/forwarding)"
printf 'transactionLock=%s\n' "$(if test -e /run/maxiems-deploy-transaction; then printf present; else printf absent; fi)"
printf 'stagingCount=%s\n' "$(sudo find /opt/maxiems/releases -mindepth 1 -maxdepth 1 -name '.staging-*' | wc -l)"
printf 'modbusOwner=%s\n' "$(sudo ss -ltnp | awk '/:502 / {print $0}' | grep -o 'java' | head -n1)"
'@
	$result = Invoke-FixtureBashScript $Session $script
	$values = [ordered]@{}
	foreach ($line in @($result.Output -split "`r?`n")) {
		if ($line -match '^([^=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
	}
	return [pscustomobject]$values
}

function Assert-InstalledInvariant {
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][pscustomobject]$Expected,
		[switch]$AllowManagedConfigAuditChange
	)
	$actual = Get-InstalledInvariant $Session
	$keys = @('current','previous','releases','edgeActive','nginxActive','edgeUser','capEff',
		'sudoersHash','unitHash','nginxHash','bootstrapHash','unmanagedHash','ipForward',
		'transactionLock','stagingCount','modbusOwner')
	if (-not $AllowManagedConfigAuditChange) { $keys += 'configHash' }
	foreach ($key in $keys) {
		if ($actual.$key -ne $Expected.$key) {
			throw "Installed invariant '$key' changed: expected '$($Expected.$key)', got '$($actual.$key)'."
		}
	}
	return $actual
}

function Wait-InstalledInvariant {
	param([Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][pscustomobject]$Expected,
		[TimeSpan]$Timeout = [TimeSpan]::FromMinutes(6))
	$deadline = [DateTimeOffset]::UtcNow + $Timeout
	$last = $null
	do {
		try { return Assert-InstalledInvariant $Session $Expected } catch { $last = $_.Exception.Message }
		Start-Sleep -Seconds 3
	} while ([DateTimeOffset]::UtcNow -lt $deadline)
	throw "Installed target did not recover after VM restart: $last"
}

function Initialize-MatrixUnmanagedSentinel {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	Invoke-FixtureSsh $Session "printf matrix-unmanaged-v1 | sudo tee /var/lib/maxiems/shared/unmanaged-matrix.sentinel >/dev/null; sudo chown root:root /var/lib/maxiems/shared/unmanaged-matrix.sentinel; sudo chmod 0444 /var/lib/maxiems/shared/unmanaged-matrix.sentinel" | Out-Null
}

function Wait-FixtureSudoRestored {
	param([Parameter(Mandatory)][pscustomobject]$Session, [TimeSpan]$Timeout = [TimeSpan]::FromSeconds(90))
	$deadline = [DateTimeOffset]::UtcNow + $Timeout
	do {
		$result = Invoke-FixtureSsh $Session 'sudo -n true' -AllowFailure
		if ($result.ExitCode -eq 0) { return }
		Start-Sleep -Seconds 2
	} while ([DateTimeOffset]::UtcNow -lt $deadline)
	throw 'Fixture sudo policy was not restored by the scheduled fault cleanup.'
}

function Wait-FixtureSshAfterHostKeyRotation {
	param([Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$OldKnownHostsHash,
		[TimeSpan]$Timeout = [TimeSpan]::FromSeconds(60))
	$deadline = [DateTimeOffset]::UtcNow + $Timeout
	$last = $null
	do {
		if (Test-Path -LiteralPath $Session.KnownHostsPath) {
			Remove-Item -LiteralPath $Session.KnownHostsPath -Force
		}
		$last = Invoke-FixtureSsh $Session 'systemctl is-active ssh.service' -TrustOnFirstUse -AllowFailure
		if ($last.ExitCode -eq 0 -and (Test-Path -LiteralPath $Session.KnownHostsPath) -and
			(Get-FileHash -LiteralPath $Session.KnownHostsPath -Algorithm SHA256).Hash -ne $OldKnownHostsHash) { return }
		Start-Sleep -Seconds 2
	} while ([DateTimeOffset]::UtcNow -lt $deadline)
	throw "Fixture SSH did not recover after host-key rotation: $($last.Output)"
}

function Get-TextSecretMatches {
	param([Parameter(Mandatory)][string[]]$Roots, [Parameter(Mandatory)][string[]]$Secrets)
	$matches = @()
	foreach ($root in $Roots) {
		if (-not (Test-Path -LiteralPath $root)) { continue }
		$files = if (Test-Path -LiteralPath $root -PathType Leaf) { @(Get-Item -LiteralPath $root) } else {
			@(Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction SilentlyContinue)
		}
		foreach ($file in $files) {
			if ($file.Name -like '*-input.json') { continue }
			if ($file.Extension -notin @('.json','.html','.log','.txt','.md')) { continue }
			$text = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction SilentlyContinue
			if ($null -eq $text) { continue }
			foreach ($secret in $Secrets) {
				if (-not [string]::IsNullOrEmpty($secret) -and
					$text.IndexOf($secret, [StringComparison]::Ordinal) -ge 0) {
					$matches += "$($file.FullName):$([Array]::IndexOf($Secrets, $secret))"
				}
			}
		}
	}
	return @($matches)
}

function Test-RestrictedSudoersPolicy {
	param([Parameter(Mandatory)][string]$Text)
	return $Text -match '(?m)^maxiems-deploy ALL=\(root\) NOPASSWD: MAXIEMS_DEPLOY$' `
		-and $Text -match '(?m)^openems ALL=\(root\) NOPASSWD: MAXIEMS_NETWORK$' `
		-and $Text -notmatch '(?m)^(maxiems-deploy|openems)\s+ALL=.*NOPASSWD:\s*ALL\s*$'
}

function Invoke-SecurityMatrix {
	param(
		[Parameter(Mandatory)][string]$TargetArchitecture,
		[Parameter(Mandatory)][string]$Executable,
		[Parameter(Mandatory)][string]$VmRoot,
		[Parameter(Mandatory)][string]$TemplatesRoot,
		[Parameter(Mandatory)][pscustomobject]$ImageLock,
		[Parameter(Mandatory)][string]$CacheDirectory,
		[Parameter(Mandatory)][string]$ArtifactsDirectory,
		[switch]$KeepFixture
	)
	$session = $null
	$started = [DateTimeOffset]::UtcNow
	$script:MatrixArtifacts = Join-Path $ArtifactsDirectory "$TargetArchitecture-security-matrix"
	New-Item -ItemType Directory -Force $script:MatrixArtifacts | Out-Null
	$cases = @()
	$password = 'E2E-Only-Admin-Password!42'
	$passphrase = 'Matrix-Key-Passphrase!72'
	try {
		$session = New-QemuFixture -Architecture $TargetArchitecture -VmRoot $VmRoot `
			-TemplatesRoot $TemplatesRoot -ImageLock $ImageLock -CacheDirectory $CacheDirectory
		$bootMinutes = if ($TargetArchitecture -eq 'arm64') { 12 } else { 4 }
		Wait-QemuFixtureReady $session ([TimeSpan]::FromMinutes($bootMinutes))
		Assert-FixtureMarker $session | Out-Null
		$networkDiagnostics = Invoke-FixtureBashScript $session @'
set +e
printf 'resolv.conf\n'
cat /etc/resolv.conf
for probe in \
  '/usr/bin/getent ahosts archive.ubuntu.com' \
  '/usr/bin/getent hosts archive.ubuntu.com' \
  '/usr/bin/curl -4 --connect-timeout 5 --max-time 10 -I http://archive.ubuntu.com/ubuntu/dists/noble/InRelease' \
  '/usr/bin/curl -4 --connect-timeout 5 --max-time 10 -I https://archive.ubuntu.com/ubuntu/dists/noble/InRelease'
do
  printf 'probe=%s\n' "$probe"
  start="$(date +%s%3N)"
  timeout 15 sh -c "$probe" 2>&1
  code="$?"
  end="$(date +%s%3N)"
  printf 'exit=%s duration_ms=%s\n' "$code" "$((end-start))"
done
'@
		[IO.File]::WriteAllText((Join-Path $script:MatrixArtifacts 'network-diagnostics.txt'),
			$networkDiagnostics.Output, [Text.UTF8Encoding]::new($false))
		$key = New-MatrixEncryptedKey $session $passphrase
		$trust = Join-Path $session.InstanceRoot 'matrix-security-trust.json'

		$rejected = Invoke-MatrixDriver $session $Executable 'first-seen-rejected' $trust $key $passphrase $password `
			@('DriverFailed') @(2) -ConfirmObservedHostKey:$false
		Assert-PristineTarget $session
		Assert-MatrixCondition (-not (Test-Path -LiteralPath $trust)) 'First-seen rejection mutated the trust store.'
		$cases += [ordered]@{ case = 'first-seen-rejection'; status = 'passed'; mutation = 'none'; exit = $rejected.ExitCode }

		[IO.File]::WriteAllText($trust, '{broken', [Text.UTF8Encoding]::new($false))
		$corrupt = Invoke-MatrixDriver $session $Executable 'corrupt-trust-store' $trust $key $passphrase $password `
			@('DriverFailed') @(2) -ConfirmObservedHostKey:$false
		Assert-PristineTarget $session
		$quarantines = @(Get-ChildItem -LiteralPath $session.InstanceRoot -Filter 'matrix-security-trust.json.corrupt-*')
		Assert-MatrixCondition ($quarantines.Count -eq 1) 'Corrupted trust store was not quarantined exactly once.'
		$cases += [ordered]@{ case = 'corrupted-trust-store'; status = 'passed'; quarantined = $true; mutation = 'none' }

		$unsupportedScript = @'
set -eu
sudo cp -a --remove-destination /etc/os-release /run/maxiems-os-release.backup
sudo sed -i 's/^VERSION_ID=.*/VERSION_ID="22.04"/' /etc/os-release
'@
		Invoke-FixtureBashScript $session $unsupportedScript | Out-Null
		$unsupported = Invoke-MatrixDriver $session $Executable 'unsupported-os' $trust $key $passphrase $password `
			@('Failed') @(1) -ConfirmObservedHostKey:$true
		Invoke-FixtureSsh $session 'sudo cp -a --remove-destination /run/maxiems-os-release.backup /etc/os-release' | Out-Null
		Assert-PristineTarget $session
		$cases += [ordered]@{ case = 'unsupported-os-architecture-policy'; status = 'passed'; finalState = $unsupported.Result.status; mutation = 'none' }

		$sudoFaultScript = @'
set -eu
sudo systemd-run --quiet --unit=maxiems-fixture-restore-sudo --on-active=45s /bin/rm -f /etc/sudoers.d/zz-maxiems-fixture-deny
sudo /bin/sh -c 'printf "%s\n" "fixture-admin ALL=(ALL) PASSWD: ALL" > /etc/sudoers.d/zz-maxiems-fixture-deny; chmod 0440 /etc/sudoers.d/zz-maxiems-fixture-deny; visudo -cf /etc/sudoers.d/zz-maxiems-fixture-deny >/dev/null'
'@
		Invoke-FixtureBashScript $session $sudoFaultScript | Out-Null
		$sudoDenied = Invoke-MatrixDriver $session $Executable 'sudo-denied' $trust $key $passphrase $password `
			@('Failed') @(1) -ConfirmObservedHostKey:$true
		Wait-FixtureSudoRestored $session
		Assert-PristineTarget $session
		$cases += [ordered]@{ case = 'sudo-denial'; status = 'passed'; finalState = $sudoDenied.Result.status; mutation = 'none' }

		$baseline = Invoke-MatrixDriver $session $Executable 'confirmed-baseline' $trust $key $passphrase $password `
			@('Succeeded') @(0) -ConfirmObservedHostKey:$true `
			-ManifestPath (Join-Path $script:MatrixHarnessRoot '..\..\fixtures\openems-components.modbus-readonly.v1.json')
		Initialize-MatrixUnmanagedSentinel $session
		$installed = Get-InstalledInvariant $session
		Assert-MatrixCondition ($installed.edgeUser -eq 'openems' -and $installed.capEff -eq '0000000000000400') `
			'Edge privilege policy was invalid after confirmed baseline.'
		$cases += [ordered]@{ case = 'first-seen-confirmation'; status = 'passed'; finalState = $baseline.Result.status; trustPersisted = $true }

		$forbidden = Invoke-FixtureBashScript $session @'
set -eu
if sudo -u maxiems-deploy sudo -n /usr/bin/id -u >/dev/null 2>&1; then exit 81; fi
if sudo -u openems sudo -n /usr/bin/id -u >/dev/null 2>&1; then exit 82; fi
sudo -u maxiems-deploy sudo -n /usr/local/libexec/maxiems-deploy-helper status >/dev/null
test "$(systemctl show maxiems-edge.service -p User --value)" = openems
'@ -AllowFailure
		Assert-MatrixCondition ($forbidden.ExitCode -eq 0) "Forbidden sudo assertion failed: $($forbidden.Output)"
		$cases += [ordered]@{ case = 'forbidden-sudo-and-root-edge'; status = 'passed'; broadSudo = 'rejected'; edgeUser = 'openems' }

		$alternatePort = [int](Get-Random -Minimum 54000 -Maximum 59000)
		$tunnelArgs = "-q -N -i `"$($session.SshKeyPath)`" -o BatchMode=yes -o ExitOnForwardFailure=yes " +
			"-o StrictHostKeyChecking=yes -o UserKnownHostsFile=`"$($session.KnownHostsPath)`" " +
			"-p $($session.Port) -L $alternatePort`:127.0.0.1:22 fixture-admin@127.0.0.1"
		$tunnel = Start-Process -FilePath 'C:\Windows\System32\OpenSSH\ssh.exe' -ArgumentList $tunnelArgs `
			-WindowStyle Hidden -PassThru
		try {
			Start-Sleep -Seconds 2
			$isolated = Invoke-MatrixDriver $session $Executable 'endpoint-port-isolation' $trust $key $passphrase $password `
				@('DriverFailed') @(2) -Port $alternatePort -ConfirmObservedHostKey:$false
			Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
		} finally { if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force } }
		$trustDocument = Get-Content -LiteralPath $trust -Raw | ConvertFrom-Json
		Assert-MatrixCondition (@($trustDocument.hosts).Count -eq 1) 'Alternate endpoint port reused or mutated the primary pin.'
		$cases += [ordered]@{ case = 'endpoint-port-isolation'; status = 'passed'; alternatePort = $alternatePort; mutation = 'none' }

		$oldTrustHash = (Get-FileHash -LiteralPath $trust -Algorithm SHA256).Hash
		$oldKnownHostsHash = (Get-FileHash -LiteralPath $session.KnownHostsPath -Algorithm SHA256).Hash
		$rotate = @'
set -eu
sudo systemd-run --quiet --unit=maxiems-fixture-rotate-host-key --on-active=8s /bin/bash -c 'rm -f /etc/ssh/ssh_host_*; ssh-keygen -A; systemctl restart ssh'
'@
		Invoke-FixtureBashScript $session $rotate -AllowFailure | Out-Null
		Start-Sleep -Seconds 12
		Wait-FixtureSshAfterHostKeyRotation $session $oldKnownHostsHash
		$changedRejected = Invoke-MatrixDriver $session $Executable 'changed-key-rejected' $trust $key $passphrase $password `
			@('DriverFailed') @(2) -ConfirmObservedHostKey:$false
		Assert-MatrixCondition ((Get-FileHash -LiteralPath $trust -Algorithm SHA256).Hash -eq $oldTrustHash) `
			'Changed-key rejection altered the old pin.'
		Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
		$cases += [ordered]@{ case = 'changed-key-rejection'; status = 'passed'; oldPinPreserved = $true }

		$changedAccepted = Invoke-MatrixDriver $session $Executable 'changed-key-replaced' $trust $key $passphrase $password `
			@('Succeeded') @(0) -ConfirmObservedHostKey:$true `
			-ManifestPath (Join-Path $script:MatrixHarnessRoot '..\..\fixtures\openems-components.modbus-readonly.v1.json')
		Assert-InstalledInvariant $session $installed | Out-Null
		$trustDocument = Get-Content -LiteralPath $trust -Raw | ConvertFrom-Json
		Assert-MatrixCondition ($null -ne $trustDocument.hosts[0].replacement) 'Confirmed key replacement lacks audit data.'
		$cases += [ordered]@{ case = 'changed-key-explicit-replacement'; status = 'passed'; replacementAudited = $true; finalState = $changedAccepted.Result.status }

		$sudoers = Get-Content -LiteralPath (Join-Path $script:MatrixHarnessRoot '..\..\remote\etc\sudoers.d\maxiems-deployer') -Raw
		Assert-MatrixCondition (Test-RestrictedSudoersPolicy $sudoers) 'Production sudoers policy is broader than expected.'
		$weakened = $sudoers -replace '(?m)^maxiems-deploy ALL=\(root\) NOPASSWD: MAXIEMS_DEPLOY$', 'maxiems-deploy ALL=(root) NOPASSWD: ALL'
		Assert-MatrixCondition (-not (Test-RestrictedSudoersPolicy $weakened)) 'Sudoers sensitivity control did not detect broad NOPASSWD.'

		$privateMaterial = (Get-Content -LiteralPath $key | Where-Object { $_ -match '^[A-Za-z0-9+/]{20,}={0,2}$' } | Select-Object -First 1)
		$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("Admin:$password"))
		$remoteLogs = Invoke-FixtureBashScript $session @'
sudo journalctl --no-pager -u maxiems-edge.service -u nginx.service -u ssh.service
sudo find /var/log/openems /var/log/nginx -type f -maxdepth 2 -exec sed -n '1,2000p' {} \; 2>/dev/null || true
'@
		[IO.File]::WriteAllText((Join-Path $script:MatrixArtifacts 'remote-logs.txt'), $remoteLogs.Output,
			[Text.UTF8Encoding]::new($false))
		$secrets = @($privateMaterial, $passphrase, $password, $basic, 'fixture-token')
		$matches = Get-TextSecretMatches @($script:MatrixArtifacts) $secrets
		Assert-MatrixCondition (@($matches).Count -eq 0) "Seeded secrets leaked: $($matches -join ', ')"
		$leak = Join-Path $session.InstanceRoot 'sensitivity-leak.txt'
		[IO.File]::WriteAllText($leak, $password, [Text.UTF8Encoding]::new($false))
		$sensitivityMatches = Get-TextSecretMatches @($leak) $secrets
		Assert-MatrixCondition (@($sensitivityMatches).Count -eq 1) 'Secret-scan sensitivity control did not detect a seeded leak.'
		Remove-Item -LiteralPath $leak -Force
		$cases += [ordered]@{ case = 'secret-redaction-and-sensitivity'; status = 'passed'; matches = 0; syntheticLeakDetected = $true; seededKinds = 5 }

		return [ordered]@{
			scenario = 'SecurityMatrix'
			architecture = $TargetArchitecture
			status = 'passed'
			fixtureId = $session.FixtureId
			cases = $cases
			durationSeconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 3)
		}
	} finally {
		if ($session) {
			foreach ($name in @('console.log','qemu.stderr.log','seed-server.stderr.log','session.json')) {
				$source = Join-Path $session.InstanceRoot $name
				if (Test-Path $source) { Copy-Item $source (Join-Path $script:MatrixArtifacts $name) -Force }
			}
		}
		if ($session -and -not $KeepFixture) { Remove-QemuFixture $session }
	}
}

function Invoke-FailureMatrix {
	param(
		[Parameter(Mandatory)][string]$TargetArchitecture,
		[Parameter(Mandatory)][string]$Executable,
		[Parameter(Mandatory)][string]$VmRoot,
		[Parameter(Mandatory)][string]$TemplatesRoot,
		[Parameter(Mandatory)][pscustomobject]$ImageLock,
		[Parameter(Mandatory)][string]$CacheDirectory,
		[Parameter(Mandatory)][string]$ArtifactsDirectory,
		[switch]$KeepFixture
	)
	$session = $null
	$started = [DateTimeOffset]::UtcNow
	$script:MatrixArtifacts = Join-Path $ArtifactsDirectory "$TargetArchitecture-failure-matrix"
	New-Item -ItemType Directory -Force $script:MatrixArtifacts | Out-Null
	$cases = @()
	$password = 'E2E-Only-Admin-Password!42'
	$passphrase = 'Matrix-Key-Passphrase!72'
	try {
		$session = New-QemuFixture -Architecture $TargetArchitecture -VmRoot $VmRoot `
			-TemplatesRoot $TemplatesRoot -ImageLock $ImageLock -CacheDirectory $CacheDirectory
		$bootMinutes = if ($TargetArchitecture -eq 'arm64') { 12 } else { 4 }
		Wait-QemuFixtureReady $session ([TimeSpan]::FromMinutes($bootMinutes))
		Assert-FixtureMarker $session | Out-Null
		$key = New-MatrixEncryptedKey $session $passphrase
		$trust = Join-Path $session.InstanceRoot 'matrix-failure-trust.json'
		$manifest = Join-Path $script:MatrixHarnessRoot '..\..\fixtures\openems-components.modbus-readonly.v1.json'
		$baseline = Invoke-MatrixDriver $session $Executable 'failure-baseline' $trust $key $passphrase $password `
			@('Succeeded') @(0)
		Initialize-MatrixUnmanagedSentinel $session
		$installed = Get-InstalledInvariant $session
		$cases += [ordered]@{ case = 'baseline-checkpoint'; status = 'passed'; finalState = $baseline.Result.status }

		$faultRoot = Join-Path $script:MatrixHarnessRoot '..\..\artifacts\e2e'
		foreach ($fault in @(
			@('local-checksum-mismatch','fault-ChecksumMismatch.zip'),
			@('traversal-zip-manifest','fault-TraversalManifest.zip'))) {
			$result = Invoke-MatrixDriver $session $Executable $fault[0] $trust $key $passphrase $password `
				@('DriverFailed') @(2) -PayloadPath (Join-Path $faultRoot $fault[1])
			Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
			$cases += [ordered]@{ case = $fault[0]; status = 'passed'; mutation = 'none'; exit = $result.ExitCode }
		}

		$helperAdversarial = Invoke-FixtureBashScript $session @'
set -eu
helper='sudo -u maxiems-deploy sudo -n /usr/local/libexec/maxiems-deploy-helper'
if $helper begin 'bad;touch-/tmp/maxiems-injected' >/dev/null 2>&1; then exit 71; fi
test ! -e /tmp/maxiems-injected
sudo -u maxiems-deploy ln -s /etc/passwd /var/lib/maxiems-deploy/incoming/.upload-symlink
$helper begin 20260720010101-aaaaaaaaaaaa
if $helper stage 20260720010101-aaaaaaaaaaaa .upload-symlink aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa >/dev/null 2>&1; then exit 72; fi
$helper abort 20260720010101-aaaaaaaaaaaa >/dev/null 2>&1 || test ! -e /run/maxiems-deploy-transaction
sudo -u maxiems-deploy rm -f /var/lib/maxiems-deploy/incoming/.upload-symlink
$helper begin 20260720010101-aaaaaaaaaaaa
if $helper begin 20260720010102-bbbbbbbbbbbb >/dev/null 2>&1; then exit 73; fi
$helper abort 20260720010101-aaaaaaaaaaaa >/dev/null 2>&1 || test ! -e /run/maxiems-deploy-transaction
test ! -e /run/maxiems-deploy-transaction
'@ -AllowFailure
		Assert-MatrixCondition ($helperAdversarial.ExitCode -eq 0) "Helper adversarial checks failed: $($helperAdversarial.Output)"
		Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
		$cases += [ordered]@{ case = 'remote-checksum-injection-symlink-concurrent-lock'; status = 'passed'; mutation = 'none' }

		$prerequisiteSource = Get-Content -LiteralPath `
			(Join-Path $script:MatrixHarnessRoot '..\..\remote\usr\local\libexec\maxiems-prerequisite-ops') -Raw
		foreach ($needle in @('DPkg::Lock::Timeout=120','curl --fail','gpgv --keyring','verify_hash')) {
			Assert-MatrixCondition ($prerequisiteSource.IndexOf($needle, [StringComparison]::Ordinal) -ge 0) `
				"Prerequisite failure boundary is missing '$needle'."
		}
		$cases += [ordered]@{ case = 'apt-lock-source-signature-guards'; status = 'passed'; productionBoundary = 'inspected' }

		foreach ($tailscale in @(@('tailscale-timeout','timeout'), @('tailscale-cancel','cancel'))) {
			$result = Invoke-MatrixDriver $session $Executable $tailscale[0] $trust $key $passphrase $password `
				@('Failed','Canceled') @(1) -ConfigureTailscale:$true -SimulateTailscale:$true `
				-SimulatedTailscaleOutcome $tailscale[1] -TailscaleTimeoutMilliseconds 150
			Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
			$cases += [ordered]@{ case = $tailscale[0]; status = 'passed'; finalState = $result.Result.status; rolledBack = $true }
		}

		$wrongAuth = Invoke-MatrixDriver $session $Executable 'jsonrpc-wrong-auth' $trust $key $passphrase `
			'Wrong-Admin-Password!84' @('Failed') @(1) -ManifestPath $manifest
		Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
		$cases += [ordered]@{ case = 'jsonrpc-auth-partial-mutation'; status = 'passed'; finalState = $wrongAuth.Result.status; unmanagedPreserved = $true }

		foreach ($fault in @(
			@('nginx-invalid-config','fault-NginxInvalid.zip'),
			@('edge-crash','fault-EdgeCrash.zip'))) {
			$result = Invoke-MatrixDriver $session $Executable $fault[0] $trust $key $passphrase $password `
				@('Failed','RolledBack') @(1) -PayloadPath (Join-Path $faultRoot $fault[1]) -ManifestPath $manifest
			Wait-InstalledInvariant $session $installed ([TimeSpan]::FromMinutes(2)) | Out-Null
			$cases += [ordered]@{ case = $fault[0]; status = 'passed'; finalState = $result.Result.status; rollbackVerified = $true }
		}

		$recoverySource = Get-Content -LiteralPath `
			(Join-Path $script:MatrixHarnessRoot '..\..\src\Maxiems.Deployer.Infrastructure\Deployment\DeploymentEngine.cs') -Raw
		Assert-MatrixCondition ($recoverySource.IndexOf('Options(request, "maxiems-deploy")', [StringComparison]::Ordinal) -ge 0) `
			'Lost-SSH recovery reconnect boundary is absent.'
		$networkSource = Get-Content -LiteralPath `
			(Join-Path $script:MatrixHarnessRoot '..\..\remote\usr\local\libexec\maxiems-network-ops') -Raw
		Assert-MatrixCondition ($networkSource.IndexOf('rollback_network "$transaction_id"', [StringComparison]::Ordinal) -ge 0) `
			'Network apply rollback boundary is absent.'
		$cases += [ordered]@{ case = 'lost-ssh-and-network-rollback-boundaries'; status = 'passed'; productionBoundary = 'inspected' }

		return [ordered]@{ scenario = 'FailureMatrix'; architecture = $TargetArchitecture; status = 'passed';
			fixtureId = $session.FixtureId; cases = $cases;
			durationSeconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 3) }
	} finally {
		if ($session) {
			foreach ($name in @('console.log','qemu.stderr.log','seed-server.stderr.log','session.json')) {
				$source = Join-Path $session.InstanceRoot $name
				if (Test-Path $source) { Copy-Item $source (Join-Path $script:MatrixArtifacts $name) -Force }
			}
		}
		if ($session -and -not $KeepFixture) { Remove-QemuFixture $session }
	}
}
