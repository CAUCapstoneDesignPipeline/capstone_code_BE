import copy
import json
import unittest
from pathlib import Path
from unittest.mock import patch

from test_ai_release import AI_IMAGE, CA, ai_manifest, sha
from contract import ReleaseError
import ai_attestation as att
import ai_auto
import host
import pipeline

AI = ai_auto.REPOSITORY
DIGEST = 'sha256:' + '7' * 64
NOW = 1794700000
USED = sorted(set(host.AI_DEFAULT_PROFILES.values()))
EXTRACT = {'profileVersion': 'extract-det-3', 'provider': 'local', 'model': 'kiwi-tfidf-markdown',
           'modelRevision': 'cfg-1+idf-2', 'promptVersion': 'rules-1', 'schemaVersion': '1.0', 'preprocessingVersion': 'md-mask-1'}


class FakeSource:
    """Profile tables and per-file content of one AI commit; `code` changes the code hash of every reasoning profile."""

    def __init__(self, code=b'v1', extra=()):
        self.code = code
        self.extra = set(extra)

    def profiles(self):
        reasoning = {}
        for profile in USED:
            if profile in ('extract-det-3', 'embeddings-bge-m3-1'):
                continue
            operation = {'explain-llm-8': 'explain', 'facts-llm-3': 'facts', 'derive-sound-1': 'derive'}.get(profile, 'merge')
            model = 'llm' if operation in att.LLM_ROLE else 'rules'
            reasoning[profile] = {'id': profile, 'operation': operation, 'model': model, 'prompt': profile, 'files': ('x.py',)}
        for profile in self.extra:
            reasoning[profile] = {'id': profile, 'operation': 'merge', 'model': 'rules', 'prompt': profile, 'files': ('x.py',)}
        extract = {'extract-det-3': {'id': 'extract-det-3', 'relations': 'rules'}}
        embedding = {'embeddings-bge-m3-1': {'id': 'embeddings-bge-m3-1', 'backend': 'sentence-transformers',
                                             'model': host.AI_EMBEDDING['name'], 'revision': host.AI_EMBEDDING['revision'], 'prefix': ''}}
        return reasoning, extract, embedding

    def code_revision(self, files):
        return sha(self.code)[:12]


def ai_release(name='ai-fixture-v3', image=AI_IMAGE, source='b' * 40):
    return {'schemaVersion': 1, 'kind': 'ai', 'releaseId': name, 'image': image, 'sourceSha': source,
            'bootstrapRevision': 'd' * 40, 'configRevision': 'e' * 64, 'idf': {'sha256': 'c' * 64},
            'model': dict(host.AI_EMBEDDING), 'profiles': USED + ['extract-hybrid-1'], 'tls': {'caSha256': sha(CA)},
            'provider': {'name': 'anthropic', 'models': {'facts': 'claude-opus-5-5', 'explain': 'claude-opus-5-5', 'sound': 'claude-opus-5-5'}}}


def world(code=b'v1'):
    """Recorded app state whose installed attestation is exactly what the offline method gives for `code`."""
    release = ai_release()
    sources = {'b' * 40: FakeSource(code), '9' * 40: FakeSource(b'v2', ['extract-hybrid-1'])}
    meta = att.expected(sources['b' * 40], release, USED, {'extract-det-3': EXTRACT})
    installed = att.content(att.build(release, meta))
    app = ai_manifest('cur', release='ai-fixture-v3')
    app['ai']['attestationSha256'] = att.sha256(installed)
    return {'current': app, 'previous': None}, {'current': 'ai-fixture-v3', 'previous': 'ai-fixture-v2'}, release, installed, sources


def env(**extra):
    value = {'GITHUB_REPOSITORY': AI, 'GITHUB_REPOSITORY_ID': ai_auto.REPOSITORY_ID, 'GITHUB_REPOSITORY_OWNER_ID': pipeline.OWNER,
             'GITHUB_REF': 'refs/heads/develop', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_SHA': '9' * 40,
             'GITHUB_WORKFLOW_REF': AI + '/.github/workflows/gcp-cd.yml@refs/heads/develop', 'GCP_DEPLOY_ENABLED': 'true',
             'OPERATION': 'deploy', 'DEPLOY_TOOL_SHA': 'a' * 40, 'SOURCE_SHA': '9' * 40, 'IMAGE_DIGEST': DIGEST}
    value.update(extra)
    return value


def plan(state, ai_state, release, installed, sources, be=None, exported=None, operation='deploy', previous=None):
    schema = {'Derivation': {'properties': {'a': {'type': 'string'}}, 'required': ['a']}}
    return ai_auto.plan(state, ai_state, release, previous, installed, sources, be or schema, exported or schema,
                        operation, '9' * 40, DIGEST, NOW)


