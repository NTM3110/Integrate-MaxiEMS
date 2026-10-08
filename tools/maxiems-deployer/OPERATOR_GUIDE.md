# MAXiEMS Deployer operator guide

This guide covers the single-device Windows installer for a marked Ubuntu 24.04 Edge host. It does not deploy a fleet,
OpenEMS Backend, HTTPS certificates, exit-node routing, or arbitrary root commands.

## Phase 1 manufacturing workflow

The normal engineer path is **Deployments** and does not require a source repository, SSH key path, component manifest,
Java, Node, Gradle, or .NET installation:

1. Select an **Approved** Manufacturing Profile.
2. Enter the target IP/host, verify the displayed SSH fingerprint, and deliberately confirm it.
3. Enter the temporary Ubuntu SSH password and a non-empty current/new OpenEMS administrator password.
4. Run the read-only inspection. No target mutation occurs in this step.
5. Confirm which detected Linux interface is physical LAN1 and LAN2. The profile marks the serial-source port; the app
   derives `MR` + its uppercase permanent MAC and checks local uniqueness.
6. Review CPU, RAM, storage, OS/image, both Ethernet identities, and required/optional/forbidden module evidence.
7. Review the classified action (`Deploy`, `Update`, `NoChange`, or `LegacyMigration`) and run it only when every required
   gate passes.
8. The app verifies service startup/enabling, nginx/UI, authenticated OpenEMS readiness, bindings, installed release
   hashes, legacy shutdown, and—when required by the profile—reboot persistence. Only then is the unit
   `ReadyForWarehouse`.

`NoChange` is a verified success when the installed release already matches; it is not a deployment error. Manual FAT is
deferred and is not claimed by `ReadyForWarehouse`.

The local manufacturing database and reports are under `%LOCALAPPDATA%\MAXiEMS\Deployer`. In **Settings**, configure an
explicit production backup destination, create a verified `.mxbak`, and export profiles/device history when required.
Restore is an Advanced action: always run Preview first; the app validates archive hash, SQLite integrity, and schema and
retains a pre-restore database. The app never stores or exports Ubuntu/OpenEMS passwords. Store the unique OpenEMS password
manually in the approved company password manager under the derived SN.

## Release contents and verification

A release directory contains exactly one runnable program, `Maxiems.Deployer.exe`, plus:

- `Maxiems.Deployer.exe.sha256` — SHA-256 checksum;
- `release-metadata.json` and `release-status.txt` — version, source revision, payload hash, and signed/unsigned state;
- `sbom.spdx.json` — SPDX 2.3 dependency inventory;
- `deployment-report.template.json` and `.html` — report format examples.

Run this before copying the EXE to an operator workstation:

```powershell
.\verify-release.ps1 -ReleaseDirectory .\artifacts\publish
```

An unsigned build is labeled `UNSIGNED INTERNAL BUILD`. Do not claim it is signed. For a signed build, also compare the
certificate publisher/thumbprint with your release-channel record. The EXE is self-contained for Windows 10/11 x64 and
does not require a separately installed .NET runtime.

## Target prerequisites

- A dedicated Ubuntu 24.04 amd64 or arm64 host marked for MAXiEMS deployment.
- SSH reachable from Windows, normally TCP 22. A new device may use its bootstrap account password for one-time pairing.
- The bootstrap account may use password authentication for pairing, or public-key authentication for later runs.
- Bootstrap user permission to run the narrowly defined bootstrap installer through `sudo`.
- Outbound target access for pinned Java, Node, nginx and Tailscale prerequisites when not already installed.
- A non-empty new OpenEMS administrator password. Do not reuse a repository default password.

The deployer reads and pins the public host key before sending any credential. In worker mode it uses the temporary SSH
password only for initial login and `sudo`, installs an app-managed public key, and then reconnects through the restricted
`maxiems-deploy` key identity. The temporary password is not saved.

## Acquire and compare the SSH fingerprint

Obtain the expected fingerprint through a channel independent of the network path being deployed, for example the
device console or a trusted provisioning record. On the Ubuntu console, an administrator can run:

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Record the hostname/IP, SSH port, key algorithm, and complete SHA-256 fingerprint together. In the wizard, **Connect
safely** performs only a host-key probe. Compare the displayed endpoint and fingerprint before confirming.

First contact is not silently trusted. Rejecting it leaves the target untouched. If the key changes, the wizard shows
both old and new fingerprints and preserves the old pin until **Confirm and replace trust** is explicitly acknowledged.
Treat an unexplained change as a possible interception. After an intentional device reinstall, compare the new console
fingerprint and device identity with the old provisioning record before replacing the pin. Trust changes are audited.

Local trust is stored at `%LOCALAPPDATA%\MAXiEMS\Deployer\ssh-trust.json`, protected for the current Windows user.

## Wizard fields

1. **Target device**
   - **Host**: marked Ubuntu hostname, IP address, or Tailscale address.
   - **Port**: SSH endpoint port, default `22`. Pins are isolated by host and port.
2. **Confirm SSH identity**
   - Verify endpoint, algorithm, old fingerprint, and new fingerprint.
   - Select the risk acknowledgement only after out-of-band comparison, then confirm or reject.
