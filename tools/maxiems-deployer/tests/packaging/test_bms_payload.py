import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('payload', ROOT / 'build-bms-payload.py')
payload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class BmsPayloadTest(unittest.TestCase):
    def test_release_checksums_expose_canonical_payload_hash(self):
        script = (ROOT / 'build-bms-release.sh').read_text()

        self.assertIn("printf '# payloadHash: %s\\n' \"$payload_hash\"", script)
        self.assertIn("jq -er '.payloadHash", script)

    def test_archive_matches_manifest_and_is_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / 'BMSApp.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for bundle in ('bms.service', 'bms.api', 'shared.postgresql', 'controller.api.rest'):
                    inner = io.BytesIO()
                    with zipfile.ZipFile(inner, 'w') as content:
                        content.writestr('io/openems/edge/bms/endpoints/time/BmsTimeJsonApi.class', b'fixture')
                        content.writestr('io/openems/edge/bms/endpoints/tcp/BmsTcpBridgeJsonApi.class', b'fixture')
                        content.writestr('io/openems/edge/bms/endpoints/modbus/BmsModbusServerJsonApi.class', b'fixture')
                    archive.writestr(f'jar/io.openems.edge.{bundle}.jar', inner.getvalue())
            ui = root / 'ui'
            (ui / 'config').mkdir(parents=True)
            (ui / 'index.html').write_text('<html>BMS</html>')
            (ui / 'config/device-config.json').write_text('{"device":{"product":"unified"}}')
            (ui / 'config/device-config.bms.example.json').write_text(json.dumps({
                'device': {'product': 'bms'},
                'navigation': {'enabledItems': ['serial-tcp', 'ntp-server', 'modbus-server']},
                'connectivity': {'tcp': True},
            }))
            packages = root / 'packages'
            (packages / 'amd64').mkdir(parents=True)
            (packages / 'amd64/test.deb').write_bytes(b'offline-package-fixture')
            with patch.object(payload, 'package_inventory'):
                for name in ('a.zip', 'b.zip'):
                    payload.build(jar, ui, packages, ['amd64'], root / name, 'a' * 40,
                                  '2026-09-30T03:34:22Z', '2026-10-05T08:16:56+07:00')
            self.assertEqual(payload.sha256(root / 'a.zip'), payload.sha256(root / 'b.zip'))
            with zipfile.ZipFile(root / 'a.zip') as archive:
                manifest = json.loads(archive.read('manifest.json'))
                self.assertEqual(2, manifest['formatVersion'])
                self.assertEqual('2026-09-30T03:34:22Z', manifest['sourceTimestampUtc'])
                self.assertEqual('2026-10-05T01:16:56Z', manifest['buildTimestampUtc'])
                self.assertEqual('20261005011656-' + manifest['payloadHash'][:12],
                                 manifest['releaseId'])
                self.assertEqual(['amd64'], manifest['supportedTargets']['architectures'])
                for item in manifest['files']:
                    content = archive.read(item['path'])
                    self.assertEqual(item['length'], len(content))
                    self.assertEqual(item['sha256'], hashlib.sha256(content).hexdigest())
                self.assertIn('remote/usr/local/libexec/maxiems-bms-setup', archive.namelist())
                managed = json.loads(archive.read('components/managed-components.v1.json'))
                self.assertTrue(all(component['updateOnly'] for component in managed['components']))
                self.assertEqual({'ctrlApiRest0', 'ctrlApiWebsocket0'},
                                 {component['id'] for component in managed['components']})
                sudoers = archive.read('remote/etc/sudoers.d/maxiems-deployer').decode()
                self.assertIn('maxiems-deploy-helper bms-setup real', sudoers)
                self.assertIn('maxiems-deploy-helper bms-setup demo', sudoers)
                self.assertNotIn('maxiems-deploy-helper bms-setup *', sudoers)
                deploy_helper = archive.read('remote/usr/local/libexec/maxiems-deploy-helper').decode()
                self.assertIn("$2\" == 'real' || \"$2\" == 'demo'", deploy_helper)
                deploy_ops = archive.read('remote/usr/local/libexec/maxiems-deploy-ops').decode()
                self.assertIn('maxiems-bms-setup setup "$2"', deploy_ops)
                active = json.loads(archive.read('ui/config/device-config.json'))
                self.assertEqual('bms', active['device']['product'])
                self.assertTrue(active['connectivity']['tcp'])
                for helper in ('maxiems-network-helper', 'maxiems-time-helper', 'maxiems-system-helper'):
                    self.assertIn(f'remote/usr/local/sbin/{helper}', archive.namelist())
                    self.assertNotIn(f'remote/usr/local/libexec/{helper}', archive.namelist())
                self.assertFalse(any('__pycache__' in name or name.endswith('.pyc')
                                     for name in archive.namelist()))
                self.assertNotIn('config-bms/', archive.namelist())
                self.assertEqual(len(manifest['files']) + 1, len(archive.namelist()))
            release_info = json.loads((root / 'a.zip.manifest.json').read_text())
            archive_hash = payload.sha256(root / 'a.zip')
            self.assertEqual('20261005011656-' + manifest['payloadHash'][:12],
                             release_info['releaseId'])
            self.assertEqual(manifest['releaseId'], release_info['releaseId'])
            self.assertEqual(archive_hash, release_info['payloadArchiveSha256'])
            self.assertEqual(manifest['payloadHash'], release_info['payloadHash'])
            self.assertEqual(manifest['sourceTimestampUtc'], release_info['sourceTimestampUtc'])
            self.assertEqual(manifest['buildTimestampUtc'], release_info['buildTimestampUtc'])
            self.assertEqual('a' * 40, release_info['gitRevision'])
            self.assertEqual(f'{archive_hash}  a.zip\n', (root / 'a.zip.sha256').read_text())

    def test_tree_excludes_python_bytecode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'helper').write_text('#!/usr/bin/python3\n')
            cache = root / '__pycache__'
            cache.mkdir()
            (cache / 'helper.cpython-312.pyc').write_bytes(b'generated')
            (root / 'stale.pyc').write_bytes(b'generated')

            self.assertEqual({'remote/helper': root / 'helper'}, payload.tree(root, 'remote'))

    def test_rejects_stale_api_bundle_before_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / 'BMSApp.jar'
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, 'w') as content:
                content.writestr('old-api.class', b'fixture')
            with zipfile.ZipFile(jar, 'w') as archive:
                for bundle in ('bms.service', 'bms.api', 'shared.postgresql', 'controller.api.rest'):
                    archive.writestr(f'jar/io.openems.edge.{bundle}.jar', inner.getvalue())
            with self.assertRaisesRegex(ValueError, 'Stale BMS API'):
                payload.build(jar, root / 'ui', root / 'packages', ['arm64'], root / 'out.zip',
                              'a' * 40, '2026-10-01T00:00:00Z', '2026-10-05T00:00:00Z')

    def test_bms_profile_rejects_unified_or_missing_tcp(self):
        with tempfile.TemporaryDirectory() as temporary:
            ui = Path(temporary)
            (ui / 'config').mkdir()
            profile = ui / 'config/device-config.bms.example.json'
            profile.write_text(json.dumps({'device': {'product': 'unified'}}))
            with self.assertRaisesRegex(ValueError, 'device.product'):
                payload.bms_ui_profile(ui)
            profile.write_text(json.dumps({
                'device': {'product': 'bms'},
                'navigation': {'enabledItems': ['serial-tcp', 'ntp-server', 'modbus-server']},
                'connectivity': {'tcp': False},
            }))
            with self.assertRaisesRegex(ValueError, 'enable TCP'):
                payload.bms_ui_profile(ui)

    def test_rejects_wrong_architecture_and_missing_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'one.deb').write_bytes(b'fixture')
            with patch.object(payload.subprocess, 'check_output', return_value='Package: nginx\nArchitecture: arm64\n'):
                with self.assertRaisesRegex(ValueError, 'Wrong architecture'):
                    payload.package_inventory(root, 'amd64')
            with patch.object(payload.subprocess, 'check_output', return_value='Package: nginx\nArchitecture: amd64\n'):
                with self.assertRaisesRegex(ValueError, 'Incomplete offline packages'):
                    payload.package_inventory(root, 'amd64')

    def test_rejects_symlink_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'file').write_text('data')
            (root / 'link').symlink_to(root / 'file')
            with self.assertRaisesRegex(ValueError, 'Symlink'):
                payload.tree(root, 'ui')


if __name__ == '__main__':
    unittest.main()
