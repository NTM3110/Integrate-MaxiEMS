# MAXiEMS Ubuntu VM fixtures

`Invoke-E2E.ps1` provisions disposable, marker-guarded Ubuntu Minimal 24.04 VMs through QEMU. The image URLs and SHA-256 values are pinned in `images.lock.json`; every cached download is verified before use.

Run the fixture contract:

```powershell
.\Invoke-E2E.ps1 -Scenario FixtureSelfTest -Architecture amd64,arm64
```

Set `MAXIEMS_QEMU_HOME` when QEMU is not in `%TEMP%\maxiems-qemu` or `C:\Program Files\qemu`. Each scenario starts from a QCOW2 snapshot. The harness refuses mutation unless `/etc/maxiems-fixture/marker.json` matches the active provider session. Artifacts are machine-readable JSON under `artifacts/`; VM overlays, generated SSH keys, seed data, and downloaded images remain in `%TEMP%` and are never committed.

The fixture exposes a deterministic dummy interface (`maxiems-fake`), persistent journald audit storage, a controllable APT proxy file, and allowlisted fault hooks for disk, checksum, helper, systemd, nginx, Tailscale, JSON-RPC, package mirror, and network failures.