3. **Device access**
   - **SSH username**: bootstrap account, default `admin`; after provisioning the deployer reconnects as
     `maxiems-deploy`.
   - **Temporary SSH setup password**: enter it for the first deployment from this Windows user; leave it blank after
     pairing. It is also supplied to `sudo` through standard input and is never placed on a command line.
   - **OpenEMS administrator password**: new password used for the first bootstrap and JSON-RPC reconciliation.
   - **Advanced technician mode**: exposes a manually supplied private-key path and optional key passphrase.
4. **Installation profile**
   - Standard MAXiEMS requires no manifest path. Select the built-in read-only Modbus TCP profile only when needed.
   - Advanced technician mode exposes an optional custom schema-version-1 manifest path.
   - **Enable interactive Tailscale login** joins the host as a normal node only.
5. **Review deployment**
   - Recheck target, SSH user, manifest, and Tailscale selection. Secrets are deliberately omitted.
6. **Deployment in progress**
   - Follow the phase timeline. If Tailscale is enabled, open or copy the displayed login URL and approve the correct
     device in the browser. **Cancel safely** requests rollback; do not power off the target while rollback runs.
7. **Deployment result/history**
   - Open history to inspect redacted JSON and HTML reports. A failed or rolled-back result is not a success.

Password controls are cleared after deployment. The deployer does not persist the temporary SSH password, technician
key passphrase, OpenEMS password, Basic authorization value, or Tailscale login token. Its generated RSA-3072 private key
is encrypted; the random key passphrase is protected for the current Windows user with Windows DPAPI under
`%LOCALAPPDATA%\MAXiEMS\Deployer\keys`.

## Component manifest v1

Every full BMS payload includes `components/managed-components.v1.json`. The normal deployment loads it automatically,
so no command-line manifest is needed to migrate the deployer-owned REST/WebSocket endpoint settings. These bundled
entries are `updateOnly`: a missing component is not created, and no String, TA/TC, calculator, or Modbus TCP feature is
seeded. Build a full release after changing the bundled manifest; a CLI-only publish cannot add it to an older payload.

An optional external manifest remains the extension point for additional bundle/component initialization. It is merged
with the bundled manifest and cannot claim the same component ID. Manifests are schema validated before target mutation
and reconciled through supported OpenEMS JSON-RPC create/update/get/read-back operations. Only listed properties are
managed; other operator/custom properties and unmanaged components are preserved. `updateOnly` requires an explicit ID.

```json
{
  "schemaVersion": 1,
  "components": [
    {
      "factoryPid": "Controller.Api.ModbusTcp.ReadOnly",
      "id": "ctrlApiModbusTcp0",
      "properties": {
        "alias": "Modbus/TCP Read-Only",
        "enabled": true,
        "port": 502,
        "component.ids": ["_sum"]
      }
    }
  ]
}
```

The maintained example is `fixtures/openems-components.modbus-readonly.v1.json`; the schema is
`schemas/openems-components.v1.schema.json`. Duplicate IDs, unknown fields, unsafe property names, invalid types, and
unsupported schema versions are rejected.

## Network services after deployment

| Port | Bind/exposure | Purpose |
| --- | --- | --- |
| 22/tcp | device network | SSH bootstrap and restricted deployment transport |
| 80/tcp | device network | nginx static UI and reverse proxy |
| 502/tcp | device network, when manifest enables it | Modbus/TCP read-only controller |
| 8080/tcp | device network | Felix Web Console |
| 8084/tcp | loopback only | OpenEMS REST and JSON-RPC behind nginx |
| 8085/tcp | device network | OpenEMS BMS WebSocket; nginx also proxies `/websocket` |

Use `http://<device>/` for the UI, `/rest/` for REST, `/jsonrpc` for JSON-RPC, and `/websocket` for WebSocket. HTTPS is
not configured by this tool. Felix Web Console is available at
`http://<device>:8080/system/console`; its authentication is separate from the OpenEMS management account.
Port `8084` remains private and must be accessed through nginx or the deployer's SSH tunnel.

Tailscale runs `tailscale up` with accepted routes disabled, no advertised routes, and no exit-node role. The installer
does not change IPv4/IPv6 forwarding.

## Remote layout and privilege boundary

- `/opt/maxiems/releases/<release-id>` — immutable verified releases.
- `/opt/maxiems/current` and `/opt/maxiems/previous` — atomic release symlinks.
- `/var/lib/maxiems/shared/config` — persistent managed OpenEMS configuration.
- `/var/lib/openems` and `/var/log/openems` — persistent data/cache and service logs.
- `/var/lib/maxiems/state/deployments` — deployment state and rollback evidence.
- `/var/lib/maxiems/network-transactions` — network transaction backups/evidence.
- `/var/lib/maxiems-deploy/incoming` — restricted upload staging.
- `/run/maxiems-deploy-transaction` — active deployment lock.

OpenEMS runs as `openems`, not root, with only `CAP_NET_BIND_SERVICE`. The SSH identity `maxiems-deploy` can invoke only
allow-listed root-owned helpers; it has no general shell or broad sudo permission.

