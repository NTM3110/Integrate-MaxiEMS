import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
INSTALLER = ROOT / 'release/install.sh'


class ReleaseInstallerTest(unittest.TestCase):
    def make_release(self, root: Path):
        shutil.copy2(INSTALLER, root / 'install.sh')
        (root / 'install.sh').chmod(0o755)
        (root / 'payload.zip').write_bytes(b'verified payload fixture')
        (root / 'maxiems-deployer').write_text(
            '#!/usr/bin/env bash\nset -euo pipefail\n'
            '[[ "${1:-}" != --help ]] || exit 0\n'
            'printf "%s\\n" "$@" > "$CAPTURE"\n')
        (root / 'maxiems-deployer').chmod(0o755)
        (root / 'maxiems-local-bootstrap-helper').write_text('#!/usr/bin/env bash\nexit 0\n')
        (root / 'maxiems-local-bootstrap-helper').chmod(0o755)
        lines = []
        for name in ('install.sh', 'maxiems-deployer', 'maxiems-local-bootstrap-helper', 'payload.zip'):
            digest = hashlib.sha256((root / name).read_bytes()).hexdigest()
            lines.append(f'{digest}  ./{name}\n')
        (root / 'SHA256SUMS').write_text(''.join(lines))

    def run_installer(self, release: Path, cwd: Path, *arguments: str):
        capture = release / 'arguments.txt'
        environment = os.environ.copy()
        environment['CAPTURE'] = str(capture)
        result = subprocess.run(
            [str(release / 'install.sh'), *arguments], cwd=cwd, env=environment,
            text=True, capture_output=True, check=False)
        return result, capture

    def test_ssh_uses_verified_sibling_payload_and_forwards_arguments_from_other_cwd(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / 'release'
            elsewhere = root / 'elsewhere'
            release.mkdir()
            elsewhere.mkdir()
            self.make_release(release)

            result, capture = self.run_installer(
                release, elsewhere, 'ssh', '--host', '192.0.2.10', '--user', 'admin',
                '--identity', '/secure/deploy-key', '--deploy-option', 'demo',
                '--kiosk', 'preserve')

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([
                'deploy', '--payload', str(release / 'payload.zip'), '--host', '192.0.2.10',
                '--user', 'admin', '--identity', '/secure/deploy-key', '--deploy-option', 'demo',
                '--kiosk', 'preserve'], capture.read_text().splitlines())

    def test_payload_override_is_rejected_in_both_forms(self):
        with tempfile.TemporaryDirectory() as temporary:
            release = Path(temporary)
            self.make_release(release)
            for arguments in (
                    ('ssh', '--payload', '/tmp/other.zip'),
                    ('ssh', '--payload=/tmp/other.zip')):
                result, capture = self.run_installer(release, release, *arguments)
                self.assertEqual(64, result.returncode)
                self.assertIn('owns the verified sibling payload.zip', result.stderr)
                self.assertFalse(capture.exists())

    def test_checksum_failure_happens_before_cli(self):
        with tempfile.TemporaryDirectory() as temporary:
            release = Path(temporary)
            self.make_release(release)
            (release / 'payload.zip').write_bytes(b'tampered')

            result, capture = self.run_installer(
                release, release, 'ssh', '--host', '192.0.2.10', '--user', 'admin',
                '--identity', '/secure/deploy-key')

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(capture.exists())

    def test_local_builds_loopback_invocation_and_rejects_connection_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake = root / 'fake-cli'
            capture = root / 'arguments.txt'
            fake.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$CAPTURE"\n')
            fake.chmod(0o755)
            command = r'''
source "$INSTALLER"
deployer="$FAKE"
payload="$PAYLOAD"
create_local_bootstrap() {
    temporary_root="$TMPROOT"
    temporary_user='temporary-user'
    : > "$temporary_root/deploy-key"
    : > "$temporary_root/deploy-key.pub"
}
cleanup_local_bootstrap() { :; }
run_local --deploy-option demo --kiosk preserve
'''
            environment = os.environ.copy()
            environment.update({
                'INSTALLER': str(INSTALLER), 'FAKE': str(fake), 'PAYLOAD': str(root / 'payload.zip'),
                'TMPROOT': str(root), 'CAPTURE': str(capture)})
            result = subprocess.run(['bash', '-c', command], env=environment,
                                    text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([
                'deploy', '--host', '127.0.0.1', '--user', 'temporary-user', '--identity',
                str(root / 'deploy-key'), '--payload', str(root / 'payload.zip'),
                '--local-bootstrap', '--deploy-option', 'demo', '--kiosk', 'preserve'],
                capture.read_text().splitlines())

            rejected = subprocess.run(
                ['bash', '-c', 'source "$1"; reject_local_connection_override --host 127.0.0.1',
                 'bash', str(INSTALLER)], text=True, capture_output=True, check=False)
            self.assertEqual(64, rejected.returncode)

    def test_cleanup_removes_only_tracked_temporary_files_and_new_authorized_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            auth = root / 'authorized_keys'
            sudoers = root / 'sudoers'
            sshd_config = root / 'sshd-config'
            scratch = root / 'scratch'
            keep = root / 'keep'
            auth.write_text('temporary key')
            sudoers.write_text('temporary rule')
            sshd_config.write_text('temporary ssh restriction')
            scratch.mkdir()
            keep.write_text('keep')
            command = r'''
source "$1"
deploy_authorized_keys="$2"
deploy_authorized_keys_touched=1
deploy_authorized_keys_existed=0
temporary_sudoers="$3"
temporary_sudoers_created=1
temporary_sshd_config="$4"
temporary_sshd_config_created=1
temporary_root="$5"
temporary_user_created=0
cleanup_local_bootstrap
'''
            result = subprocess.run(
                ['bash', '-c', command, 'bash', str(INSTALLER), str(auth), str(sudoers),
                 str(sshd_config), str(scratch)],
                text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(auth.exists())
            self.assertFalse(sudoers.exists())
            self.assertFalse(sshd_config.exists())
            self.assertFalse(scratch.exists())
            self.assertEqual('keep', keep.read_text())

    def test_cleanup_restores_existing_authorized_keys_exactly(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            auth = root / 'authorized_keys'
            backup = root / 'authorized_keys.before'
            scratch = root / 'scratch'
            auth.write_text('temporary local key\n')
            backup.write_text('existing operator key\n')
            backup.chmod(0o640)
            scratch.mkdir()
            command = r'''
source "$1"
deploy_authorized_keys="$2"
deploy_authorized_keys_backup="$3"
deploy_authorized_keys_touched=1
deploy_authorized_keys_existed=1
temporary_root="$4"
temporary_user_created=0
cleanup_local_bootstrap
'''
            result = subprocess.run(
                ['bash', '-c', command, 'bash', str(INSTALLER), str(auth), str(backup), str(scratch)],
                text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('existing operator key\n', auth.read_text())
            self.assertEqual(0o640, auth.stat().st_mode & 0o777)
            self.assertFalse(scratch.exists())

    def test_term_signal_runs_local_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake = root / 'fake-cli'
            fake.write_text('#!/usr/bin/env bash\nkill -TERM "$PPID"\nsleep 1\n')
            fake.chmod(0o755)
            auth = root / 'authorized_keys'
            sudoers = root / 'sudoers'
            sshd_config = root / 'sshd-config'
            scratch = root / 'scratch'
            command = r'''
source "$1"
deployer="$2"
payload="$3"
deploy_authorized_keys="$4"
test_scratch="$5"
test_sudoers="$6"
test_sshd_config="$7"
create_local_bootstrap() {
    temporary_root="$test_scratch"
    /usr/bin/mkdir -p "$temporary_root"
    temporary_user='temporary-user'
    temporary_user_created=0
    temporary_sudoers="$test_sudoers"
    temporary_sudoers_created=1
    temporary_sshd_config="$test_sshd_config"
    temporary_sshd_config_created=1
    deploy_authorized_keys_touched=1
    deploy_authorized_keys_existed=0
    : > "$temporary_root/deploy-key"
    : > "$temporary_root/deploy-key.pub"
    : > "$temporary_sudoers"
    : > "$temporary_sshd_config"
    : > "$deploy_authorized_keys"
}
run_local --deploy-option demo
'''
            result = subprocess.run(
                ['bash', '-c', command, 'bash', str(INSTALLER), str(fake),
                 str(root / 'payload.zip'), str(auth), str(scratch), str(sudoers),
                 str(sshd_config)], text=True, capture_output=True, check=False)
            self.assertEqual(143, result.returncode, result.stderr)
            self.assertFalse(auth.exists())
            self.assertFalse(sudoers.exists())
            self.assertFalse(sshd_config.exists())
            self.assertFalse(scratch.exists())

    def test_builder_and_verifier_require_installer_before_release_checksum(self):
        builder = (ROOT / 'build-bms-release.sh').read_text()
        publisher = (ROOT / 'publish-linux.sh').read_text()
        verifier = (ROOT / 'verify-linux-release.sh').read_text()
        copy_index = builder.index('release/install.sh')
        checksum_index = builder.index('find . -type f ! -name SHA256SUMS')
        self.assertLess(copy_index, checksum_index)
        self.assertIn('-x install.sh', verifier)
        self.assertIn('-x maxiems-local-bootstrap-helper', verifier)
        self.assertIn('"$output" --cli-only', publisher)
        self.assertNotIn('--cli-only', builder)

    def test_cli_only_verification_is_explicit_and_full_release_stays_strict(self):
        with tempfile.TemporaryDirectory() as temporary:
            release = Path(temporary)
            self.make_release(release)
            (release / 'install.sh').unlink()
            (release / 'maxiems-local-bootstrap-helper').unlink()
            lines = []
            for name in ('maxiems-deployer', 'payload.zip'):
                digest = hashlib.sha256((release / name).read_bytes()).hexdigest()
                lines.append(f'{digest}  ./{name}\n')
            (release / 'SHA256SUMS').write_text(''.join(lines))

            cli_only = subprocess.run(
                [str(ROOT / 'verify-linux-release.sh'), str(release), '--cli-only'],
                text=True, capture_output=True, check=False)
            self.assertEqual(0, cli_only.returncode, cli_only.stderr)

            full = subprocess.run(
                [str(ROOT / 'verify-linux-release.sh'), str(release)],
                text=True, capture_output=True, check=False)
            self.assertEqual(2, full.returncode)
            self.assertIn('Missing local installer assets', full.stderr)

            unknown = subprocess.run(
                [str(ROOT / 'verify-linux-release.sh'), str(release), '--unknown-mode'],
                text=True, capture_output=True, check=False)
            self.assertEqual(64, unknown.returncode)

    def test_temporary_sshd_match_is_public_key_only_and_resets_scope(self):
        installer = INSTALLER.read_text()
        match = installer.index("printf 'Match User %s\\n'")
        public_key = installer.index("printf '    PubkeyAuthentication yes\\n'", match)
        password_off = installer.index("printf '    PasswordAuthentication no\\n'", public_key)
        reset = installer.index("printf 'Match all\\n'", password_off)
        self.assertLess(match, public_key)
        self.assertLess(public_key, password_off)
        self.assertLess(password_off, reset)

    def test_local_bootstrap_disables_remote_password_authentication(self):
        installer = INSTALLER.read_text()
        self.assertIn('AuthenticationMethods publickey', installer)
        self.assertIn('PasswordAuthentication no', installer)
        self.assertIn('KbdInteractiveAuthentication no', installer)
        self.assertIn('from="127.0.0.1,::1",restrict', installer)
        self.assertIn('temporary_sshd_config_created', installer)
        self.assertIn('systemctl reload ssh.service', installer)

    def test_release_verifier_rejects_non_executable_installer(self):
        with tempfile.TemporaryDirectory() as temporary:
            release = Path(temporary)
            self.make_release(release)
            success = subprocess.run(
                [str(ROOT / 'verify-linux-release.sh'), str(release)],
                text=True, capture_output=True, check=False)
            self.assertEqual(0, success.returncode, success.stderr)

            (release / 'install.sh').chmod(0o644)
            rejected = subprocess.run(
                [str(ROOT / 'verify-linux-release.sh'), str(release)],
                text=True, capture_output=True, check=False)
            self.assertEqual(2, rejected.returncode)
            self.assertIn('local installer assets', rejected.stderr)


if __name__ == '__main__':
    unittest.main()
