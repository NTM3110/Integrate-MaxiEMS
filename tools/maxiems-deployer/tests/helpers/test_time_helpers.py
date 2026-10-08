"""Kiểm thử helper bằng mock; không đổi clock, network hoặc reboot máy chạy test."""
import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import struct
from datetime import datetime

ROOT = Path(__file__).resolve().parents[2] / 'remote/usr/local/sbin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(ROOT / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class TimeHelperTests(unittest.TestCase):
    def setUp(self):
        self.time = load('maxiems-time-helper')

    def test_invalid_ip_never_runs_command(self):
        with patch.object(self.time.os, 'geteuid', return_value=0), patch.object(self.time, 'command') as command:
            for value in ('localhost', '::1%eth0', '1.2.3.4;reboot', '01.2.3.4', '1.2.3.4\n'):
                with self.assertRaises(ValueError):
                    self.time.main(['configure', value])
            command.assert_not_called()

    def test_atomic_configuration_and_order(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'timesyncd.conf.d/90-maxiems.conf'
            with patch.object(self.time, 'CONFIG', config), patch.object(self.time, 'command') as command:
                self.time.configure('192.168.127.8')
                self.assertEqual(config.read_text(), '[Time]\nNTP=192.168.127.8\nFallbackNTP=time.cloudflare.com pool.ntp.org\n')
                self.assertEqual(config.stat().st_mode & 0o777, 0o644)
                self.assertEqual([call.args[1] for call in command.call_args_list],
                                 ['set-timezone', 'enable', 'set-ntp', 'restart'])
                self.time.configure('2001:db8::1')
                self.assertIn('NTP=2001:db8::1\n', config.read_text())

    def test_status_and_unavailable_server(self):
        with patch.object(self.time, 'CONFIG', Path('/nonexistent/bms-time-config')), patch.object(
                self.time, 'command', side_effect=[
                    'Timezone=Asia/Ho_Chi_Minh\nNTPSynchronized=yes\nNTP=yes\n',
                    RuntimeError(), 'Poll interval: 34min 8s (min: 32s; max: 34min 8s)\nPacket count: 0\n',
                    'ActiveState=active\n']):
            result = self.time.status()
            self.assertTrue(result['synchronized'])
            self.assertTrue(result['ntpServiceActive'])
            self.assertIsNone(result['serverAddress'])
            self.assertEqual(result['serviceState'], 'active')
            self.assertEqual(result['packetCount'], 0)
            self.assertEqual(result['pollInterval'], '34min 8s')
            self.assertIn('+', result['currentTime'])
            self.assertIsNone(result['serverReachable'])
            self.assertIn('unavailable', result['error'])

    def test_historical_sync_does_not_imply_current_server_reachability(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / '90-maxiems.conf'
            config.write_text('[Time]\nNTP=192.168.4.81\n')
            with patch.object(self.time, 'CONFIG', config), patch.object(self.time, 'command', side_effect=[
                    'Timezone=Asia/Ho_Chi_Minh\nNTPSynchronized=yes\nNTP=yes\n',
                    'ServerName=192.168.4.81\nServerAddress=192.168.4.81\n',
                    'Poll interval: 34min 8s\nPacket count: 0\n', 'ActiveState=active\n']), patch.object(
                    self.time, 'probe_ntp', side_effect=TimeoutError()):
                result = self.time.status()
            self.assertTrue(result['synchronized'])
            self.assertFalse(result['serverReachable'])
            self.assertIn('No valid NTP response', result['reachabilityError'])

    def test_probe_requires_matching_origin_and_server_response(self):
        transmit = struct.pack('!II', 2208988801, 0)
        valid = bytearray(48)
        valid[0] = 0x24  # LI=0, version=4, mode=4 (server)
        valid[1] = 2
        valid[24:32] = transmit
        valid[32:40] = b'12345678'
        valid[40:48] = b'abcdefgh'

        class FakeSocket:
            def __init__(self, response): self.response = response
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): self.timeout = value
            def connect(self, value): self.destination = value
            def send(self, request): self.request = request
            def recv(self, size):
                response = bytearray(self.response)
                response[24:32] = self.request[40:48] if response[24:32] == transmit else response[24:32]
                return bytes(response)

        with patch.object(self.time.socket, 'socket', return_value=FakeSocket(valid)):
            self.time.probe_ntp('192.168.4.81')
        invalid = bytearray(valid)
        invalid[0] = 0x23  # client response is invalid
        with patch.object(self.time.socket, 'socket', return_value=FakeSocket(invalid)):
            with self.assertRaises(ValueError):
                self.time.probe_ntp('192.168.4.81')

    def test_probe_supports_ipv6_and_applies_bounded_timeout(self):
        class FakeSocket:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): self.timeout = value
            def connect(self, value): self.destination = value
            def send(self, request): self.request = request
            def recv(self, size):
                response = bytearray(48)
                response[0] = 0x24
                response[1] = 3
                response[24:32] = self.request[40:48]
                response[32:40] = b'12345678'
                response[40:48] = b'abcdefgh'
                return bytes(response)

        client = FakeSocket()
        with patch.object(self.time.socket, 'socket', return_value=client) as factory:
            self.time.probe_ntp('2001:db8::103')
        factory.assert_called_once_with(self.time.socket.AF_INET6, self.time.socket.SOCK_DGRAM)
        self.assertEqual(client.timeout, self.time.NTP_PROBE_TIMEOUT_SECONDS)
        self.assertEqual(client.destination, ('2001:db8::103', 123))

    def test_probe_rejects_invalid_header_and_timestamps(self):
        class FakeSocket:
            def __init__(self, mutate): self.mutate = mutate
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): pass
            def connect(self, value): pass
            def send(self, request): self.request = request
            def recv(self, size):
                response = bytearray(48)
                response[0] = 0x24
                response[1] = 2
                response[24:32] = self.request[40:48]
                response[32:40] = b'12345678'
                response[40:48] = b'abcdefgh'
                self.mutate(response)
                return bytes(response)

        mutations = (
            lambda response: response.__setitem__(0, 0xE4),  # unsynchronized leap indicator
            lambda response: response.__setitem__(0, 0x14),  # unsupported version 2
            lambda response: response.__setitem__(0, 0x23),  # client mode
            lambda response: response.__setitem__(1, 0),     # kiss-of-death
            lambda response: response.__setitem__(slice(24, 32), bytes(8)),
            lambda response: response.__setitem__(slice(32, 40), bytes(8)),
            lambda response: response.__setitem__(slice(40, 48), bytes(8)),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation), patch.object(
                    self.time.socket, 'socket', return_value=FakeSocket(mutation)):
                with self.assertRaises(ValueError):
                    self.time.probe_ntp('192.168.4.103')

    def test_status_samples_current_time_after_probe(self):
        class First(datetime):
            @classmethod
            def now(cls, tz=None):
                raise AssertionError('Clock sampled before the probe completed')

        class Final(datetime):
            @classmethod
            def now(cls, tz=None):
                return cls.fromisoformat('2026-10-06T14:00:05+07:00')

        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / '90-maxiems.conf'
            config.write_text('[Time]\nNTP=192.168.4.103\n')
            with patch.object(self.time, 'CONFIG', config), patch.object(
                    self.time, 'command', side_effect=[
                        'Timezone=Asia/Ho_Chi_Minh\nNTPSynchronized=yes\nNTP=yes\n',
                        'ServerName=192.168.4.103\nServerAddress=192.168.4.103\n',
                        'Packet count: 1\nPoll interval: 32s\n', 'ActiveState=active\n']), patch.object(
                    self.time, 'datetime', First), patch.object(self.time, 'probe_ntp') as probe:
                probe.side_effect = lambda server: setattr(self.time, 'datetime', Final)
                result = self.time.status()
        self.assertEqual(result['currentTime'], '2026-10-06T14:00:05+07:00')

    def test_apply_failure_reports_saved_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / '90-maxiems.conf'
            with patch.object(self.time, 'CONFIG', config), patch.object(self.time.os, 'geteuid', return_value=0), patch.object(
                    self.time, 'command', side_effect=RuntimeError('failed')), patch.object(
                    self.time, 'probe_ntp', side_effect=TimeoutError()):
                result = self.time.main(['configure', '192.168.127.8'])
                self.assertEqual(result['configuredServer'], '192.168.127.8')
                self.assertIn('Unable to apply', result['error'])
                self.assertIsNone(result['synchronized'])

    def test_subprocess_has_bounded_timeout_and_clean_environment(self):
        with patch.object(self.time.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = 'NTP=yes'
            self.assertEqual(self.time.command('/usr/bin/timedatectl', 'show'), 'NTP=yes')
            self.assertEqual(run.call_args.kwargs['timeout'], 10)
            self.assertEqual(set(run.call_args.kwargs['env']), {'PATH', 'LC_ALL'})

    def test_reboot_is_exact_and_root_only(self):
        helper = load('maxiems-system-helper')
        with patch.object(helper.os, 'geteuid', return_value=0), patch.object(helper.subprocess, 'run') as run:
            with self.assertRaises(ValueError):
                helper.main(['reboot', 'extra'])
            run.assert_not_called()
            helper.main(['reboot'])
            self.assertEqual(run.call_args.args[0], ['/usr/bin/systemctl', 'reboot', '--no-block'])
        with patch.object(helper.os, 'geteuid', return_value=1000):
            with self.assertRaises(ValueError):
                helper.main(['reboot'])


if __name__ == '__main__':
    unittest.main()