class AiAutoTest(unittest.TestCase):
    def test_identity(self):
        with patch.object(ai_auto.subprocess, 'check_output', return_value='a' * 40 + '\n'):
            self.assertEqual(ai_auto.identity(env(), Path('.')), 'deploy')
            self.assertEqual(ai_auto.identity(env(GITHUB_EVENT_NAME='workflow_dispatch', OPERATION='rollback', IMAGE_DIGEST=''), Path('.')), 'rollback')
            for key, value in [('GITHUB_REPOSITORY', 'CAUCapstoneDesignPipeline/capstone_code_BE'), ('GITHUB_REPOSITORY_ID', '1'),
                               ('GITHUB_REF', 'refs/heads/main'), ('GITHUB_EVENT_NAME', 'pull_request'),
                               ('GITHUB_WORKFLOW_REF', AI + '/.github/workflows/gcp-deploy.yml@refs/heads/develop'),
                               ('GCP_DEPLOY_ENABLED', 'false'), ('OPERATION', 'x'), ('DEPLOY_TOOL_SHA', 'b' * 40),
                               ('SOURCE_SHA', '8' * 40), ('IMAGE_DIGEST', 'latest')]:
                with self.subTest(key=key), self.assertRaises(ReleaseError):
                    ai_auto.identity(env(**{key: value}), Path('.'))

    def test_changed_stamps_widen_then_narrow(self):
        state, ai_state, release, installed, sources = world()
        bundle = plan(state, ai_state, release, installed, sources)
        target = bundle['target']
        self.assertEqual((target['releaseId'], target['sourceSha'], target['image'].split('@')[1]),
                         ('cd-ai-20261114234640-9999999', '9' * 40, DIGEST))
        self.assertEqual({k for k in release if release[k] != target[k]}, {'releaseId', 'sourceSha', 'image'})
        widen, narrow = bundle['widen'], bundle['narrow']
        self.assertEqual(widen['releaseManifest'], release)
        self.assertEqual(narrow['releaseManifest'], target)
        old = att.normalized(json.loads(installed)['expectedMeta'])
        for profile in USED:
            entries = att.stamps(widen['expectedMeta'][profile])
            self.assertTrue(all(s in entries for s in old[profile] + att.stamps(narrow['expectedMeta'][profile])), profile)
        self.assertEqual(att.stamps(narrow['expectedMeta']['extract-det-3']), [EXTRACT])
        self.assertEqual(len(widen['expectedMeta']['derive-sound-1']), 4)
        self.assertEqual(ai_auto.unpack(ai_auto.pack(bundle)), bundle)

    def test_unchanged_stamps_skip_widen_and_same_image_is_noop(self):
        state, ai_state, release, installed, sources = world()
        sources['9' * 40] = FakeSource(b'v1', ['extract-hybrid-1'])
        self.assertIsNone(plan(state, ai_state, release, installed, sources)['widen'])
        same = ai_auto.plan(state, ai_state, release, None, installed, sources, {}, {}, 'deploy', release['sourceSha'],
                            release['image'].split('@')[1], NOW)
        self.assertEqual(same, {'noop': True})

    def test_gates(self):
        state, ai_state, release, installed, sources = world()
        be = {'Derivation': {'properties': {'a': {'type': 'string'}}, 'required': ['a']}}
        newer = {'Derivation': {'properties': {'a': {'type': 'string'}, 'b': {'type': 'string'}}, 'required': ['a', 'b']}}
        relaxed = {'Derivation': {'properties': {'a': {'type': 'string'}, 'b': {'type': 'string'}}, 'required': ['a']}}
        self.assertTrue(plan(state, ai_state, release, installed, sources, relaxed, newer)['narrow'])
        for name, change in [
                ('schema', lambda: plan(state, ai_state, release, installed, sources, be, newer)),
                ('tampered install', lambda: plan(state, ai_state, release, installed + b' ', sources)),
                ('wrong stamps', lambda: plan(state, ai_state, release, installed, dict(sources, **{'b' * 40: FakeSource(b'other')}))),
                ('dropped profile', lambda: plan(state, ai_state, release, installed, dict(sources, **{'9' * 40: FakeSource(b'v2')}))),
                ('records disagree', lambda: plan(state, dict(ai_state, current='ai-other'), dict(release, releaseId='ai-other'), installed, sources)),
                ('no previous', lambda: plan(state, ai_state, release, installed, sources, operation='rollback'))]:
            with self.subTest(name), self.assertRaises(ReleaseError):
                change()
        self.assertEqual(att.schema_problems(be, newer), ['/Derivation/properties/b'])
        self.assertEqual(att.schema_problems(newer, relaxed), ['/Derivation/required'])
        self.assertEqual(att.schema_problems(relaxed, newer), [])

    def test_rollback_and_pending_narrow(self):
        state, ai_state, release, installed, sources = world()
        previous = ai_release('ai-fixture-v2', source='9' * 40)
        back = plan(state, ai_state, release, installed, sources, operation='rollback', previous=previous)
        self.assertEqual((back['target'], back['expectedAi']), (previous, 'ai-fixture-v3'))
        self.assertEqual(ai_auto.ai_request(back)['operationId'], 'cd-rollback-ai-fixture-v3')
        # The AI switched to the target but the narrow release did not happen: the next plan narrows only.
        bundle = plan(state, ai_state, release, installed, sources)
        switched = {'current': bundle['target']['releaseId'], 'previous': 'ai-fixture-v3'}
        widened = att.content(bundle['widen'])
        state['current']['ai']['attestationSha256'] = att.sha256(widened)
        pending = ai_auto.plan(state, switched, bundle['target'], release, widened, sources, {}, {}, 'deploy', '9' * 40, DIGEST, NOW + 60)
        self.assertEqual((pending['target'], pending['widen'], pending['narrow']), (bundle['target'], None, bundle['narrow']))

    def test_app_release_pins_only_ai_fields(self):
        state, _, release, _, _ = world()
        base = state['current']
        target = ai_auto.app_release(base, 'narrow', dict(release, image='asia-northeast3-docker.pkg.dev/capsnote/capstone-ai/ai@' + DIGEST,
                                                          releaseId='cd-ai-x', sourceSha='9' * 40), 'f' * 64, NOW)
        self.assertEqual(target['releaseId'], 'cd-narrow-20261114234640-9999999')
        self.assertEqual({k for k in base if base[k] != target[k]}, {'releaseId', 'aiImage', 'ai'})
        self.assertEqual({k for k in base['ai'] if base['ai'][k] != target['ai'][k]}, {'release', 'attestationSha256'})
        self.assertIsNone(ai_auto.app_release(base, 'widen', release, base['ai']['attestationSha256'], NOW))

    def test_ai_step_follows_the_host_protocol(self):
        state, ai_state, release, installed, sources = world()
        bundle = plan(state, ai_state, release, installed, sources)
        request = ai_auto.ai_request(bundle)

        class Cloud:
            def __init__(self):
                self.objects = {'state.json': dict(ai_state)}

            def read(self, key):
                return copy.deepcopy(self.objects.get(key)), 1

            def put(self, key, value, generation):
                assert generation == 0 and key not in self.objects
                self.objects[key] = value

        cloud, commands = Cloud(), []
        journal = json.dumps({'requestHash': ai_auto.ai_digest(request), 'phase': 'succeeded'}).encode()

        def ssh(command, check=True):
            commands.append(command)
            return journal if command.startswith('sudo cat') else b''

        ai_auto.ai_step({}, cloud, bundle, ssh, sleep=lambda s: None)
        self.assertEqual(cloud.objects['releases/%s.json' % request['releaseId']], bundle['target'])
        self.assertEqual(cloud.objects['operations/%s.json' % request['operationId']], request)
        self.assertIn('release.py run --operation-id ' + request['operationId'], commands[0])
        cloud.objects['state.json'] = {'current': 'ai-elsewhere', 'previous': None}
        with self.assertRaises(ReleaseError):
            ai_auto.ai_step({}, cloud, bundle, ssh, sleep=lambda s: None)
        failed = json.dumps({'requestHash': ai_auto.ai_digest(request), 'phase': 'reverted'}).encode()
        cloud = Cloud()
        with self.assertRaises(ReleaseError):
            ai_auto.ai_step({}, cloud, bundle, lambda c, check=True: failed if c.startswith('sudo cat') else b'', sleep=lambda s: None)

    def test_fixed_host_commands(self):
        digest = 'a' * 64
        command = ai_auto.install_command(digest)
        self.assertIn('/etc/capstone-gcp/ai/' + digest + '.attestation.json', command)
        self.assertIn('sha256sum -c', command)
        for bad in ('../x', 'A' * 64, 'a' * 63):
            with self.assertRaises(ReleaseError):
                ai_auto.install_command(bad)
            with self.assertRaises(ReleaseError):
                ai_auto.read_command(bad)


if __name__ == '__main__':
    unittest.main()
