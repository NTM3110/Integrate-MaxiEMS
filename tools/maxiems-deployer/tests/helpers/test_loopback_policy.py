from pathlib import Path
import re
import socket
import subprocess
import tempfile
import unittest


class PortBindingPolicyTest(unittest.TestCase):
    def test_bms_runtime_defaults_match_deployed_felix_console_policy(self):
        bndrun = (Path(__file__).resolve().parents[4]
                  / 'platform/applications/io.openems.edge.application/BMSApp.bndrun').read_text()
        self.assertIn('org.osgi.service.http.port=8080', bndrun)
        self.assertIn('org.apache.felix.http.host=0.0.0.0', bndrun)
        self.assertNotIn('org.osgi.service.http.port=8081', bndrun)

    def test_private_ports_reject_wildcard_and_lan_in_either_ip_family(self):
        script = (Path(__file__).resolve().parents[2] / 'remote/usr/local/libexec/maxiems-deploy-ops').read_text()
        pattern = re.search(r"grep -Eqv '([^']+)'", script[script.index('local port listeners'):]).group(1)
        self.assertIn('listeners="$(/usr/bin/ss -H -lnt)"', script)
        self.assertIn('listeners_for_port "$port" "$listeners"', script)
        for value in ['127.0.0.1:8084', '127.0.0.1:502',
                      '[::ffff:127.0.0.1]:8084', '[::1]:8084']:
            with self.subTest(value=value):
                self.assertEqual(0, subprocess.run(['grep', '-Eq', pattern], input=value, text=True).returncode)
        for value in ['0.0.0.0:8084', '*:502', '[::]:8084',
                      '[::ffff:192.168.4.127]:8084', '192.168.4.127:8084']:
            with self.subTest(value=value):
                self.assertEqual(1, subprocess.run(['grep', '-Eq', pattern], input=value, text=True).returncode)

    def test_public_ports_require_wildcard_and_real_ipv4_connection(self):
        script = (Path(__file__).resolve().parents[2] / 'remote/usr/local/libexec/maxiems-deploy-ops').read_text()
        self.assertIn('for port in 8080 8085; do', script)
        matcher = script[script.index('has_public_ipv4_listener() {'):script.index('\nlisteners_for_port() {')]
        with tempfile.TemporaryDirectory() as directory:
            matcher_path = Path(directory) / 'match.sh'
            matcher_path.write_text(f'set -euo pipefail\n{matcher}\nhas_public_ipv4_listener "$1" "$2"\n')
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener_socket:
                listener_socket.bind(('127.0.0.1', 0))
                listener_socket.listen()
                port = listener_socket.getsockname()[1]
                for name, address, expected in [
                    ('zero wildcard', '0.0.0.0', 0),
                    ('star wildcard', '*', 0),
                    ('ipv6 wildcard with IPv4 access', '[::]', 0),
                    ('loopback', '127.0.0.1', 1),
                    ('lan', '192.168.4.127', 1),
                ]:
                    with self.subTest(name=name):
                        row = f'LISTEN 0 128 {address}:{port} *:*'
                        result = subprocess.run(['bash', str(matcher_path), str(port), row], check=False)
                        self.assertEqual(expected, result.returncode)
            row = f'LISTEN 0 128 *:{port} *:*'
            result = subprocess.run(['bash', str(matcher_path), str(port), row], check=False)
            self.assertEqual(1, result.returncode)
        self.assertIn('public port ${port} is not listening', script)

    def test_listener_selection_uses_exact_port_from_full_socket_table(self):
        script = (Path(__file__).resolve().parents[2] / 'remote/usr/local/libexec/maxiems-deploy-ops').read_text()
        selector = script[script.index('listeners_for_port() {'):script.index('\nnginx_action() {')]
        table = '\n'.join([
            'LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*',
            'LISTEN 0 128 127.0.0.1:8084 0.0.0.0:*',
            'LISTEN 0 128 0.0.0.0:18080 0.0.0.0:*',
        ])
        with tempfile.TemporaryDirectory() as directory:
            selector_path = Path(directory) / 'select.sh'
            selector_path.write_text(f'set -euo pipefail\n{selector}\nlisteners_for_port "$1" "$2"\n')
            result = subprocess.run(['bash', str(selector_path), '8080', table],
                                    check=True, capture_output=True, text=True)
            self.assertEqual('LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*', result.stdout.strip())

    def test_websocket_migration_matches_only_exact_legacy_managed_shape(self):
        script = (Path(__file__).resolve().parents[2] / 'remote/usr/local/libexec/maxiems-deploy-ops').read_text()
        matcher = script[script.index('is_legacy_managed_websocket() {'):script.index('\nmigrate_managed_websocket() {')]
        legacy = '''\
:org.apache.felix.configadmin.revision:=L"1"
alias=""
apiTimeout=I"60"
bindAddress="127.0.0.1"
debugMode=B"false"
enabled=B"true"
id="ctrlApiWebsocket0"
port=I"8085"
service.bundleLocation="?"
service.factoryPid="Controller.Api.Websocket"
service.pid="Controller.Api.Websocket.maxiems"
'''
        with tempfile.TemporaryDirectory() as directory:
            matcher_path = Path(directory) / 'match.sh'
            matcher_path.write_text(f'set -euo pipefail\n{matcher}\nis_legacy_managed_websocket "$1"\n')
            config_path = Path(directory) / 'websocket.config'
            for name, content, expected in [
                ('legacy managed', legacy, 0),
                ('already public', legacy.replace('127.0.0.1', '0.0.0.0'), 1),
                ('custom id', legacy.replace('ctrlApiWebsocket0', 'customWebsocket'), 1),
                ('custom property', legacy + 'connectionlimit=I"10"\n', 1),
            ]:
                with self.subTest(name=name):
                    config_path.write_text(content)
                    result = subprocess.run(['bash', str(matcher_path), str(config_path)], check=False)
                    self.assertEqual(expected, result.returncode)
