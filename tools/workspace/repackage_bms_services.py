#!/usr/bin/env python3
"""Move the imported BMS service implementations out of controller packages.

Only the BMS Java sources in apps/bms are rewritten. The scheduled calculation,
monitoring and Modbus controllers remain under controllers.
"""

from pathlib import Path
import argparse
import shutil


FEATURES = (
    "alarms", "auth", "calibration", "discharge", "email", "history",
    "keyboard", "maintenance", "network", "persistence", "serial",
    "site", "strings", "system",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bms_root", type=Path)
    args = parser.parse_args()
    root = args.bms_root.resolve()
    platform = root / "services/io.openems.edge.bms.platform"
    if not platform.is_dir():
        raise SystemExit(f"Missing BMS platform bundle: {platform}")
    replacements = {
        f"io.openems.edge.bms.controllers.{feature}":
        f"io.openems.edge.bms.services.{feature}"
        for feature in FEATURES
    }
    replacements[
        "io.openems.edge.bms.controllers.calculation.checkpoint.persistence"
    ] = "io.openems.edge.bms.services.calculation.checkpoint.persistence"
    replacements[
        "io.openems.edge.bms.controllers.calculation.checkpoint.AsyncSocEstimatorCheckpointStore"
    ] = "io.openems.edge.bms.services.calculation.checkpoint.AsyncSocEstimatorCheckpointStore"

    changed = 0
    for module in root.rglob("bnd.bnd"):
        for tree in ("src", "test"):
            folder = module.parent / tree
            if not folder.is_dir():
                continue
            for java in folder.rglob("*.java"):
                original = java.read_text()
                updated = original
                for old, new in replacements.items():
                    updated = updated.replace(old, new)
                if module.parent == platform and "calculation/checkpoint" in str(java):
                    updated = updated.replace(
                        "io.openems.edge.bms.controllers.calculation.checkpoint",
                        "io.openems.edge.bms.services.calculation.checkpoint",
                    )
                if updated != original:
                    java.write_text(updated)
                    changed += 1

    moved = 0
    for tree in ("src", "test"):
        old = platform / tree / "io/openems/edge/bms/controllers"
        new = platform / tree / "io/openems/edge/bms/services"
        for feature in FEATURES:
            source = old / feature
            if source.exists():
                destination = new / feature
                if destination.exists():
                    raise SystemExit(f"Destination exists: {destination}")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(source, destination)
                moved += 1
        checkpoint = old / "calculation/checkpoint"
        if checkpoint.exists():
            destination = new / "calculation/checkpoint"
            if destination.exists():
                raise SystemExit(f"Destination exists: {destination}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(checkpoint, destination)
            moved += 1
    print(f"Updated {changed} Java files; moved {moved} service trees")


if __name__ == "__main__":
    main()
