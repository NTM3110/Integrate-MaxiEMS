#!/usr/bin/env python3
"""Create an isolated ConfigAdmin directory without pre-created BMS strings."""

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SMOKE_OUTPUT = ROOT / "config-bms"
DEMO_OUTPUT = ROOT / "config-bms-demo"


def value(name, default=None):
    result = os.environ.get(name, default)
    if result is None or "\n" in result or "\r" in result:
        raise SystemExit(f"Set {name} to a single-line value")
    return result


def quote(text):
    return json.dumps(text)


def write(output, path, fields):
    target = output / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(f"{key}={item}" for key, item in fields.items()) + "\n")
    target.chmod(0o600)


def main():
    profile = value("BMS_TEST_PROFILE", "smoke")
    if profile not in ("smoke", "local-demo"):
        raise SystemExit("BMS_TEST_PROFILE must be smoke or local-demo")
    local_demo = profile == "local-demo"
    output = DEMO_OUTPUT if local_demo else SMOKE_OUTPUT
    database = value("BMS_TEST_PG_DATABASE", "demo" if local_demo else "bms_app_test")
    pg_host = value("BMS_TEST_PG_HOST", "127.0.0.1")
    if local_demo:
        if database != "demo" or pg_host not in ("127.0.0.1", "localhost"):
            raise SystemExit("local-demo requires database demo on localhost")
        password = "admin"
    else:
        if not (database == "bms_app_test" or database.startswith("bms_smoke_")):
            raise SystemExit("Use only disposable bms_app_test or bms_smoke_* database")
        password = value("BMS_TEST_ADMIN_PASSWORD")
        if len(password) < 12:
            raise SystemExit("BMS_TEST_ADMIN_PASSWORD must contain at least 12 characters")
    pg_user = value("BMS_TEST_PG_USER", "shou")
    pg_password = value("BMS_TEST_PG_PASSWORD", "")
    modbus_port = value("BMS_TEST_MODBUS_PORT", "/dev/ttyBMS1")
    try:
        pg_port = int(value("BMS_TEST_PG_PORT", "5432" if local_demo else "55432"))
    except ValueError as error:
        raise SystemExit("BMS_TEST_PG_PORT must be an integer") from error
    if not 1 <= pg_port <= 65535:
        raise SystemExit("BMS_TEST_PG_PORT must be between 1 and 65535")
    if local_demo and pg_port != 5432:
        raise SystemExit("local-demo requires PostgreSQL port 5432")
    if output.exists():
        raise SystemExit(f"Refusing to overwrite {output}")
    old_umask = os.umask(0o077)
    try:
        output.mkdir(mode=0o700)
        for pid, identifier in (("Core.AppManager", "_appManager"),
                                ("Core.ComponentManager", "_componentManager"),
                                ("Core.Cycle", "_cycle"), ("Core.Meta", "_meta"),
                                ("Core.PredictorManager", "_predictorManager"),
                                ("Core.Sum", "_sum")):
            write(output, pid.replace(".", "/") + ".config", {
                "id": quote(identifier), "service.pid": quote(pid)})
        for path, pid, identifier, fields in (
            ("Scheduler/AllAlphabetically/bms.config", "Scheduler.AllAlphabetically", "scheduler0", {}),
            ("Controller/Api/Websocket/bms.config", "Controller.Api.Websocket", "ctrlApiWebsocket0",
             {"bindAddress": quote("127.0.0.1"), "port": 'I"8085"'}),
            ("Bridge/Modbus/Serial/bms.config", "Bridge.Modbus.Serial", "modbus0",
             {"portName": quote(modbus_port),
              "baudRate": 'I"9600"', "databits": 'I"8"',
              "stopbits": quote("ONE"), "parity": quote("NONE")})):
            write(output, path, {"id": quote(identifier), "enabled": 'B"true"', **fields,
                         "service.factoryPid": quote(pid), "service.pid": quote(pid + ".bms")})
        pid = "io.openems.edge.bms.platform.persistence.BmsDatabaseImpl"
        write(output, "io/openems/edge/bms/platform/persistence/BmsDatabaseImpl.config", {
            "pgHost": quote(pg_host),
            "pgPort": f'I"{pg_port}"',
            "database": quote(database), "pgUser": quote(pg_user),
            "pgPassword": quote(pg_password),
            "connectionPoolSize": 'I"5"', "service.pid": quote(pid)})
        pid = "io.openems.edge.bms.platform.auth.PlatformAuthenticationServiceImpl"
        write(output, "io/openems/edge/bms/platform/auth/PlatformAuthenticationServiceImpl.config", {
            "bootstrapAdminUsername": quote("admin"),
            "bootstrapAdminName": quote("Local Demo Administrator" if local_demo else "Smoke Test Administrator"),
            "bootstrapAdminPassword": quote(password),
            "importLegacyOpenmucAdministrator": 'B"false"', "service.pid": quote(pid)})
    finally:
        os.umask(old_umask)
    print(f"Created {output}; no string, TA, TC, calculator, or monitoring")


if __name__ == "__main__":
    main()
