# MAXiEMS Deployer Phase 1 handoff

## Delivered scope

Phase 1 is a local, engineer-only Windows application for one semi-finished Ubuntu Edge device at a time. It provides a
versioned Manufacturing Profile/BOM, read-only hardware inspection, explicit LAN1/LAN2 mapping, serial derivation from the
profile-selected permanent MAC, local duplicate protection, safe deploy/update/migration, rollback, automated warehouse
acceptance, immutable reports/history, and verified local backup/export/recovery.

The published `Maxiems.Deployer.exe` is self-contained, single-file, Windows x64, and embeds the verified Edge/UI payload.
The workstation does not need this repository or a development toolchain. GitLab release delivery, central records/RBAC,
parallel fleet work, customer projects, and manual FAT remain Phase 2/3 work.

## Traceability and data

- Local root: `%LOCALAPPDATA%\MAXiEMS\Deployer`.
- Database: `manufacturing.db`; credentials and private-key paths are prohibited from its schema.
- Device identity: `MR` + uppercase 12-hex permanent MAC from the Manufacturing Profile serial-source Ethernet port.
- Both Ethernet ports retain physical label, Linux name, permanent/current MAC, serial-source flag, and observation time.
- Reports carry device/profile/inspection/release/action/check/lifecycle evidence and SHA-256 metadata, not secrets.
- `.mxbak` archives carry a versioned manifest, database schema version, and SHA-256; restore always retains a pre-restore
  database.

## Production inputs that remain external

The executable does not invent or approve a production BOM. Before production use, AT Energy must provide/approve the
exact profile revision (CPU/RAM/storage tolerances, OS image policy, LAN physical mapping, and stable 4G/LoRa/Wi-Fi/BLE
identifiers), backup destination/retention, and password-manager ownership. A sanitized example profile is test material,
not production approval.

Final physical acceptance needs one fresh unit and one already-deployed unit. Capture the derived SN, both permanent and
current MACs, full checklist, release/hash evidence, reboot/startup evidence when required, and one forced rollback. Never
place real credentials or device reports in source control.

## Release verification

Run `verify-release.ps1` against the release directory. It verifies the EXE checksum/metadata, SBOM/report templates,
signed-or-explicitly-unsigned status, exactly one executable, and the embedded payload. Vulnerability-policy gating is
intentionally skipped by operator instruction and is not claimed as passed.
