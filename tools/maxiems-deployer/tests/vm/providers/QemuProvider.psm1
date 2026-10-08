Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-QemuHome {
	[CmdletBinding()]
	param()

	$candidates = @(
		$env:MAXIEMS_QEMU_HOME,
		(Join-Path $env:TEMP 'maxiems-qemu'),
		'C:\Program Files\qemu'
	) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
	foreach ($candidate in $candidates) {
		if (Test-Path (Join-Path $candidate 'qemu-img.exe')) { return (Resolve-Path $candidate).Path }
	}
	throw 'QEMU was not found. Set MAXIEMS_QEMU_HOME to a verified QEMU for Windows installation.'
}

function Get-FreeTcpPort {
	$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
	$listener.Start()
	try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Assert-Sha256 {
	param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Expected)
	$actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
	if ($actual -ne $Expected.ToLowerInvariant()) {
		throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual."
	}
}

function Get-LockedImage {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)][pscustomobject]$Lock,
		[Parameter(Mandatory)][ValidateSet('amd64', 'arm64')][string]$Architecture,
		[Parameter(Mandatory)][string]$CacheDirectory
	)

	$image = $Lock.images.$Architecture
	New-Item -ItemType Directory -Force $CacheDirectory | Out-Null
	$path = Join-Path $CacheDirectory $image.file
	if (-not (Test-Path $path)) {
		$temporary = "$path.download"
		try {
			& curl.exe -L --fail --output $temporary "$($Lock.baseUrl)/$($image.file)"
			if ($LASTEXITCODE -ne 0) { throw "Ubuntu image download failed with exit $LASTEXITCODE." }
			Assert-Sha256 $temporary $image.sha256
			Move-Item -LiteralPath $temporary -Destination $path
		} finally {
			if (Test-Path $temporary) { Remove-Item -LiteralPath $temporary }
		}
	}
	Assert-Sha256 $path $image.sha256
	return $path
}

function Protect-FixturePrivateKey {
	param([Parameter(Mandatory)][string]$Path)
	$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
	& icacls.exe $Path '/inheritance:r' '/grant:r' "*$sid`:(F)" | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "Restricting fixture key ACL failed with exit $LASTEXITCODE." }
}

function New-FixtureSshKey {
	param([Parameter(Mandatory)][string]$Path)
	$arguments = "-q -t ed25519 -N `"`" -C maxiems-fixture -f `"$Path`""
	$processParameters = @{
		FilePath = 'C:\Windows\System32\OpenSSH\ssh-keygen.exe'
		ArgumentList = $arguments
		WindowStyle = 'Hidden'
		Wait = $true
		PassThru = $true
	}
	$process = Start-Process @processParameters
	if ($process.ExitCode -ne 0) { throw "ssh-keygen failed with exit $($process.ExitCode)." }
	Protect-FixturePrivateKey $Path
}

