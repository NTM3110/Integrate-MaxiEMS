"""Kiosk readiness and child lifecycle without a graphical session."""

import importlib.machinery
import importlib.util
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import Mock, patch


SOURCE = Path(__file__).resolve().parents[2] / 'remote/usr/local/libexec/maxiems-kiosk-supervisor'


def load():
    loader = importlib.machinery.SourceFileLoader('maxiems_kiosk_supervisor_test', str(SOURCE))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class KioskSupervisorTests(unittest.TestCase):
    def setUp(self):
        self.kiosk = load()

    def test_only_bms_static_page_is_ready(self):
        response = Mock()
        response.status = 200
        response.read.return_value = b'<!doctype html><html data-sveltekit-preload-data="hover">'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=None)
        with patch.object(self.kiosk.urllib.request, 'urlopen', return_value=response):
            self.assertTrue(self.kiosk.ready())
            response.read.return_value = b'<html>Generic 200 page</html>'
            self.assertFalse(self.kiosk.ready())
            response.status = 404
            response.read.return_value = b'<!doctype html><html data-sveltekit-preload-data="hover">'
            self.assertFalse(self.kiosk.ready())

    def test_stops_and_reaps_browser_before_relaunch(self):
        child = Mock()
        child.poll.return_value = None
        self.kiosk.child = child
        self.kiosk.stop_child()
        child.terminate.assert_called_once()
        child.wait.assert_called_once_with(timeout=5)
        self.assertIsNone(self.kiosk.child)

    def test_discovers_only_private_owned_wayland_socket(self):
        with tempfile.TemporaryDirectory() as root:
            runtime = Path(root) / str(os.getuid())
            runtime.mkdir(mode=0o700)
            display = runtime / 'wayland-1'
            with socket.socket(socket.AF_UNIX) as wayland:
                wayland.bind(str(display))
                self.assertEqual((str(runtime), 'wayland-1'),
                                 self.kiosk.discover_wayland(root))

                (runtime / 'wayland-2').symlink_to(display)
                self.assertEqual((str(runtime), 'wayland-1'),
                                 self.kiosk.discover_wayland(root))

                runtime.chmod(0o755)
                self.assertIsNone(self.kiosk.discover_wayland(root))

    def test_missing_runtime_or_non_socket_is_not_a_session(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertIsNone(self.kiosk.discover_wayland(root))
            runtime = Path(root) / str(os.getuid())
            runtime.mkdir(mode=0o700)
            (runtime / 'wayland-1').write_text('not a socket')
            self.assertIsNone(self.kiosk.discover_wayland(root))


if __name__ == '__main__':
    unittest.main()