## Reports, history, and rollback

Windows history, reports, and trust records live under `%LOCALAPPDATA%\MAXiEMS\Deployer`:

- `history.json` — completed deployment index;
- `reports\deployment-<id>.json` — machine-readable redacted evidence;
- `reports\deployment-<id>.html` — operator-readable evidence;
- `ssh-trust.json` — endpoint-specific SSH pins and replacement audit.

Every report identifies the verified payload hash, phases, checks, host-trust decision, and rollback proof. A post-
activation failure restores the prior healthy release and managed component checkpoint. A failed first installation
removes the staged/active partial state. Shared data and unmanaged component configuration remain intact.

## Upgrade and uninstall boundaries

To upgrade, publish a new verified release and run the wizard against the same endpoint. Deployment is idempotent when
the release ID is unchanged. A changed release is staged, hash checked, activated atomically, health checked, and only
then committed; the prior release remains the rollback target.

When a legacy `openems.service` owns the OpenEMS API or Modbus ports, the deployment treats it as a controlled migration.
Inside the release transaction it records the legacy service state, stops and disables the service, and backs up/removes
the recognized `maxicom` nginx enablement link. A failed deployment restores the legacy nginx link and the exact prior
enabled/active service state; a successful deployment leaves the legacy service disabled without deleting its unit or data.

There is no automatic uninstall button. Uninstall is an explicit maintenance operation because shared OpenEMS data and
configuration may be operational records. Back up `/var/lib/maxiems/shared`, `/var/lib/openems`, reports, and trust
records first. Stop/disable `maxiems-edge` and nginx integration, remove only MAXiEMS-managed unit/sudoers/helper/release
paths, and decide separately whether retained data may be deleted. Never remove unrelated nginx sites, users, components,
or network configuration. Reinstalling the OS requires the changed-key procedure above.

## Troubleshooting

- **Host-key rejected/changed**: verify host and port, then obtain the current fingerprint from the device console. Do
  not delete the trust file merely to bypass a mismatch.
- **cloud-init datasource not found in a test VM**: the temporary VM did not see its NoCloud seed source, so the fixture
  user/key/marker was not provisioned. This is a VM harness issue before the deployer runs, not an Ubuntu target setting
  the production installer changes.
- **Unsupported OS/architecture**: use Ubuntu 24.04 on amd64 or arm64; reports show the observed preflight values.
- **SSH login rejected on a new device**: enter the Ubuntu bootstrap account password in **Temporary SSH setup
  password**, not the OpenEMS administrator password. Confirm that SSH password login is permitted for that account.
- **SSH key pair not found in advanced mode**: the private-key path is a local Windows file and a sibling with the same
  path plus `.pub` must exist. Turn off advanced technician mode to let the app manage the key automatically.
- **Sudo denial**: compare the bootstrap account to the approved fixture/provisioning policy. Do not grant blanket
  `NOPASSWD: ALL`.
- **Checksum/signature error**: discard the artifact, reacquire it through the release channel, and rerun
  `verify-release.ps1`. Do not override verification.
- **Concurrent deployment**: wait for the active transaction to finish. Investigate a stale lock with the deployment
  report before any manual cleanup.
- **Tailscale timeout/cancel**: retry only after confirming browser approval and device identity. No auth token is saved.
- **Nginx/Edge health failure**: inspect the JSON/HTML rollback proof and `journalctl -u maxiems-edge`; keep the failed
  report and do not manually repoint `current` unless following an approved recovery procedure.
- **JSON-RPC authentication failure**: for a new device, use the administrator password selected during its first
  deployment. For an update, enter the password currently configured for that device's OpenEMS `admin` account. The
  deployer preserves existing user configuration and does not silently reset this password; it may differ from the
  temporary Ubuntu SSH setup password.
- **UI unavailable but SSH works**: check `systemctl status nginx maxiems-edge`, listener bindings, and the active release.
  The expected bindings are `0.0.0.0:8080`, `127.0.0.1:8084`, and `0.0.0.0:8085`.

Escalate with the release metadata, endpoint (without secrets), deployment ID, redacted JSON/HTML report, and relevant
service logs. Never attach private keys, passphrases, passwords, Basic headers, PFX files, or Tailscale login URLs.

## Publishing

Unsigned internal build:

```powershell
$dotnet = "$env:TEMP\maxiems-dotnet10\dotnet.exe" # or a .NET 10 SDK dotnet.exe on PATH
.\publish.ps1 -Version 0.1.0-internal -DotNetPath $dotnet -Force
```

Signed build using an installed code-signing certificate:

```powershell
.\publish.ps1 -Version 1.0.0 -CertificateThumbprint '<SHA1>' `
  -TimestampUrl 'https://your-approved-rfc3161-service/' -Force
```

For a PFX, pass `-CertificatePath` and a `SecureString` through `-CertificatePassword`; the script imports it into a
temporary current-user certificate store and never places the password in the SignTool command line. Signing parameters
are optional as a group, but partial signing configuration is rejected. Generated EXE/PFX/payload/report artifacts are
ignored and must not be committed.