function New-QemuFixture {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)][ValidateSet('amd64', 'arm64')][string]$Architecture,
		[Parameter(Mandatory)][string]$VmRoot,
		[Parameter(Mandatory)][string]$TemplatesRoot,
		[Parameter(Mandatory)][pscustomobject]$ImageLock,
		[Parameter(Mandatory)][string]$CacheDirectory
	)

	$qemuHome = Resolve-QemuHome
	$fixtureId = [Guid]::NewGuid().ToString('N')
	$instanceRoot = Join-Path $VmRoot "$Architecture-$fixtureId"
	$session = $null
	try {
	$seedRoot = Join-Path $instanceRoot 'seed'
	New-Item -ItemType Directory -Force $seedRoot | Out-Null
	$keyPath = Join-Path $instanceRoot 'fixture-admin-ed25519'
	New-FixtureSshKey $keyPath
	$publicKey = (Get-Content "$keyPath.pub" -Raw).Trim()
	$replacements = @{
		'__FIXTURE_ID__' = $fixtureId
		'__ARCHITECTURE__' = $Architecture
		'__ADMIN_PUBLIC_KEY__' = $publicKey
	}
	foreach ($templateName in @('meta-data.template.yaml', 'user-data.template.yaml')) {
		$content = Get-Content (Join-Path $TemplatesRoot $templateName) -Raw
		foreach ($replacement in $replacements.GetEnumerator()) {
			$content = $content.Replace($replacement.Key, $replacement.Value)
		}
		$destinationName = $templateName.Replace('.template', '')
		[System.IO.File]::WriteAllText((Join-Path $seedRoot $destinationName), $content,
			[System.Text.UTF8Encoding]::new($false))
	}
	$baseImage = Get-LockedImage $ImageLock $Architecture $CacheDirectory
	$overlay = Join-Path $instanceRoot 'fixture.qcow2'
	$qemuImg = Join-Path $qemuHome 'qemu-img.exe'
	& $qemuImg create -q -f qcow2 -F qcow2 -b $baseImage $overlay
	if ($LASTEXITCODE -ne 0) { throw "qemu-img overlay creation failed with exit $LASTEXITCODE." }
	& $qemuImg resize $overlay 16G | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "qemu-img resize failed with exit $LASTEXITCODE." }
	$firmwareCode = if ($Architecture -eq 'amd64') {
		Join-Path $qemuHome 'share\edk2-x86_64-code.fd'
	} else {
		Join-Path $qemuHome 'share\edk2-aarch64-code.fd'
	}
	$firmwareVarsSource = if ($Architecture -eq 'amd64') {
		Join-Path $qemuHome 'share\edk2-i386-vars.fd'
	} else {
		Join-Path $qemuHome 'share\edk2-arm-vars.fd'
	}
	$firmwareVars = Join-Path $instanceRoot 'firmware-vars.fd'
	Copy-Item -LiteralPath $firmwareVarsSource -Destination $firmwareVars
	$session = [pscustomobject]@{
		Architecture = $Architecture
		ExpectedArchitecture = $ImageLock.images.$Architecture.expectedDpkgArchitecture
		FixtureId = $fixtureId
		VmRoot = [System.IO.Path]::GetFullPath($VmRoot)
		InstanceRoot = $instanceRoot
		OverlayPath = $overlay
		SeedRoot = $seedRoot
		SeedPort = Get-FreeTcpPort
		SeedToken = [Guid]::NewGuid().ToString('N')
		SeedServerProcess = $null
		SeedServerReadyPath = Join-Path $instanceRoot 'seed-server.ready'
		SshKeyPath = $keyPath
		KnownHostsPath = Join-Path $instanceRoot 'known_hosts'
		Port = Get-FreeTcpPort
		HttpPort = Get-FreeTcpPort
		ModbusPort = Get-FreeTcpPort
		QemuHome = $qemuHome
		QemuExecutable = Join-Path $qemuHome $ImageLock.images.$Architecture.qemuSystem
		QemuImg = $qemuImg
		FirmwareCode = $firmwareCode
		FirmwareVars = $firmwareVars
		FirmwareVarsBaseline = Join-Path $instanceRoot 'firmware-vars-provisioned.fd'
		Process = $null
		SnapshotName = 'provisioned'
		ConsoleLog = Join-Path $instanceRoot 'console.log'
	}
	$sessionMetadata = [ordered]@{
		fixtureId = $session.FixtureId
		architecture = $session.Architecture
		sshPort = $session.Port
		httpPort = $session.HttpPort
		modbusPort = $session.ModbusPort
		seedPort = $session.SeedPort
	}
	[System.IO.File]::WriteAllText((Join-Path $instanceRoot 'session.json'),
		($sessionMetadata | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
	Start-SeedServer $session
	Start-QemuFixture $session
	return $session
	} catch {
		if ($session) {
			Remove-QemuFixture $session
		} elseif (Test-Path $instanceRoot) {
			Remove-Item -LiteralPath $instanceRoot -Recurse -Force
		}
		throw
	}
}

