import json
import unittest
from pathlib import Path
from unittest.mock import patch

from test_engine import manifest, run, request, Fake, ReleaseError
from test_migration import m as migration
from host import Host, COLD_SCOPE, release_scope, checksum


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
