import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from test_engine import manifest, run, request, Fake, ReleaseError
from test_migration import m as migration
from contract import require, validate
import host
from host import Host, COLD_SCOPE, release_scope, checksum

RELEASES = Path(__file__).parents[1] / 'releases'
V1_TOOLS = 'efaabd05dc61a347909561603401ff612b7b0998'
V2_TOOLS = 'a3d41977f9665fff60a2ab0f129c591915fe7587'


def release(name):
    return json.loads((RELEASES / (name + '.json')).read_text())


class ColdStartTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).parents[1] / 'cold-start-host.example.json').read_text())

    def test_only_new_database_and_app_role_share_cold_release_history(self):
        self.assertEqual(release_scope(self.config), COLD_SCOPE)
        for changes in ({'restoreVerified': True}, {'database': 'capstone'}, {'databaseUser': 'postgres'},
                        {'sqlConnection':'capsnote:asia-northeast3:capsnote-rehearsal-test'}, {'jwtSecretVersion':1}):
            with self.subTest(changes=changes), self.assertRaises(ReleaseError):
                release_scope(dict(self.config, **changes))

    def test_cold_manifest_rejects_old_signing_key(self):
        host = object.__new__(Host)
        host.scope = COLD_SCOPE
        target = manifest()
        target['configRevision'] = checksum(self.config)
        with patch('host.trusted', return_value=self.config):
            with self.assertRaises(ReleaseError):
                host.configuration(target)
            target['secretVersions']['jwtSecret'] = 'projects/capsnote/secrets/capsnote-prod-jwt-secret/versions/2'
            host.configuration(target)

    def test_first_deploy_cannot_adopt_existing_history(self):
        platform = Fake()
        platform.cloud.state = None
        platform.scope = COLD_SCOPE
        platform.check_initial_release = Host.check_initial_release.__get__(platform)
        with self.assertRaises(ReleaseError):
            run(platform, request(expected='none'))
        self.assertEqual(platform.journals, {})

    def test_cold_cutover_requires_fresh_db_session_invalidation_and_unchanged_safety_gates(self):
        record = json.loads((Path(__file__).parents[1]/'migration/cold-start-cutover.example.json').read_text())
        record['writersStopped'] = dict.fromkeys(record['writersStopped'], True)
        record['gates'] = dict.fromkeys(record['gates'], True)
        self.assertFalse(migration.cutover_check(record)['executed'])
        for key in record['gates']:
            with self.subTest(key=key), self.assertRaises(migration.MigrationError):
                migration.cutover_check(dict(record, gates=dict(record['gates'], **{key:False})))
        for change in ({'mode':'unknown'}, {'gcpWritesResumed':True}, {'rollback':'before-writes-source-preserved'}):
            with self.assertRaises(migration.MigrationError):
                migration.cutover_check(dict(record, **change))


