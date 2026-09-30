#!/usr/bin/env python3
"""Move complete legacy bundles into ownership folders; never overwrite targets."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def destination(name):
    if name in {"io.openems.edge.application", "io.openems.backend.application", "io.openems.backend.edge.application"}:
        return "platform/applications"
    if name in {"io.openems.edge.controller.tools", "io.openems.edge.controller.bms"}:
        return "apps/bms/controllers"
    if name.startswith("io.openems.edge.bms."):
        suffix = name.removeprefix("io.openems.edge.bms.")
        if suffix == "demo":
            raise ValueError("BMS.Demo was retired; do not recreate the simulator bundle")
        category = "drivers" if suffix.startswith("huasu.") else {"api": "endpoints"}.get(suffix, "services")
        return "apps/bms/" + category
    if name == "io.openems.edge.bridge.dlms":
        return "platform/protocols"
    if name == "io.openems.edge.meter.landis.dlms":
        return "apps/profile-meter/meters"
    if name == "io.openems.edge.bridge.edmi":
        return "platform/protocols"
    if name in {"io.openems.edge.meter.edmi", "io.openems.edge.meter.elster.a1700"}:
        return "apps/profile-meter/meters"
    if name in {"io.openems.edge.controller.maximeter", "io.openems.edge.controller.energy.calculator"}:
        return "apps/profile-meter/services"
    shared = {"io.openems.edge.network.manager": "ethernet", "io.openems.edge.modem.4g": "lte", "io.openems.edge.config.manager": "system"}
    if name in shared:
        return "apps/shared/" + shared[name]
    if name.startswith("io.openems.backend."):
        return "platform/backend"
    if name.startswith(("io.openems.common", "io.openems.shared.", "io.openems.oem.", "io.openems.core.")):
        return "platform/common"
    if name == "io.openems.wrapper":
        return "platform/wrappers"
    if name.startswith("io.openems.edge.bridge."):
        return "platform/protocols"
    if name.startswith("io.openems.edge.controller."):
        if name.startswith("io.openems.edge.controller.api") and name != "io.openems.edge.controller.api.modbus.evn":
            return "platform/edge"
        return "apps/ems/controllers"
    if name.startswith("io.openems.edge.meter.") and not name.endswith(".api"):
        return "apps/ems/meters"
    if name.startswith(("io.openems.edge.logger.", "io.openems.edge.smartlogger.", "io.openems.edge.data.smartlogger")):
        return "apps/ems/loggers"
    if name.startswith(("io.openems.edge.pvinverter.", "io.openems.edge.inverter.", "io.openems.edge.batteryinverter.")) and not name.endswith(".api"):
        return "apps/ems/inverters"
    if name.startswith(("io.openems.edge.ess.", "io.openems.edge.battery.", "io.openems.edge.evcs.", "io.openems.edge.evse.", "io.openems.edge.io.", "io.openems.edge.heat.")) and not name.endswith(".api"):
        return "apps/ems/services"
    if name in {"io.openems.edge.customerinfo", "io.openems.edge.monitoring", "io.openems.edge.modem.wifi", "io.openems.edge.notification.email", "io.openems.edge.dcbox", "io.openems.edge.moxa.model", "io.openems.edge.wika.device"}:
        return "apps/ems/services"
    if name.endswith(".api") or name in {"io.openems.edge.common", "io.openems.edge.core"} or name.startswith(("io.openems.edge.scheduler.", "io.openems.edge.timedata.", "io.openems.edge.predictor.", "io.openems.edge.timeofusetariff.", "io.openems.edge.energy", "io.openems.edge.weather.")):
        return "platform/edge"
    return "platform/devices"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    moves = {
        p.parent.name: f"{destination(p.parent.name)}/"
        f"{'io.openems.edge.controller.bms' if p.parent.name == 'io.openems.edge.controller.tools' else p.parent.name}"
        for p in sorted(ROOT.glob("io.openems.*/bnd.bnd"))
    }
    if not moves:
        print("No legacy root bundles to move; existing path manifest preserved.")
        return
    for name, target in moves.items():
        if (ROOT / target).exists():
            raise SystemExit(f"Refusing to overwrite {target}")
    if args.apply:
        for name, target in moves.items():
            (ROOT / target).parent.mkdir(parents=True, exist_ok=True)
            (ROOT / name).rename(ROOT / target)
        (ROOT / "tools/workspace/legacy-paths.json").write_text(json.dumps(moves, indent=2) + "\n")
    print(json.dumps(moves, indent=2))


if __name__ == "__main__":
    main()
