"""Offline regression checks for bundle ownership and migration completeness."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WorkspaceLayoutTest(unittest.TestCase):
    def test_all_legacy_bundles_have_one_new_home(self):
        paths = json.loads((ROOT / "tools/workspace/legacy-paths.json").read_text())
        self.assertEqual(291, len(paths))
        for name, relative in paths.items():
            with self.subTest(bundle=name):
                if name in {"io.openems.edge.bms.core", "io.openems.edge.bms.platform"}:
                    self.assertEqual("io.openems.edge.bms.service", Path(relative).name)
                elif name == "io.openems.edge.controller.tools":
                    self.assertEqual("io.openems.edge.controller.bms", Path(relative).name)
                else:
                    self.assertEqual(name, Path(relative).name)
                self.assertTrue((ROOT / relative / "bnd.bnd").is_file())
                self.assertFalse((ROOT / name).exists())

    def test_unique_bundle_names(self):
        names = []
        for group in ("apps", "platform"):
            for descriptor in (ROOT / group).rglob("bnd.bnd"):
                if not set(descriptor.relative_to(ROOT).parts) & {"generated", "bin", "build"}:
                    names.append(descriptor.parent.name)
        self.assertGreaterEqual(len(names), 293)
        self.assertEqual(len(names), len(set(names)))

    def test_app_categories_exist(self):
        categories = {
            "bms": "controllers services endpoints drivers",
            "ems": "controllers services inverters meters loggers",
            "profile-meter": "meters services",
            "shared": "serial tcp ethernet lte system",
        }
        for app, folders in categories.items():
            for folder in folders.split():
                with self.subTest(app=app, folder=folder):
                    self.assertTrue((ROOT / "apps" / app / folder).is_dir())

    def test_bms_contracts_and_implementations_share_one_service_bundle(self):
        services = ROOT / "apps/bms/services"
        service = services / "io.openems.edge.bms.service"
        self.assertTrue((service / "bnd.bnd").is_file())
        self.assertFalse((services / "io.openems.edge.bms.core").exists())
        self.assertFalse((services / "io.openems.edge.bms.platform").exists())
        package_root = service / "src/io/openems/edge/bms/service"
        self.assertTrue((package_root / "calculation/model/BmsCalculationSnapshot.java").is_file())
        self.assertTrue((package_root / "calibration/implement/CalibrationEventService.java").is_file())
        manifest = (service / "bnd.bnd").read_text()
        self.assertIn("io.openems.edge.bms.service.*.contracts", manifest)
        self.assertIn("io.openems.edge.bms.service.*.model", manifest)
        self.assertNotIn("io.openems.edge.controller.tools", manifest.split("-buildpath:", 1)[1].split("-testpath:", 1)[0])

    def test_bms_controller_bundle_has_one_runtime_identity(self):
        controllers = ROOT / "apps/bms/controllers"
        self.assertTrue((controllers / "io.openems.edge.controller.bms/bnd.bnd").is_file())
        self.assertFalse((controllers / "io.openems.edge.controller.tools").exists())
        for app in ("BMSApp", "EdgeApp"):
            bndrun = (ROOT / f"platform/applications/io.openems.edge.application/{app}.bndrun").read_text()
            self.assertIn("io.openems.edge.controller.bms", bndrun)
            self.assertNotIn("io.openems.edge.controller.tools", bndrun)

    def test_bms_demo_bundle_is_not_packaged(self):
        self.assertFalse((ROOT / "apps/bms/simulator").exists())
        for app in ("BMSApp", "EdgeApp"):
            bndrun = (ROOT / f"platform/applications/io.openems.edge.application/{app}.bndrun").read_text()
            self.assertNotIn("io.openems.edge.bms.demo", bndrun)

    def test_shared_postgresql_is_packaged_and_bms_migrations_stay_local(self):
        shared = ROOT / "apps/shared/postgresql/io.openems.edge.shared.postgresql"
        bms = ROOT / "apps/bms/services/io.openems.edge.bms.service"
        self.assertTrue((shared / "bnd.bnd").is_file())
        self.assertTrue((bms / "src/io/openems/edge/bms/service/persistence/implement/BmsSchema.java").is_file())
        self.assertTrue((bms / "src/io/openems/edge/bms/service/persistence/implement/FeatureSchemaMigration.java").is_file())
        for app in ("BMSApp", "EdgeApp"):
            bndrun = (ROOT / f"platform/applications/io.openems.edge.application/{app}.bndrun").read_text()
            self.assertIn("io.openems.edge.shared.postgresql", bndrun)

    def test_profile_meter_protocol_and_application_ownership(self):
        expected_bundles = (
            "platform/protocols/io.openems.edge.bridge.dlms",
            "platform/protocols/io.openems.edge.bridge.edmi",
            "platform/protocols/io.openems.edge.bridge.iec6205621",
            "apps/profile-meter/meters/io.openems.edge.meter.edmi",
            "apps/profile-meter/meters/io.openems.edge.meter.elster.a1700",
            "apps/profile-meter/meters/io.openems.edge.meter.landis.dlms",
            "apps/profile-meter/services/io.openems.edge.controller.energy.calculator",
            "apps/profile-meter/services/io.openems.edge.controller.maximeter",
            "platform/applications/io.openems.backend.application",
            "platform/applications/io.openems.backend.edge.application",
            "platform/applications/io.openems.edge.application",
        )
        for relative in expected_bundles:
            with self.subTest(bundle=relative):
                self.assertTrue((ROOT / relative / "bnd.bnd").is_file())
        for obsolete in ("applications", "apps/a1700", "apps/dlms", "apps/edmi"):
            with self.subTest(obsolete=obsolete):
                self.assertFalse((ROOT / obsolete).exists())

    def test_extracted_packages_have_single_shared_owner(self):
        core = ROOT / "apps/bms/services/io.openems.edge.bms.service"
        for category, package in (("serial", "serial"), ("ethernet", "network"), ("system", "system")):
            shared = ROOT / f"apps/shared/{category}/io.openems.edge.shared.{package}"
            relative = f"src/io/openems/edge/bms/core/{package}"
            self.assertTrue((shared / relative).is_dir())
            self.assertFalse((core / relative).exists())
            manifest = (shared / "bnd.bnd").read_text()
            buildpath = manifest.split("-buildpath:", 1)[1].split("-testpath:", 1)[0]
            self.assertNotIn("io.openems.edge.bms.", buildpath)
            self.assertNotIn("io.openems.edge.bms.core." + package, (core / "bnd.bnd").read_text())

    @unittest.skipUnless((ROOT / ".git").exists() and shutil.which("git"), "Requires Git checkout")
    def test_source_packages_are_not_hidden_by_runtime_ignores(self):
        sources = []
        for group in ("apps", "platform"):
            for source in (ROOT / group).rglob("*.java"):
                parts = source.relative_to(ROOT).parts
                if ("src" in parts or "test" in parts) and not set(parts) & {"generated", "bin", "bin_test", "build"}:
                    sources.append(str(source.relative_to(ROOT)))
        result = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"], cwd=ROOT,
                                input="\n".join(sources), text=True, capture_output=True)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