function Start-SeedServer {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$serverScript = Join-Path (Split-Path $PSScriptRoot -Parent) 'SeedServer.ps1'
	$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$serverScript`" -Root `"$($Session.SeedRoot)`" " +
		"-Port $($Session.SeedPort) -Token $($Session.SeedToken) -ReadyFile `"$($Session.SeedServerReadyPath)`""
	$processParameters = @{
		FilePath = 'powershell.exe'
		ArgumentList = $arguments
		WindowStyle = 'Hidden'
		RedirectStandardOutput = Join-Path $Session.InstanceRoot 'seed-server.stdout.log'
		RedirectStandardError = Join-Path $Session.InstanceRoot 'seed-server.stderr.log'
		PassThru = $true
	}
	$Session.SeedServerProcess = Start-Process @processParameters
	$deadline = [DateTimeOffset]::UtcNow.AddSeconds(10)
	while (-not (Test-Path $Session.SeedServerReadyPath)) {
		if ($Session.SeedServerProcess.HasExited) { throw 'NoCloud seed server exited before becoming ready.' }
		if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'NoCloud seed server readiness timed out.' }
		Start-Sleep -Milliseconds 100
	}
}

function Stop-SeedServer {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)
	if ($Session.SeedServerProcess -and -not $Session.SeedServerProcess.HasExited) {
		Stop-Process -Id $Session.SeedServerProcess.Id -Force
		$Session.SeedServerProcess.WaitForExit()
	}
	if (Test-Path $Session.SeedServerReadyPath) { Remove-Item -LiteralPath $Session.SeedServerReadyPath -Force }
}

function Start-QemuFixture {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)

	if ($Session.Process -and -not $Session.Process.HasExited) { throw 'Fixture process is already running.' }
	$netdev = "user,id=net0,hostfwd=tcp:127.0.0.1:$($Session.Port)-:22," +
		"hostfwd=tcp:127.0.0.1:$($Session.HttpPort)-:80," +
		"hostfwd=tcp:127.0.0.1:$($Session.ModbusPort)-:502"
	$arguments = @('-name', "maxiems-$($Session.FixtureId)", '-m', '2048', '-smp', '2',
		'-display', 'none', '-no-reboot', '-serial', "file:$($Session.ConsoleLog)",
		'-smbios', "type=1,serial=ds=nocloud;s=http://10.0.2.2:$($Session.SeedPort)/$($Session.SeedToken)/",
		'-drive', "if=pflash,format=raw,unit=0,readonly=on,file=$($Session.FirmwareCode)",
		'-drive', "if=pflash,format=raw,unit=1,file=$($Session.FirmwareVars)",
		'-drive', "file=$($Session.OverlayPath),if=virtio,format=qcow2,cache=writeback",
		'-netdev', $netdev)
	if ($Session.Architecture -eq 'amd64') {
		$arguments += @('-machine', 'q35,pic=off', '-accel', 'whpx', '-device', 'virtio-net-pci,netdev=net0')
	} else {
		$arguments += @('-machine', 'virt,accel=tcg', '-cpu', 'cortex-a72',
			'-device', 'virtio-net-device,netdev=net0')
	}
	$processParameters = @{
		FilePath = $Session.QemuExecutable
		ArgumentList = $arguments
		WindowStyle = 'Hidden'
		RedirectStandardOutput = Join-Path $Session.InstanceRoot 'qemu.stdout.log'
		RedirectStandardError = Join-Path $Session.InstanceRoot 'qemu.stderr.log'
		PassThru = $true
	}
	$Session.Process = Start-Process @processParameters
}

function Invoke-FixtureSsh {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)][pscustomobject]$Session,
		[Parameter(Mandatory)][string]$Command,
		[string]$Username = 'fixture-admin',
		[switch]$TrustOnFirstUse,
		[switch]$AllowFailure
	)
	$hostKeyPolicy = if ($TrustOnFirstUse) { 'accept-new' } else { 'yes' }
	$args = @('-i', $Session.SshKeyPath, '-p', [string]$Session.Port, '-o', 'BatchMode=yes',
		'-o', 'IdentitiesOnly=yes', '-o', 'ConnectTimeout=10', '-o', "StrictHostKeyChecking=$hostKeyPolicy",
		'-o', "UserKnownHostsFile=$($Session.KnownHostsPath)",
		"$Username@127.0.0.1", '--', $Command)
	$previousPreference = $ErrorActionPreference
	try {
		$ErrorActionPreference = 'Continue'
		$output = & 'C:\Windows\System32\OpenSSH\ssh.exe' @args 2>&1
		$code = $LASTEXITCODE
	} finally {
		$ErrorActionPreference = $previousPreference
	}
	if ($code -ne 0 -and -not $AllowFailure) { throw "Fixture SSH failed with exit $code`: $output" }
	return [pscustomobject]@{ ExitCode = $code; Output = ($output -join "`n") }
}

function Wait-QemuFixtureReady {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session, [TimeSpan]$Timeout = [TimeSpan]::FromMinutes(8))

	$attemptDeadline = [DateTimeOffset]::UtcNow + $Timeout
	$lastSshFailure = 'SSH was not attempted.'
	$timerPanicRestarts = 0
	while ($true) {
		$timerPanic = (Test-Path $Session.ConsoleLog) -and
			[bool](Select-String -LiteralPath $Session.ConsoleLog -SimpleMatch `
				'Kernel panic - not syncing: IO-APIC + timer doesn''t work!' -Quiet)
		if ($Session.Architecture -eq 'amd64' -and $timerPanic) {
			if ($timerPanicRestarts -ge 4) { throw 'QEMU x86 timer panic persisted after four clean boot retries.' }
			$timerPanicRestarts++
			if (-not $Session.Process.HasExited) {
				$Session.Process.Kill()
				$Session.Process.WaitForExit()
			}
			Copy-Item -LiteralPath $Session.ConsoleLog -Destination `
				(Join-Path $Session.InstanceRoot "console-timer-panic-$timerPanicRestarts.log") -Force
			Remove-Item -LiteralPath $Session.ConsoleLog -Force
			Start-QemuFixture $Session
			$attemptDeadline = [DateTimeOffset]::UtcNow + $Timeout
			continue
		}
		if ([DateTimeOffset]::UtcNow -ge $attemptDeadline) {
			throw "Fixture did not become ready before $attemptDeadline. Last SSH result: $lastSshFailure"
		}
		if ($Session.Process.HasExited) {
			throw "QEMU exited early with $($Session.Process.ExitCode). See $($Session.ConsoleLog)."
		}
		if (Test-Path $Session.KnownHostsPath) { Remove-Item -LiteralPath $Session.KnownHostsPath -Force }
		$result = Invoke-FixtureSsh -Session $Session `
			-Command 'test -f /etc/maxiems-fixture/marker.json -a -f /var/log/maxiems-fixture-audit.log' `
			-TrustOnFirstUse -AllowFailure
		if ($result.ExitCode -eq 0) { return }
		$lastSshFailure = "exit=$($result.ExitCode) $($result.Output)"
		[System.IO.File]::WriteAllText((Join-Path $Session.InstanceRoot 'ssh-attempt.log'), $lastSshFailure,
			[System.Text.UTF8Encoding]::new($false))
		Start-Sleep -Seconds 3
	}
}

