import argparse
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('zerifin_youtube_start', Path(__file__).with_name('start.py'))
start = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(start)


class StartTest(unittest.TestCase):
    def test_accepts_private_lan_and_tailscale_addresses(self):
        for address in ('127.0.0.1', '192.168.1.20', '10.1.2.3', '100.72.163.64'):
            self.assertEqual(start.allowed_bind(address), address)
        for address in ('0.0.0.0', '8.8.8.8', '::1'):
            with self.assertRaises(argparse.ArgumentTypeError):
                start.allowed_bind(address)

    @patch.object(start, 'tailscale_binary', return_value='/tailscale')
    @patch.object(start.subprocess, 'run')
    def test_prefers_tailscale_for_zero_configuration_start(self, run, _binary):
        run.return_value.stdout = '100.72.163.64\n'
        self.assertEqual(start.automatic_bind(), '100.72.163.64')


if __name__ == '__main__':
    unittest.main()
