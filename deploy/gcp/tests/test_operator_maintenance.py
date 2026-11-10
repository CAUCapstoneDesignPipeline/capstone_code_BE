import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from test_engine import Fake, ReleaseError, manifest, request, run
import host
import observe

GCP = Path(__file__).resolve().parents[1]


class Platform(Fake):
    """Fake services with the real Nginx switch, hold and public check rules."""
    hold_path = host.Host.hold_path
    held = host.Host.held
    maintenance = host.Host.maintenance
    smoke_public = host.Host.smoke_public

    def __init__(self):
        super().__init__()
        self.scope = 'cold-start/capstone_live/'
        self.opening = False

    def activate(self, m):
        self.events.append(('nginx', mode()))
        super().activate(m)


def mode():
    try:
        active = host.NGINX.read_text()
    except OSError:
        return None
    return {(GCP / 'nginx-maintenance.conf').read_text(): 'maintenance', (GCP / 'nginx-live.conf').read_text(): 'live'}[active]


class OperatorMaintenanceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.served = []
        self.public_fails = False

        def wait(expected, seconds=10):
            if expected == 200 and self.public_fails:
                raise ReleaseError('public health')
            self.served.append(expected)
        replacements = {'ROOT': self.root / 'state', 'RUNTIME': self.root / 'run',
                        'NGINX': self.root / 'nginx.conf', 'TOOLS': GCP}
        for patcher in [*(patch.object(host, k, v) for k, v in replacements.items()),
                        patch.object(host, 'command', return_value=''),
                        patch.object(host, 'trusted', side_effect=lambda path: json.loads(path.read_text())),
                        patch.dict(os.environ, {'SUDO_USER': 'operator_1'})]:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.platform = Platform()
        self.platform.wait_tls_status = MagicMock(side_effect=wait)

    def main(self, *args):
        output = io.StringIO()
        with patch.object(host, 'Host', return_value=self.platform), patch.object(host.sys, 'argv', ['host', *args]), \
                contextlib.redirect_stdout(output):
            host.main()
        return output.getvalue()

    def log(self):
        path = self.root / 'state' / self.platform.scope / 'maintenance.log'
        return [json.loads(line) for line in path.read_text().splitlines()]

    def activated(self):
        return [e for e in self.platform.events if isinstance(e, tuple) and e[0] == 'activate']

    def test_on_holds_and_confirms_503_even_with_pending_operation(self):
        self.platform.journals['stuck'] = {'operationId': 'stuck', 'phase': 'switching'}
        printed = self.main('--maintenance', 'on')
        self.assertEqual(mode(), 'maintenance')
        self.assertEqual(self.served, [503])
        self.assertTrue(self.platform.hold_path().exists())
        self.assertEqual(json.loads(printed), {'hold': True, 'holdSince': self.log()[0]['at'], 'holdBy': 'operator_1',
                                               'nginx': 'maintenance', 'pendingOperations': 1})
        self.assertEqual([(r['action'], r['by'], r['result']) for r in self.log()], [('on', 'operator_1', 'ok')])
        self.assertRegex(self.log()[0]['at'], r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$')
        self.assertEqual(self.activated(), [])

    def test_on_fails_when_503_is_not_served_but_keeps_hold(self):
        self.platform.wait_tls_status.side_effect = ReleaseError('Nginx did not serve the requested mode')
        with self.assertRaises(ReleaseError):
            self.main('--maintenance', 'on')
        self.assertTrue(self.platform.hold_path().exists())
        self.assertEqual(self.log()[-1]['result'], 'failed')

    def test_off_reopens_only_after_checks_and_public_smoke(self):
        self.main('--maintenance', 'on')
        printed = self.main('--maintenance', 'off')
        self.assertEqual(mode(), 'live')
        self.assertFalse(self.platform.hold_path().exists())
        self.assertEqual(self.activated(), [('activate', 'old')])
        self.assertIn(('nginx', 'maintenance'), self.platform.events)
        self.assertEqual(self.served, [503, 503, 200, 200])
        self.assertEqual(json.loads(printed)['nginx'], 'live')
        self.assertEqual([(r['action'], r['result']) for r in self.log()], [('on', 'ok'), ('off', 'ok')])

    def test_off_failure_returns_to_maintenance_and_keeps_hold(self):
        def schema(p):
            p.rows[-1]['version'] = '1'
        def preflight(p):
            p.fail = 'preflight'
        def health(p):
            p.fail = 'health'
        def public(p):
            self.public_fails = True
        for name, failure in [('schema', schema), ('preflight', preflight), ('health', health), ('public', public)]:
            with self.subTest(failure=name):
                self.setUp()
                self.main('--maintenance', 'on')
                failure(self.platform)
                with self.assertRaises(ReleaseError):
                    self.main('--maintenance', 'off')
                self.assertEqual(mode(), 'maintenance')
                self.assertTrue(self.platform.hold_path().exists())
                self.assertFalse(self.platform.opening)
                self.assertEqual(self.log()[-1], dict(self.log()[-1], action='off', result='failed'))
                if name == 'schema':
                    self.assertEqual(self.activated(), [])

    def test_off_rejected_while_operation_is_unresolved(self):
        self.main('--maintenance', 'on')
        self.platform.journals['stuck'] = {'operationId': 'stuck', 'phase': 'committing'}
        with self.assertRaises(ReleaseError):
            self.main('--maintenance', 'off')
        self.assertEqual(mode(), 'maintenance')
        self.assertEqual(self.activated(), [])
        self.assertTrue(self.platform.hold_path().exists())

    def test_off_requires_current_release(self):
        self.platform.cloud.state = None
        with self.assertRaises(ReleaseError):
            self.main('--maintenance', 'off')
        self.assertIsNone(mode())

    def test_reboot_resume_keeps_hold(self):
        self.main('--maintenance', 'on')
        self.main('--resume')
        self.assertEqual(self.activated(), [('activate', 'old')])
        self.assertEqual(mode(), 'maintenance')
        self.assertNotIn(200, self.served)
        self.assertTrue(self.platform.hold_path().exists())

    def test_successful_deploy_and_recovery_keep_hold(self):
        self.main('--maintenance', 'on')
        self.assertEqual(run(self.platform, request()), 'done')
        self.assertEqual(self.platform.cloud.state['current']['releaseId'], 'next')
        self.assertEqual(mode(), 'maintenance')
        self.platform.fail = 'health'
        with self.assertRaises(ReleaseError):
            run(self.platform, request(manifest('later'), op='op2', expected='next'))
        self.platform.fail = None
        self.assertEqual(run(self.platform, request(manifest('later'), op='op2', expected='next')), 'rolled-back')
        self.assertEqual(mode(), 'maintenance')
        self.assertNotIn(200, self.served)

    def test_without_hold_resume_and_deploy_still_open(self):
        self.main('--resume')
        self.assertEqual(mode(), 'live')
        self.assertEqual(self.served, [503, 200, 200])
        self.assertEqual(run(self.platform, request(expected='old')), 'done')
        self.assertEqual(mode(), 'live')
        self.assertEqual(self.served[-1], 200)
        self.assertFalse((self.root / 'state' / self.platform.scope / 'maintenance.log').exists())

    def test_status_is_read_only_single_line_without_manifest(self):
        printed = self.main('--maintenance', 'status')
        self.assertEqual(printed.count('\n'), 1)
        self.assertEqual(json.loads(printed), {'hold': False, 'holdSince': None, 'holdBy': None,
                                               'nginx': 'unknown', 'pendingOperations': 0})
        self.assertFalse((self.root / 'state').exists())
        self.main('--maintenance', 'on')
        printed = self.main('--maintenance', 'status')
        for absent in ['releaseId', 'secretVersions', 'image', 'projects/']:
            self.assertNotIn(absent, printed)
        self.assertEqual(json.loads(printed)['nginx'], 'maintenance')

    def test_invalid_arguments_are_rejected_before_host_access(self):
        for args in [['--maintenance', 'open'], ['--maintenance', '/tmp/x'], ['--maintenance'],
                     ['--maintenance', 'on', '--resume'], ['--maintenance', 'off', '--operation', 'op1']]:
            with self.subTest(args=args), patch.object(host, 'Host') as constructor, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
                with patch.object(host.sys, 'argv', ['host', *args]):
                    host.main()
            self.assertEqual(exit.exception.code, 2)
            constructor.assert_not_called()
        self.assertFalse((self.root / 'state').exists())

    @unittest.skipIf(os.geteuid() == 0, 'non-root failure path')
    def test_cli_exit_codes(self):
        for args, code in [(['--maintenance', 'bogus'], 2), (['--maintenance', 'status'], 1)]:
            result = subprocess.run([sys.executable, str(GCP / 'host.py'), *args], capture_output=True, text=True,
                                    env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
            self.assertEqual(result.returncode, code)
            self.assertEqual(result.stdout, '')

    def test_observer_reports_held_maintenance(self):
        context = MagicMock()
        tls = context.wrap_socket.return_value.__enter__.return_value
        tls.getpeercert.return_value = {'notAfter': 'Feb  2 07:35:11 2027 GMT'}
        tls.recv.return_value = b'HTTP/1.1 503 Service Temporarily Unavailable\r\n'
        with patch.object(observe.ssl, 'create_default_context', return_value=context), \
                patch.object(observe.socket, 'create_connection'):
            result = observe.sample(opener=MagicMock(side_effect=OSError), clock=lambda: 0)
        self.assertEqual(result['state'], 'maintenance')


if __name__ == '__main__':
    unittest.main()
