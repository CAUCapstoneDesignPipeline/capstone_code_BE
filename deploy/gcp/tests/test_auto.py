import copy
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from test_engine import manifest
from contract import ReleaseError
import auto
import pipeline

DIGEST = 'sha256:' + '9' * 64
NOW = 1794700000


def env(repo, component, **extra):
    number, _ = pipeline.REPOSITORIES[repo]
    value = {'GITHUB_REPOSITORY': repo, 'GITHUB_REPOSITORY_ID': number, 'GITHUB_REPOSITORY_OWNER_ID': pipeline.OWNER,
             'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_SHA': '7' * 40,
             'GITHUB_WORKFLOW_REF': repo + '/.github/workflows/gcp-cd.yml@refs/heads/main',
             'GCP_DEPLOY_ENABLED': 'true', 'COMPONENT': component, 'OPERATION': 'deploy', 'DEPLOY_TOOL_SHA': 'a' * 40,
             'SOURCE_SHA': '7' * 40, 'IMAGE_DIGEST': DIGEST,
             'GCP_PROJECT_ID': 'capsnote', 'GCP_ZONE': 'asia-northeast3-a', 'GCP_VM_NAME': 'capsnote-prod-app'}
    value.update(extra)
    return value


def inventory():
    return copy.deepcopy(manifest()['flyway']['migrations'])


class FakeCloud:
    def __init__(self, states):
        self.states = list(states)
        self.reads = 0

    def read(self, name):
        assert name == 'state.json'
        index = min(self.reads, len(self.states) - 1)
        self.reads += 1
        return copy.deepcopy(self.states[index]), index + 1


class AutoReleaseTest(unittest.TestCase):
    def test_identity_accepts_both_callers_and_rejects_everything_else(self):
        with patch.object(auto.subprocess, 'check_output', return_value='a' * 40 + '\n'):
            for repo, (_, component) in pipeline.REPOSITORIES.items():
                self.assertEqual(auto.identity(env(repo, component), Path('.')), (component, 'deploy'))
                self.assertEqual(auto.identity(env(repo, component, GITHUB_EVENT_NAME='workflow_dispatch', OPERATION='rollback',
                                                   SOURCE_SHA='', IMAGE_DIGEST=''), Path('.')), (component, 'rollback'))
                for key, value in [('GITHUB_REPOSITORY_ID', '1'), ('GITHUB_REPOSITORY_OWNER_ID', '2'),
                                   ('GITHUB_REF', 'refs/heads/dev'), ('GITHUB_EVENT_NAME', 'pull_request'),
                                   ('GITHUB_WORKFLOW_REF', repo + '/.github/workflows/gcp-deploy.yml@refs/heads/main'),
                                   ('GCP_DEPLOY_ENABLED', 'false'), ('COMPONENT', 'web' if component == 'be' else 'be'),
                                   ('OPERATION', 'migrate'), ('DEPLOY_TOOL_SHA', 'b' * 40), ('SOURCE_SHA', '8' * 40),
                                   ('IMAGE_DIGEST', 'latest'), ('GCP_VM_NAME', 'other')]:
                    with self.subTest(repo=repo, key=key), self.assertRaises(ReleaseError):
                        auto.identity(env(repo, component, **{key: value}), Path('.'))

    def test_web_swaps_only_the_web_image(self):
        base = manifest('old')
        target = auto.derive(base, 'web', '7' * 40, DIGEST, None, NOW)
        self.assertEqual(target['web'], {'sourceSha': '7' * 40, 'image': base['web']['image'].split('@')[0] + '@' + DIGEST})
        self.assertEqual(target['releaseId'], 'cd-web-20261114234640-7777777')
        changed = {k for k in base if base[k] != target[k]}
        self.assertEqual(changed, {'web', 'releaseId'})

    def test_be_requires_identical_migration_inventory(self):
        base = manifest('old')
        self.assertEqual(auto.derive(base, 'be', '7' * 40, DIGEST, inventory(), NOW)['be']['sourceSha'], '7' * 40)
        added = inventory() + [{'version': '3', 'sha256': '3' * 64}]
        edited = inventory()
        edited[1]['sha256'] = '4' * 64
        for rows in (added, edited, None):
            with self.subTest(rows=rows), self.assertRaises(ReleaseError):
                auto.derive(base, 'be', '7' * 40, DIGEST, rows, NOW)

    def test_same_image_is_a_noop_and_schema_moves_to_after(self):
        base = manifest('old')
        self.assertIsNone(auto.derive(base, 'web', base['web']['sourceSha'], base['web']['image'].split('@')[1], None, NOW))
        base['flyway'].update(before='1', after='2', rollbackCompatibleWith=['2'])
        self.assertEqual(auto.derive(base, 'web', '7' * 40, DIGEST, None, NOW)['flyway']['before'], '2')

    def test_plan_expects_current_and_rollback_uses_previous(self):
        state = {'current': manifest('old'), 'previous': manifest('older')}
        request = auto.plan(state, 'web', 'deploy', '7' * 40, DIGEST, None, NOW)
        self.assertEqual((request['expectedRelease'], request['operationId']), ('old', request['manifest']['releaseId']))
        back = auto.plan(state, 'web', 'rollback')
        self.assertEqual((back['manifest'], back['expectedRelease'], back['operationId']), (manifest('older'), 'old', 'cd-rollback-old'))
        for broken in ({'current': None, 'previous': None}, {'current': manifest('old'), 'previous': None}):
            with self.assertRaises(ReleaseError):
                auto.plan(broken, 'web', 'rollback')

    def test_migration_inventory_from_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / auto.MIGRATIONS
            folder.mkdir(parents=True)
            for name, body in [('V10__later.sql', b'b'), ('V2__first.sql', b'a')]:
                (folder / name).write_bytes(body)
            self.assertEqual(auto.migrations(Path(directory)),
                             [{'version': '2', 'sha256': hashlib.sha256(b'a').hexdigest()},
                              {'version': '10', 'sha256': hashlib.sha256(b'b').hexdigest()}])
            (folder / 'notes.txt').write_text('x')
            with self.assertRaises(ReleaseError):
                auto.migrations(Path(directory))

    def test_retries_once_only_when_another_release_committed(self):
        repo = 'CAUCapstoneDesignPipeline/capstone_code_FE'
        first, second = {'current': manifest('old'), 'previous': None}, {'current': manifest('newer'), 'previous': manifest('old')}
        calls = []

        def execute(request):
            calls.append(request['expectedRelease'])
            if len(calls) == 1:
                raise ReleaseError('Host failed')

        with patch.object(auto.subprocess, 'check_output', return_value='a' * 40 + '\n'):
            auto.run(env(repo, 'web'), Path('.'), FakeCloud([first, second]), execute)
            self.assertEqual(calls, ['old', 'newer'])
            calls.clear()
            with self.assertRaises(ReleaseError):
                auto.run(env(repo, 'web'), Path('.'), FakeCloud([first]), execute)
            self.assertEqual(calls, ['old'])


if __name__ == '__main__':
    unittest.main()
