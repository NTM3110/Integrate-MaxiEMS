"""Focused tests for the privileged GPIO/UART3 helper without hardware writes."""
import importlib.machinery
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


HELPER = Path(__file__).resolve().parents[1] / 'remote/usr/local/sbin/maxiems-gpio'
loader = importlib.machinery.SourceFileLoader('maxiems_gpio', str(HELPER))
spec = importlib.util.spec_from_loader(loader.name, loader)
helper = importlib.util.module_from_spec(spec)
loader.exec_module(helper)


class GpioModeHelperTest(unittest.TestCase):
    def setUp(self):
        self.strings = {key: dict(value) for key, value in helper.DEFAULTS.items()}

    def test_uart_owner_blocks_switch_and_is_returned(self):
        owner = {'resource': '/dev/ttyS3', 'pid': 42, 'processName': 'java', 'user': 'openems'}
        with patch.object(helper, 'detect_mode', return_value='UART3'), \
             patch.object(helper, 'process_users', return_value=[owner]), \
             patch.object(helper, 'state', return_value={'confirmed': False, 'errorCode': 'RESOURCE_BUSY', 'busyResources': [owner]}) as state, \
             patch.object(helper, 'set_gpio') as set_gpio:
            result = helper.switch(self.strings, 'GPIO')
        set_gpio.assert_not_called()
        self.assertEqual(result['busyResources'][0]['pid'], 42)
        state.assert_called_once_with(self.strings, 'GPIO', error=('RESOURCE_BUSY', 'UART3/GPIO resources are in use'), busy=[owner])

    def test_open_device_reports_process_without_command_line(self):
        with open('/dev/null', 'rb'):
            owners = helper.process_users(Path('/dev/null'))
        own = next(owner for owner in owners if owner['pid'] == os.getpid())
        self.assertEqual(own['resource'], '/dev/null')
        self.assertIn('processName', own)
        self.assertNotIn('commandLine', own)

    def test_gpio_consumer_without_pid_is_reported(self):
        result = type('Result', (), {'stdout': 'line   0: unnamed unused input\nline   1: "IO0" "gpioset" output active-high [used]\n'})()
        with patch.object(helper.subprocess, 'run', return_value=result), \
             patch.object(helper, 'process_users', return_value=[]):
            owners = helper.gpio_consumers()
        self.assertEqual(owners[0]['resource'], 'gpiochip1 line 1 (GPIO33)')
        self.assertIsNone(owners[0]['pid'])

    def test_gpio_output_initial_level_is_set_without_out_glitch(self):
        self.strings['1']['alarmValue'] = 0  # unknown state starts at alarm level
        with patch.object(helper, 'uart_bound', return_value=False), \
             patch.object(helper, 'gpio_base', side_effect=lambda pin: Path('/nonexistent/gpio' + str(pin))), \
             patch.object(helper, 'write') as write:
            helper.set_gpio(self.strings)
        direction_writes = [(str(call.args[0]), call.args[1]) for call in write.call_args_list
                            if str(call.args[0]).endswith('/direction')]
        self.assertIn(('/nonexistent/gpio33/direction', 'low'), direction_writes)
        self.assertIn(('/nonexistent/gpio32/direction', 'high'), direction_writes)
        self.assertFalse(any(value == 'out' for _, value in direction_writes))

    def test_success_persists_only_after_readback(self):
        modes = iter(['UART3', 'GPIO'])
        with patch.object(helper, 'detect_mode', side_effect=lambda: next(modes)), \
             patch.object(helper, 'process_users', return_value=[]), \
             patch.object(helper, 'set_gpio') as set_gpio, \
             patch.object(helper, 'state', return_value={'confirmed': True, 'uart': {}}), \
             patch.object(helper, 'save_config') as save:
            helper.switch(self.strings, 'GPIO')
        set_gpio.assert_called_once_with(self.strings)
        save.assert_called_once_with(self.strings, 'GPIO')

    def test_boot_apply_repairs_uart_speed_without_rewriting_config(self):
        with patch.object(helper, 'detect_mode', return_value='UART3'), \
             patch.object(helper, 'state', side_effect=[
                 {'confirmed': False, 'uart': {'baudRate': 115200}},
                 {'confirmed': True, 'uart': {'baudRate': 9600}}]), \
             patch.object(helper, 'process_users', return_value=[]), \
             patch.object(helper, 'set_uart') as set_uart, \
             patch.object(helper, 'invalidate_output_state'), \
             patch.object(helper, 'save_config') as save:
            result = helper.switch(self.strings, 'UART3', persist=False)
        self.assertTrue(result['confirmed'])
        set_uart.assert_called_once()
        save.assert_not_called()

    def test_explicit_mode_recovers_unknown_hardware_state(self):
        with patch.object(helper, 'detect_mode', return_value='UNKNOWN'), \
             patch.object(helper, 'process_users', return_value=[]), \
             patch.object(helper, 'gpio_consumers', return_value=[]), \
             patch.object(helper, 'set_uart') as set_uart, \
             patch.object(helper, 'state', return_value={'confirmed': True, 'uart': {'baudRate': 9600}}), \
             patch.object(helper, 'save_config') as save:
            result = helper.switch(self.strings, 'UART3')
        self.assertTrue(result['confirmed'])
        set_uart.assert_called_once()
        save.assert_called_once_with(self.strings, 'UART3')

    def test_failed_readback_restores_previous_mode(self):
        with patch.object(helper, 'detect_mode', return_value='UART3'), \
             patch.object(helper, 'process_users', return_value=[]), \
             patch.object(helper, 'set_gpio'), \
             patch.object(helper, 'set_uart') as restore_uart, \
             patch.object(helper, 'state', side_effect=[
                 {'confirmed': False, 'uart': {}},
                 {'confirmed': True},
                 {'confirmed': False, 'rollbackConfirmed': True}]), \
             patch.object(helper, 'save_config') as save:
            result = helper.switch(self.strings, 'GPIO')
        restore_uart.assert_called_once()
        save.assert_not_called()
        self.assertTrue(result['rollbackConfirmed'])

    def test_drive_targets_only_selected_string_and_confirms_readback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for pin in (33, 32):
                base = root / ('gpio' + str(pin))
                base.mkdir()
                (base / 'direction').write_text('out')
                (base / 'active_low').write_text('0')
                (base / 'value').write_text('0')
            with patch.object(helper, 'CONFIG', root / 'config.json'), \
                 patch.object(helper, 'OUTPUT_STATE', root / 'output.json'), \
                 patch.object(helper, 'gpio_base', side_effect=lambda pin: root / ('gpio' + str(pin))), \
                 patch.object(helper, 'detect_mode', return_value='GPIO'), \
                 patch.object(helper, 'uart_bound', return_value=False):
                result = helper.run('drive', {'stringIndex': 1, 'alarm': True})
                self.assertTrue(result['strings']['1']['synchronized'])
                self.assertTrue(result['strings']['1']['alarm'])
                self.assertEqual((root / 'gpio33/value').read_text(), '1')
                self.assertEqual((root / 'gpio32/value').read_text(), '0')
                helper.run('drive', {'stringIndex': 2, 'alarm': True})
                self.assertEqual((root / 'gpio33/value').read_text(), '1')
                self.assertEqual((root / 'gpio32/value').read_text(), '1')
                # The alarm processor sends normal for an unconfigured str2;
                # IO0 must remain asserted for str1.
                helper.run('drive', {'stringIndex': 2, 'alarm': False})
                self.assertEqual((root / 'gpio33/value').read_text(), '1')
                self.assertEqual((root / 'gpio32/value').read_text(), '0')

    def test_legacy_input_migrates_to_output_without_losing_polarity(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text('{"strings":{"1":{"direction":"input","alarmValue":0}}}')
            with patch.object(helper, 'CONFIG', config):
                strings, _ = helper.load_config()
        self.assertEqual(strings['1'], {'direction': 'output', 'alarmValue': 0})

    def test_existing_gpio_level_is_preserved_by_set_gpio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for pin in (33, 32):
                base = root / ('gpio' + str(pin))
                base.mkdir()
                (base / 'direction').write_text('out')
                (base / 'value').write_text('1')
            with patch.object(helper, 'gpio_base', side_effect=lambda pin: root / ('gpio' + str(pin))), \
                 patch.object(helper, 'uart_bound', return_value=False), \
                 patch.object(helper, 'write') as write:
                helper.set_gpio(self.strings)
            self.assertFalse(any(str(call.args[0]).endswith('/direction')
                                 or str(call.args[0]).endswith('/value') for call in write.call_args_list))

    def test_boot_apply_asserts_alarm_level_when_gpio_was_left_normal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config.json').write_text('{"mode":"GPIO"}')
            for pin in (33, 32):
                base = root / ('gpio' + str(pin))
                base.mkdir()
                (base / 'direction').write_text('out')
                (base / 'active_low').write_text('0')
                (base / 'value').write_text('0')
            (root / 'output.json').write_text('{"1":{"alarm":false}}')
            with patch.object(helper, 'CONFIG', root / 'config.json'), \
                 patch.object(helper, 'OUTPUT_STATE', root / 'output.json'), \
                 patch.object(helper, 'gpio_base', side_effect=lambda pin: root / ('gpio' + str(pin))), \
                 patch.object(helper, 'detect_mode', return_value='GPIO'), \
                 patch.object(helper, 'uart_bound', return_value=False):
                result = helper.run('apply', {})
            self.assertTrue(result['confirmed'])
            self.assertEqual((root / 'gpio33/value').read_text(), '1')
            self.assertEqual((root / 'gpio32/value').read_text(), '1')
            self.assertFalse((root / 'output.json').exists())

    def test_save_polarity_keeps_active_alarm_asserted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for pin in (33, 32):
                base = root / ('gpio' + str(pin))
                base.mkdir()
                (base / 'direction').write_text('out')
                (base / 'active_low').write_text('0')
                (base / 'value').write_text('1')
            (root / 'output.json').write_text('{"1":{"alarm":true},"2":{"alarm":true}}')
            request = {'string1AlarmValue': 0, 'string1Direction': 'output',
                       'string2AlarmValue': 1, 'string2Direction': 'output'}
            with patch.object(helper, 'CONFIG', root / 'config.json'), \
                 patch.object(helper, 'OUTPUT_STATE', root / 'output.json'), \
                 patch.object(helper, 'gpio_base', side_effect=lambda pin: root / ('gpio' + str(pin))), \
                 patch.object(helper, 'detect_mode', return_value='GPIO'), \
                 patch.object(helper, 'uart_bound', return_value=False):
                result = helper.run('save', request)
            self.assertTrue(result['confirmed'])
            self.assertTrue(result['strings']['1']['alarm'])
            self.assertTrue(result['strings']['1']['synchronized'])
            self.assertEqual((root / 'gpio33/value').read_text(), '0')
            self.assertEqual((root / 'gpio32/value').read_text(), '1')


if __name__ == '__main__':
    unittest.main()