class ColdStartV2ReleaseTest(unittest.TestCase):
    """v2 re-releases the v1 images so the host can run the operator maintenance tools."""

    def setUp(self):
        self.v1 = release('cold-start-20261105-v1')
        self.v2 = release('cold-start-20261105-v2')
        self.config = json.loads((Path(__file__).parents[1] / 'cold-start-host.example.json').read_text())

    def preflight_host(self, directory, revision):
        (directory / 'bootstrap-revision').write_text(revision + '\n')
        target = object.__new__(Host)
        target.scope = COLD_SCOPE
        target.cloud = MagicMock(token=lambda: 'fixture', secret=lambda resource: 'fixture')
        target.check_image_migrations = lambda m, history: None
        return target

    def preflight(self, m, revision, history, resume=False):
        def command(args, **kwargs):
            if args[:3] == ['docker', 'image', 'inspect']:
                component = next(c for c in ('be', 'web', 'proxy') if m[c]['image'] == args[3])
                labels = {'org.opencontainers.image.revision': m[component].get('sourceSha')}
                return json.dumps([{'Architecture': 'amd64', 'Os': 'linux', 'Config': {'Labels': labels, 'User': '10001'}}])
            return ''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'run').mkdir()
            target = self.preflight_host(root, revision)
            config = dict(self.config)
            with patch.object(host, 'CONFIG', root), patch.object(host, 'RUNTIME', root / 'run'), \
                    patch.object(host, 'command', side_effect=command), \
                    patch.object(host, 'checksum', return_value=m['configRevision']), \
                    patch.object(Host, 'configuration', lambda self, manifest: setattr(self, 'config', config)), \
                    patch.object(host.socket, 'create_connection'), patch.object(host.ssl, 'create_default_context'), \
                    patch.object(host.os, 'chown'):
                target.preflight(m, history, resume=resume)
                return (root / 'run' / m['releaseId'] / 'be.properties').exists()

    def test_v2_only_moves_bootstrap_revision_and_migration_baseline(self):
        validate(self.v1)
        validate(self.v2)
        changed = {k for k in self.v1 if self.v1[k] != self.v2[k]}
        self.assertEqual(changed, {'releaseId', 'bootstrapRevision', 'flyway'})
        self.assertEqual((self.v1['bootstrapRevision'], self.v2['bootstrapRevision']), (V1_TOOLS, V2_TOOLS))
        self.assertEqual(dict(self.v1['flyway'], before='2'), self.v2['flyway'])
        self.assertEqual((self.v2['flyway']['before'], self.v2['flyway']['after'], self.v2['flyway']['backupReference']),
                         ('2', '2', None))

    def test_v2_keeps_cold_start_scope_and_signing_key(self):
        target = object.__new__(Host)
        target.scope = COLD_SCOPE
        m = dict(self.v2, configRevision=checksum(self.config))
        with patch('host.trusted', return_value=self.config):
            target.configuration(m)
        with patch('host.trusted', return_value=dict(self.config, jwtSecretVersion=1)), self.assertRaises(ReleaseError):
            target.configuration(m)

    def test_v1_to_v2_needs_no_backup_evidence_and_host_revision_gates_both_releases(self):
        applied = [{'installed_rank': 1, 'version': '1', 'checksum': 1, 'success': True},
                   {'installed_rank': 2, 'version': '2', 'checksum': 2, 'success': True}]
        # Same schema before and after: no backup-evidence.json exists and preflight still completes.
        self.assertTrue(self.preflight(self.v2, V2_TOOLS, applied))
        # A schema change would require backup evidence, so the fixture does detect that branch.
        with self.assertRaises(FileNotFoundError):
            self.preflight(self.v2, V2_TOOLS, applied[:1])
        # Updated host tools reject the old release (reboot resume or rollback) before any change.
        for m, revision in [(self.v1, V2_TOOLS), (self.v2, V1_TOOLS)]:
            with self.subTest(release=m['releaseId'], host=revision), self.assertRaisesRegex(ReleaseError, 'Bootstrap'):
                self.preflight(m, revision, applied, resume=True)

    def test_v1_to_v2_deploy_records_previous_and_rollback_is_refused_by_host_revision(self):
        platform = Fake()
        platform.cloud.state = {'current': self.v1, 'previous': None}
        revision = {'value': V2_TOOLS}

        def preflight(m, history, resume=False):
            # host.py preflight: the installed tool revision must match the manifest.
            require(revision['value'] == m['bootstrapRevision'], 'Bootstrap revision mismatch')
        platform.preflight = preflight
        deploy = request(self.v2, op='cold-start-20261105-v2-deploy', expected='cold-start-20261105-v1')
        self.assertEqual(run(platform, deploy), 'done')
        self.assertEqual(platform.cloud.state, {'current': self.v2, 'previous': self.v1})
        activated = [e for e in platform.events if isinstance(e, tuple) and e[0] == 'activate']
        self.assertEqual(activated, [('activate', 'cold-start-20261105-v2')])
        events = list(platform.events)
        back = request(self.v1, operation='rollback', op='cold-start-20261105-v1-back', expected='cold-start-20261105-v2')
        with self.assertRaisesRegex(ReleaseError, 'Bootstrap'):
            run(platform, back)
        self.assertEqual(platform.events, events)
        self.assertNotIn('cold-start-20261105-v1-back', platform.journals)
        revision['value'] = V1_TOOLS
        self.assertEqual(run(platform, back), 'done')
        self.assertEqual(platform.cloud.state, {'current': self.v1, 'previous': self.v2})

    def test_v2_cannot_be_first_release_or_skip_expected_v1(self):
        for state, expected in [(None, 'none'), ({'current': self.v1, 'previous': None}, 'none')]:
            platform = Fake()
            platform.cloud.state = state
            platform.scope = COLD_SCOPE
            platform.check_initial_release = Host.check_initial_release.__get__(platform)
            with self.subTest(state=state), self.assertRaises(ReleaseError):
                run(platform, request(self.v2, op='v2-first', expected=expected))
            self.assertEqual(platform.journals, {})