function Assert-FixtureMarker {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)
	$result = Invoke-FixtureSsh $Session 'cat /etc/maxiems-fixture/marker.json' -AllowFailure
	if ($result.ExitCode -ne 0) { throw 'Target is not a marked MAXiEMS VM fixture; refusing mutation.' }
	try { $marker = $result.Output | ConvertFrom-Json } catch { throw 'Fixture marker is invalid; refusing mutation.' }
	if ($marker.schemaVersion -ne 1 -or $marker.fixtureId -ne $Session.FixtureId -or $marker.provider -ne 'qemu') {
		throw 'Fixture marker does not match the active session; refusing mutation.'
	}
	return $marker
}

function Save-QemuFixtureSnapshot {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	Stop-QemuFixture $Session
	& $Session.QemuImg snapshot -c $Session.SnapshotName $Session.OverlayPath
	if ($LASTEXITCODE -ne 0) { throw "Creating QEMU snapshot failed with exit $LASTEXITCODE." }
	Copy-Item -LiteralPath $Session.FirmwareVars -Destination $Session.FirmwareVarsBaseline -Force
	Start-QemuFixture $Session
	$bootTimeoutMinutes = if ($Session.Architecture -eq 'arm64') { 8 } else { 3 }
	Wait-QemuFixtureReady $Session ([TimeSpan]::FromMinutes($bootTimeoutMinutes))
}

function Reset-QemuFixtureSnapshot {
	param([Parameter(Mandatory)][pscustomobject]$Session)
	Stop-QemuFixture $Session
	& $Session.QemuImg snapshot -a $Session.SnapshotName $Session.OverlayPath
	if ($LASTEXITCODE -ne 0) { throw "Resetting QEMU snapshot failed with exit $LASTEXITCODE." }
	Copy-Item -LiteralPath $Session.FirmwareVarsBaseline -Destination $Session.FirmwareVars -Force
	Start-QemuFixture $Session
	$bootTimeoutMinutes = if ($Session.Architecture -eq 'arm64') { 8 } else { 3 }
	Wait-QemuFixtureReady $Session ([TimeSpan]::FromMinutes($bootTimeoutMinutes))
}

function Stop-QemuFixture {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)
	if (-not $Session.Process -or $Session.Process.HasExited) { return }
	try { Invoke-FixtureSsh $Session 'sudo poweroff' -AllowFailure | Out-Null } catch { }
	if (-not $Session.Process.WaitForExit(30000)) {
		$Session.Process.Kill()
		$Session.Process.WaitForExit()
	}
}

function Remove-QemuFixture {
	[CmdletBinding()]
	param([Parameter(Mandatory)][pscustomobject]$Session)
	Stop-QemuFixture $Session
	Stop-SeedServer $Session
	$resolvedRoot = [System.IO.Path]::GetFullPath($Session.InstanceRoot)
	$resolvedVmRoot = [System.IO.Path]::GetFullPath($Session.VmRoot).TrimEnd(
		[System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
	if (-not $resolvedRoot.StartsWith($resolvedVmRoot + [System.IO.Path]::DirectorySeparatorChar,
		[System.StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove fixture outside VM root.' }
	Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}

Export-ModuleMember -Function Resolve-QemuHome, New-QemuFixture, Wait-QemuFixtureReady,
	Invoke-FixtureSsh, Assert-FixtureMarker, Save-QemuFixtureSnapshot, Reset-QemuFixtureSnapshot,
	Stop-QemuFixture, Remove-QemuFixture, Protect-FixturePrivateKey
