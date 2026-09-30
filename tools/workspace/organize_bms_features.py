#!/usr/bin/env python3
"""Organize split BMS service bundles by feature, then role within feature.

This is a one-time mechanical package migration. The OSGi bundle ownership is
unchanged: core owns contracts/models; platform owns business implementations.
"""

from pathlib import Path
import argparse


BASE = "io.openems.edge.bms"
SNAPSHOTS = {
    "BmsCalculationSnapshot": "calculation",
    "SocEstimatorCheckpoint": "calculation",
    "BmsMonitoringSnapshot": "monitoring",
}


def destination(module: str, relative: Path) -> Path | None:
    parts = relative.parts
    if module == "core" and len(parts) >= 3:
        if parts[0] in {"contracts", "model"} and parts[1] != "snapshot":
            return Path(parts[1], parts[0], *parts[2:])
        if parts[:2] == ("model", "snapshot"):
            name = parts[2].removesuffix(".java").removesuffix("Test")
            feature = SNAPSHOTS.get(name)
            if feature is None:
                raise ValueError(f"Unknown snapshot model: {relative}")
            return Path(feature, "model", *parts[2:])
    if module == "platform" and len(parts) >= 2 and parts[0] == "services":
        return Path(parts[1], "implement", *parts[2:])
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bms_root", type=Path)
    args = parser.parse_args()
    root = args.bms_root.resolve()
    modules = {
        "core": root / "services/io.openems.edge.bms.core",
        "platform": root / "services/io.openems.edge.bms.platform",
    }
    moves: list[tuple[Path, Path, str, str]] = []
    for module, folder in modules.items():
        for tree in ("src", "test"):
            base = folder / tree / "io/openems/edge/bms"
            if not base.is_dir():
                continue
            for source in base.rglob("*.java"):
                old_relative = source.relative_to(base)
                new_relative = destination(module, old_relative)
                if new_relative is None:
                    continue
                old_package = BASE + "." + ".".join(old_relative.parts[:-1])
                new_package = BASE + "." + ".".join(new_relative.parts[:-1])
                moves.append((source, base / new_relative, old_package, new_package))

    if not moves:
        raise SystemExit("No unmigrated core/platform BMS files found")
    replacements = set()
    for source, _, old, new in moves:
        if old == BASE + ".model.snapshot":
            replacements.add((old + "." + source.stem, new + "." + source.stem))
        else:
            replacements.add((old, new))
    replacements = sorted(replacements, key=lambda pair: len(pair[0]), reverse=True)
    destination_paths = [target for _, target, _, _ in moves]
    if len(destination_paths) != len(set(destination_paths)):
        raise SystemExit("Two source files would map to the same destination")
    if any(target.exists() for target in destination_paths):
        raise SystemExit("At least one destination already exists")

    changed = 0
    for module in root.rglob("bnd.bnd"):
        for tree in ("src", "test"):
            folder = module.parent / tree
            if not folder.is_dir():
                continue
            for java in folder.rglob("*.java"):
                original = java.read_text()
                updated = original
                for old, new in replacements:
                    updated = updated.replace(old + ".", new + ".")
                    updated = updated.replace(old + ";", new + ";")
                for source, _, old, new in moves:
                    if source == java:
                        updated = updated.replace("package " + old + ";", "package " + new + ";", 1)
                        if old == BASE + ".model.snapshot" and source.stem == "BmsMonitoringSnapshot":
                            updated = updated.replace(
                                "package " + new + ";",
                                "package " + new + ";\n\nimport " + BASE
                                + ".calculation.model.BmsCalculationSnapshot;",
                                1,
                            )
                        break
                # This DS component name is a stable identifier, not a package reference.
                updated = updated.replace(
                    '@Component(name = "io.openems.edge.bms.serial.implement.BmsSerialBridgeServiceImpl"',
                    '@Component(name = "io.openems.edge.bms.core.serial.BmsSerialBridgeRepository"',
                )
                if updated != original:
                    java.write_text(updated)
                    changed += 1

    for source, target, _, _ in moves:
        target.parent.mkdir(parents=True, exist_ok=True)
        source.rename(target)

    print(f"Moved {len(moves)} Java files; updated {changed} source/test files")


if __name__ == "__main__":
    main()
