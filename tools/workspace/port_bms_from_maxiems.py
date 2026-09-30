#!/usr/bin/env python3
"""Mechanically stage MaxiEMS BMS Java sources in the split integration bundles.

This does not modify Gradle/Bnd metadata or runtime configuration. Existing
Java source trees are moved to an explicit backup directory before copying.
"""

from pathlib import Path
import argparse
import shutil


MODULES = {
    "core": "services/io.openems.edge.bms.core",
    "platform": "services/io.openems.edge.bms.platform",
    "api": "endpoints/io.openems.edge.bms.api",
    "tools": "controllers/io.openems.edge.controller.bms",
    "ta": "drivers/io.openems.edge.bms.huasu.ta",
    "tc": "drivers/io.openems.edge.bms.huasu.tc",
}


def owner(relative: Path) -> str | None:
    parts = relative.parts
    if parts[0] in {"contracts", "model"}:
        return "core"
    if parts[0] == "endpoints":
        return "api"
    if parts[0] == "drivers":
        # The standalone BMS.Demo bundle is intentionally not ported.
        return None if parts[1] == "demo" else parts[2]
    if parts[0] == "controllers":
        if parts[1] == "calculation" and len(parts) > 2 and parts[2] == "checkpoint" and parts[-1] != "SocEstimatorCheckpointValidator.java":
            return "platform"
        if parts[1] in {"calculation", "monitoring", "modbus"}:
            return "tools"
        return "platform"
    raise ValueError(f"Unmapped BMS package: {relative}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--backup", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    target = args.target.resolve()
    backup = args.backup.resolve()
    if source == target or target.parent == backup or not (target / "controllers").is_dir():
        raise SystemExit("Unexpected BMS source/target layout")
    if any((backup / name).exists() for name in MODULES):
        raise SystemExit("Backup destination is already populated")

    counts = {name: 0 for name in MODULES}
    for tree in ("src", "test"):
        source_root = source / "io.openems.edge.bms" / tree / "io/openems/edge/bms"
        if not source_root.is_dir():
            raise SystemExit(f"Missing source: {source_root}")
        for file in source_root.rglob("*.java"):
            owner(file.relative_to(source_root))

    for name, module in MODULES.items():
        for tree in ("src", "test"):
            old = target / module / tree
            if old.exists():
                destination = backup / name / tree
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(old, destination)

    for tree in ("src", "test"):
        source_root = source / "io.openems.edge.bms" / tree / "io/openems/edge/bms"
        for file in source_root.rglob("*.java"):
            relative = file.relative_to(source_root)
            name = owner(relative)
            if name is None:
                continue
            destination = target / MODULES[name] / tree / "io/openems/edge/bms" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, destination)
            counts[name] += 1
    print(counts)
    print(f"Old source trees preserved at {backup}")


if __name__ == "__main__":
    main()
