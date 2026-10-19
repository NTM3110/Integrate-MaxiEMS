"""Safe provisioning contracts: never connects to PostgreSQL or changes host services."""
import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import hashlib

ROOT = Path(__file__).resolve().parents[2]
loader = importlib.machinery.SourceFileLoader('bms_setup', str(ROOT / 'remote/usr/local/libexec/maxiems-bms-setup'))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        module.CONFIG = Path(self.temp.name) / 'config'
        module.CONFIG.mkdir()
        module.STATE = Path(self.temp.name) / 'state'
        self.statements = []
        self.password = b'admin'
        self.role = False
        self.database = False
        self.patches = [patch.object(module, 'sql', self.sql), patch.object(module.os, 'chown'),
                        patch.object(module.pwd, 'getpwnam')]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    def sql(self, query, database='postgres'):
        self.statements.append(query)
        if 'pg_roles' in query:
            return '1' if self.role else ''
        if 'pg_database' in query and 'pg_get_userbyid' not in query:
            return '1' if self.database else ''
        if 'pg_get_userbyid' in query:
            return 'maxiems_bms'
        if query.startswith('CREATE ROLE'):
            self.role = True
        if query.startswith('CREATE DATABASE'):
            self.database = True
        if 'schema_version' in query:
            return '8'
        if 'JOIN bms.auth_user_role' in query:
            return '1'
        if 'encode(password_hash' in query:
            salt = b'0123456789abcdef'
            return hashlib.pbkdf2_hmac('sha256', self.password, salt, 600000).hex() + ':' + salt.hex()
        return ''

    def test_fresh_real_setup_generates_db_secret_and_three_serial_bridges(self):
        module.setup('real')
        files = list(module.CONFIG.rglob('*.config'))
        self.assertEqual(11, len(files))
        bridges = [module.config_path(f'Bridge.BmsModbus.Serial.maxiems-modbus{i}').read_text() for i in range(3)]
        self.assertIn('portName="/dev/ttyS1"', bridges[0])
        self.assertIn('portName="/dev/ttyS7"', bridges[1])
        self.assertIn('portName="/dev/ttyS9"', bridges[2])
        self.assertTrue(all('responseTimeoutMs=I"100"' in bridge for bridge in bridges))
        self.assertIn('bootstrapAdminPassword="admin"', module.config_path(module.AUTH_PID).read_text())
        self.assertEqual(0o600, (module.STATE / 'database.json').stat().st_mode & 0o777)
        self.assertFalse(any('CREATE TABLE' in q for q in self.statements))
        module.ready()
        self.assertTrue((module.STATE / 'ready').exists())

    def test_update_preserves_configs_secrets_and_changed_admin(self):
        module.setup('real')
        module.ready()
        db = module.config_path(module.DB_PID)
        db.write_text('existing custom connection')
        before = (module.STATE / 'database.json').read_bytes()
        self.statements.clear()
        self.password = b'changed-password'
        module.setup('real')
        module.ready()
        self.assertEqual('existing custom connection', db.read_text())
        self.assertEqual(before, (module.STATE / 'database.json').read_bytes())
        self.assertFalse(any(q.startswith('CREATE') for q in self.statements))

    def test_unmanaged_database_is_never_adopted_or_reset(self):
        self.database = True
        with self.assertRaisesRegex(RuntimeError, 'unmanaged'):
            module.setup('real')
        self.assertFalse((module.STATE / 'database.json').exists())
        self.assertFalse(any(q.startswith('CREATE') for q in self.statements))

    def test_unknown_profile_is_rejected_before_state_creation(self):
        with self.assertRaisesRegex(RuntimeError, 'must be real or demo'):
            module.setup('staging')
        self.assertFalse(module.STATE.exists())

    def test_missing_schema_never_reports_ready(self):
        module.setup('real')
        with patch.object(module.time, 'monotonic', side_effect=[0, 181]):
            with self.assertRaisesRegex(RuntimeError, 'did not become ready'):
                module.ready()
        self.assertFalse((module.STATE / 'ready').exists())

    def test_demo_uses_same_ids_with_demo_pty_paths_and_profile_is_locked(self):
        module.setup('demo')
        for index, port in enumerate(['/dev/ttyBMS1', '/dev/ttyBMS3', '/dev/ttyBMS5']):
            text = module.config_path(f'Bridge.BmsModbus.Serial.maxiems-modbus{index}').read_text()
            self.assertIn(f'id="modbus{index}"', text)
            self.assertIn(f'portName="{port}"', text)
        with self.assertRaisesRegex(RuntimeError, 'differs'):
            module.setup('real')

    def test_update_migrates_managed_bridge_without_losing_serial_settings(self):
        module.setup('real')
        new = module.config_path('Bridge.BmsModbus.Serial.maxiems-modbus0')
        old = module.config_path('Bridge.Modbus.Serial.maxiems-modbus0')
        old.parent.mkdir(parents=True, exist_ok=True)
        legacy = new.read_text().replace('Bridge.BmsModbus.Serial', 'Bridge.Modbus.Serial')
        legacy = legacy.replace('baudRate=I"9600"', 'baudRate=I"19200"')
        legacy = legacy.replace('responseTimeoutMs=I"100"\n', '')
        old.write_text(legacy)
        new.unlink()

        module.setup('real')

        migrated = new.read_text()
        self.assertFalse(old.exists())
        self.assertIn('id="modbus0"', migrated)
        self.assertIn('portName="/dev/ttyS1"', migrated)
        self.assertIn('baudRate=I"19200"', migrated)
        self.assertIn('responseTimeoutMs=I"100"', migrated)
        module.setup('real')
        self.assertEqual(migrated, new.read_text())

    def test_migration_rejects_duplicate_factory_configs_for_one_port(self):
        module.setup('real')
        new = module.config_path('Bridge.BmsModbus.Serial.maxiems-modbus0')
        old = module.config_path('Bridge.Modbus.Serial.maxiems-modbus0')
        old.parent.mkdir(parents=True, exist_ok=True)
        original = new.read_text()
        old.write_text(original.replace('Bridge.BmsModbus.Serial', 'Bridge.Modbus.Serial'))

        with self.assertRaisesRegex(RuntimeError, 'Duplicate managed serial bridge modbus0'):
            module.setup('real')

        self.assertEqual(original, new.read_text())
        self.assertTrue(old.exists())

    def test_legacy_managed_install_without_bridges_seeds_selected_profile(self):
        module.STATE.mkdir()
        (module.STATE / 'database.json').write_text('{"password":"' + ('a' * 64) + '"}')
        self.role = True
        self.database = True

        module.setup('demo')

        for index, port in enumerate(['/dev/ttyBMS1', '/dev/ttyBMS3', '/dev/ttyBMS5']):
            text = module.config_path(f'Bridge.BmsModbus.Serial.maxiems-modbus{index}').read_text()
            self.assertIn(f'portName="{port}"', text)
        self.assertEqual('demo\n', (module.STATE / module.PROFILE_MARKER).read_text())

    def test_legacy_managed_install_with_any_bridge_requires_explicit_migration(self):
        module.STATE.mkdir()
        (module.STATE / 'database.json').write_text('{"password":"' + ('a' * 64) + '"}')
        self.role = True
        self.database = True
        existing = module.config_path('Bridge.Modbus.Serial.maxiems-modbus0')
        existing.parent.mkdir(parents=True)
        existing.write_text('portName="/dev/custom"\n')

        with self.assertRaisesRegex(RuntimeError, 'explicit migration'):
            module.setup('demo')

        self.assertEqual('portName="/dev/custom"\n', existing.read_text())
        self.assertFalse((module.STATE / module.PROFILE_MARKER).exists())
        self.assertFalse(module.config_path('Bridge.Modbus.Serial.maxiems-modbus1').exists())

    def test_legacy_managed_install_preserves_all_existing_bridges_without_marker(self):
        module.STATE.mkdir()
        (module.STATE / 'database.json').write_text('{"password":"' + ('a' * 64) + '"}')
        self.role = True
        self.database = True
        before = {}
        for index in range(3):
            path = module.config_path(f'Bridge.Modbus.Serial.maxiems-modbus{index}')
            path.parent.mkdir(parents=True, exist_ok=True)
            content = f'id="custom{index}"\nportName="/dev/custom{index}"\n'
            path.write_text(content)
            before[path] = content

        with self.assertRaisesRegex(RuntimeError, 'explicit migration'):
            module.setup('real')

        for path, content in before.items():
            self.assertEqual(content, path.read_text())
        self.assertFalse((module.STATE / module.PROFILE_MARKER).exists())


if __name__ == '__main__':
    unittest.main()
