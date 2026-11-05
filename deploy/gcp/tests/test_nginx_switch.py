import io
import unittest
from unittest.mock import MagicMock, patch
from test_engine import ReleaseError
from host import Host


class NginxSwitchTest(unittest.TestCase):
    def test_waits_for_old_workers_in_each_direction(self):
        for old, expected in [(503, 200), (200, 503)]:
            context = MagicMock()
            tls = context.wrap_socket.return_value.__enter__.return_value
            responses = [io.BytesIO(f'HTTP/1.1 {code} status\r\n'.encode()) for code in (old, expected)]
            tls.makefile.side_effect = responses
            with patch('host.ssl.create_default_context', return_value=context), patch('host.socket.create_connection'), patch('host.time.sleep') as pause:
                Host.wait_tls_status(expected)
            pause.assert_called_once()
            self.assertEqual(tls.sendall.call_count, 2)

    def test_timeout_prevents_reporting_wrong_mode_as_ready(self):
        with patch('host.ssl.create_default_context'), patch('host.socket.create_connection', side_effect=ConnectionRefusedError):
            with self.assertRaises(ReleaseError):
                Host.wait_tls_status(200, seconds=0)
