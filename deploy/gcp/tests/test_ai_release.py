"""AI-on release (schemaVersion 4) and the unchanged AI-off path (schemaVersion 3)."""
import contextlib
import copy
import fcntl
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from test_engine import Fake, manifest, request
import contract
from contract import ReleaseError, checksum, require, validate, validate_request
import engine
import host
from host import Host, COLD_SCOPE

GCP = Path(__file__).resolve().parents[1]
RELEASES = GCP / 'releases'
OLD_RUNNER_TOOLS = 'efaabd05dc61a347909561603401ff612b7b0998'
AI_IMAGE = 'asia-northeast3-docker.pkg.dev/capsnote/capstone-ai/ai@sha256:' + 'a' * 64
TOKEN = 'fixture-service-token-0123456789'
CA = b'-----BEGIN CERTIFICATE-----\nZml4dHVyZQ==\n-----END CERTIFICATE-----\n'
SERVER = 'capsnote-prod-ai.asia-northeast3-a.c.capsnote.internal'
HOST_AI = {'baseUrl': 'https://' + SERVER + ':8443', 'serverName': SERVER, 'privateIp': '10.60.2.10',
           'serviceTokenSecret': 'projects/capsnote/secrets/capsnote-prod-ai-service-token'}
POLICY = {'version': 'policy-fixture-1',
          'relationTypes': [{'code': 'is_a', 'label': '상위 개념', 'description': '종류'},
                            {'code': 'part_of', 'label': '구성 요소', 'description': '부분'}],
          'compositionRules': [{'firstType': 'is_a', 'secondType': 'part_of', 'resultType': 'part_of'}]}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stamp(profile, provider='local', model='rules', revision='abc123def456'):
    return {'profileVersion': profile, 'provider': provider, 'model': model, 'modelRevision': revision,
            'promptVersion': profile, 'schemaVersion': '1.0', 'preprocessingVersion': 'md-plain-1'}


def attestation(release='ai-fixture-v3', image=AI_IMAGE, ca=CA):
    profiles = sorted(set(host.AI_DEFAULT_PROFILES.values()))
    expected = {p: stamp(p) for p in profiles}
    expected['embeddings-bge-m3-1'] = dict(stamp('embeddings-bge-m3-1'), model='BAAI/bge-m3',
                                           modelRevision=host.AI_EMBEDDING['revision'], promptVersion=None)
    expected['derive-sound-1'] = [stamp('derive-sound-1', 'local+anthropic', 'claude-opus-5-5', 'claude-opus-5-5@abc123def456'),
                                  stamp('derive-sound-1', 'local', 'llm')]
    return {'releaseManifest': {'schemaVersion': 1, 'kind': 'ai', 'releaseId': release, 'image': image,
                                'sourceSha': 'b' * 40, 'idf': {'sha256': 'c' * 64},
                                'model': dict(host.AI_EMBEDDING), 'profiles': profiles + ['extract-hybrid-1'],
                                'tls': {'caSha256': sha(ca)}},
            'expectedMeta': expected}


def text(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + '\n').encode()


def ai_manifest(name='v5b', att=None, policy=POLICY, ca=CA, **ai):
    m = manifest(name)
    m['schemaVersion'] = 4
    m['aiEnabled'] = True
    m['aiImage'] = AI_IMAGE
    block = {'release': 'ai-fixture-v3', 'serviceToken': 'projects/capsnote/secrets/capsnote-prod-ai-service-token/versions/1',
             'caSha256': sha(ca), 'policySha256': sha(text(policy)), 'attestationSha256': sha(text(att or attestation())),
             'profiles': {}, 'budgetMicrounits': 50000 * 10**6, 'currency': 'KRW', 'pollMs': 5000}
    block.update(ai)
    m['ai'] = block
    return m


def release(name):
    return json.loads((RELEASES / (name + '.json')).read_text())


def old_contract():
    """contract.py of the runner pin (efaabd0); None when that commit is not in the local clone."""
    found = subprocess.run(['git', '-C', str(GCP), 'cat-file', '-e', OLD_RUNNER_TOOLS + ':deploy/gcp/contract.py'],
                           capture_output=True)
    if found.returncode:
        return None
    namespace = {'__name__': 'contract_efaabd0'}
    source = subprocess.check_output(['git', '-C', str(GCP), 'show', OLD_RUNNER_TOOLS + ':deploy/gcp/contract.py'])
    exec(compile(source, 'contract.py@efaabd0', 'exec'), namespace)
    return namespace


def outcome(function, value):
    try:
        function(copy.deepcopy(value))
        return 'ok'
    except Exception as error:
        return '%s: %s' % (type(error).__name__, error)


class SchemaThreeUnchangedTest(unittest.TestCase):
    """8.3-1: every AI-off manifest (real releases and mutations) gets the same result as the runner pin."""

    def manifests(self):
        return [release(p.stem) for p in sorted(RELEASES.glob('*.json'))]

    def test_real_releases_pass_new_contract(self):
        for m in self.manifests():
            with self.subTest(release=m['releaseId']):
                self.assertEqual(m['schemaVersion'], 3)
                validate(m)
                validate_request({'operationId': 'op', 'operation': 'deploy', 'expectedRelease': 'none',
                                  'manifest': m, 'manifestHash': checksum(m)})

    def test_same_result_as_runner_pin_contract(self):
        old = old_contract()
        if old is None:
            self.skipTest('efaabd0 is not in this clone')
        mutations = [lambda m: None, lambda m: m.update(aiEnabled=True), lambda m: m.update(aiImage=AI_IMAGE),
                     lambda m: m.update(ai={}), lambda m: m.update(schemaVersion=2), lambda m: m.update(schemaVersion=True),
                     lambda m: m.update(schemaVersion='4'), lambda m: m.pop('aiImage'),
                     lambda m: m['secretVersions'].update(dbPassword='projects/capsnote/secrets/x/versions/1'),
                     lambda m: m.update(bootstrapRevision='short'), lambda m: m['flyway'].update(after='99')]
        for m in self.manifests() + [manifest()]:
            for index, mutation in enumerate(mutations):
                value = copy.deepcopy(m)
                mutation(value)
                with self.subTest(release=m['releaseId'], mutation=index):
                    self.assertEqual(outcome(validate, value), outcome(old['validate'], value))

    def test_old_runner_contract_rejects_schema_four(self):
        old = old_contract()
        if old is None:
            self.skipTest('efaabd0 is not in this clone')
        with self.assertRaises(Exception):
            old['validate'](ai_manifest())
        # A schema-3 manifest relabelled 4 without an ai block: both versions refuse it.
        relabelled = dict(manifest(), schemaVersion=4)
        for check in (validate, old['validate']):
            with self.assertRaises(Exception):
                check(copy.deepcopy(relabelled))


class SchemaFourTest(unittest.TestCase):
    """8.3-2."""

    def test_valid_ai_manifest_and_request(self):
        m = ai_manifest()
        validate(m)
        validate_request(request(m, expected='v5a'))
        validate(ai_manifest(profiles={'facts': 'facts-llm-3', 'verify-explanation': 'explanation-det-3'}))

    def test_rejections(self):
        def drop(key):
            return lambda m: m['ai'].pop(key)
        cases = {
            'ai missing': lambda m: m.pop('ai'),
            'extra top field': lambda m: m.update(extra=1),
            'extra ai field': lambda m: m['ai'].update(extra=1),
            'ai field missing': drop('pollMs'),
            'flag false with schema 4': lambda m: m.update(aiEnabled=False),
            'image null': lambda m: m.update(aiImage=None),
            'image tag': lambda m: m.update(aiImage='asia-northeast3-docker.pkg.dev/capsnote/capstone-ai/ai:latest'),
            'image other repository': lambda m: m.update(aiImage=AI_IMAGE.replace('capstone-ai/ai', 'capstone-be/be')),
            'token other secret': lambda m: m['ai'].update(serviceToken='projects/capsnote/secrets/capsnote-prod-db-password/versions/1'),
            'token other project': lambda m: m['ai'].update(serviceToken='projects/other/secrets/capsnote-prod-ai-service-token/versions/1'),
            'token latest': lambda m: m['ai'].update(serviceToken='projects/capsnote/secrets/capsnote-prod-ai-service-token/versions/latest'),
            'token version 0': lambda m: m['ai'].update(serviceToken='projects/capsnote/secrets/capsnote-prod-ai-service-token/versions/0'),
            'hash short': lambda m: m['ai'].update(caSha256='a' * 63),
            'hash upper': lambda m: m['ai'].update(policySha256='A' * 64),
            'hash null': lambda m: m['ai'].update(attestationSha256=None),
            'release id': lambda m: m['ai'].update(release='AI_Release'),
            'profiles not dict': lambda m: m['ai'].update(profiles=[]),
            'profiles unknown operation': lambda m: m['ai'].update(profiles={'chat': 'chat-llm-1'}),
            'profiles enum spelling': lambda m: m['ai'].update(profiles={'VERIFY_EXPLANATION': 'explanation-det-3'}),
            'dev profile': lambda m: m['ai'].update(profiles={'embeddings': 'embeddings-hash-dev-1'}),
            'profile grammar': lambda m: m['ai'].update(profiles={'facts': 'Facts LLM'}),
            'budget zero': lambda m: m['ai'].update(budgetMicrounits=0),
            'budget bool': lambda m: m['ai'].update(budgetMicrounits=True),
            'budget float': lambda m: m['ai'].update(budgetMicrounits=1.5),
            'budget huge': lambda m: m['ai'].update(budgetMicrounits=2**53),
            'currency': lambda m: m['ai'].update(currency='krw'),
            'poll low': lambda m: m['ai'].update(pollMs=999),
            'poll high': lambda m: m['ai'].update(pollMs=60001),
            'oversized': lambda m: m['flyway'].update(migrations=[{'version': str(i), 'sha256': 'f' * 64} for i in range(1, 400)], after='2'),
        }
        for name, mutation in cases.items():
            m = ai_manifest()
            mutation(m)
            with self.subTest(case=name), self.assertRaises(ReleaseError):
                validate(m)

    def test_schema_three_with_ai_fields_rejected(self):
        for mutation in (lambda m: m.update(aiEnabled=True, aiImage=AI_IMAGE),
                         lambda m: m.update(ai=ai_manifest()['ai'])):
            m = manifest()
            mutation(m)
            with self.assertRaises(ReleaseError):
                validate(m)

    def test_shared_base_rules_still_apply(self):
        m = ai_manifest()
        m['flyway']['migrations'].reverse()
        with self.assertRaises(ReleaseError):
            validate(m)


class PureChecksTest(unittest.TestCase):
    def test_policy_rules(self):
        self.assertEqual(host.policy_problems(POLICY), [])
        bad = [dict(POLICY, relationTypes=[]), dict(POLICY, extra=1),
               dict(POLICY, relationTypes=POLICY['relationTypes'] * 2),
               dict(POLICY, compositionRules=[{'firstType': 'is_a', 'secondType': 'causes', 'resultType': 'is_a'}]),
               dict(POLICY, compositionRules=POLICY['compositionRules'] * 2),
               dict(POLICY, relationTypes=[{'code': 'Is-A', 'label': 'x', 'description': 'y'}]),
               dict(POLICY, relationTypes=[{'code': 'is_a', 'label': '', 'description': 'y'}])]
        for value in bad:
            with self.subTest(value=value):
                self.assertTrue(host.policy_problems(value))
        self.assertEqual(host.policy_problems(dict(POLICY, compositionRules=[])), [])

    def test_attestation_accepts_one_stamp_or_a_stamp_list(self):
        m = ai_manifest()
        self.assertEqual(host.attestation_problems(attestation(), m), [])
        single = attestation()
        single['expectedMeta']['derive-sound-1'] = stamp('derive-sound-1')
        self.assertEqual(host.attestation_problems(single, m), [])

    def test_attestation_rejections(self):
        m = ai_manifest()

        def edit(fn):
            value = attestation()
            fn(value)
            return value
        cases = {
            'image differs from aiImage': edit(lambda a: a['releaseManifest'].update(image=AI_IMAGE.replace('a' * 64, 'b' * 64))),
            'release id differs': edit(lambda a: a['releaseManifest'].update(releaseId='ai-other')),
            'kind': edit(lambda a: a['releaseManifest'].update(kind='be')),
            'profiles missing': edit(lambda a: a['releaseManifest'].update(profiles=['extract-det-3'])),
            'ca differs': edit(lambda a: a['releaseManifest'].update(tls={'caSha256': 'd' * 64})),
            'model revision': edit(lambda a: a['releaseManifest']['model'].update(revision='e' * 40)),
            'idf': edit(lambda a: a['releaseManifest'].update(idf='x')),
            'meta missing': edit(lambda a: a['expectedMeta'].pop('facts-llm-3')),
            'meta empty list': edit(lambda a: a['expectedMeta'].update({'derive-sound-1': []})),
            'meta list over 8': edit(lambda a: a['expectedMeta'].update({'derive-sound-1': [stamp('derive-sound-1')] * 9})),
            'meta key mismatch': edit(lambda a: a['expectedMeta']['derive-sound-1'][1].update(profileVersion='derive-rules-1')),
            'meta extra field': edit(lambda a: a['expectedMeta']['links-det-1'].update(durationMs=1)),
            'meta schema version': edit(lambda a: a['expectedMeta']['links-det-1'].update(schemaVersion='1.1')),
            'meta null model': edit(lambda a: a['expectedMeta']['links-det-1'].update(model=None)),
            'embedding stamp': edit(lambda a: a['expectedMeta']['embeddings-bge-m3-1'].update(model='BAAI/other')),
            'shape': {'releaseManifest': []},
        }
        for name, value in cases.items():
            with self.subTest(case=name):
                self.assertTrue(host.attestation_problems(value, m))
        overridden = ai_manifest(profiles={'derive': 'derive-rules-1'})
        self.assertTrue(host.attestation_problems(attestation(), overridden))   # not in releaseManifest.profiles

    def test_host_ai_block(self):
        m = ai_manifest()
        self.assertEqual(host.ai_config_problems({'ai': HOST_AI}, m), [])
        for change in ({'baseUrl': 'http://' + SERVER + ':8443'}, {'baseUrl': 'https://' + SERVER + ':8443/x'},
                       {'privateIp': '8.8.8.8'}, {'privateIp': 'x'}, {'serverName': 'evil.example.com'},
                       {'serviceTokenSecret': 'projects/capsnote/secrets/capsnote-prod-db-password'}, {'extra': 1}):
            with self.subTest(change=change):
                self.assertTrue(host.ai_config_problems({'ai': dict(HOST_AI, **change)}, m))
        self.assertTrue(host.ai_config_problems({}, m))
        with patch.object(host, 'TOOLS', GCP):
            self.assertEqual(host.compose_ai_host(), (SERVER, '10.60.2.10'))

    def test_properties_escape(self):
        self.assertEqual(host.render_properties({'a': 'x y\\z\n', 'b': 'https://h:8443'}), 'a=x\\ y\\\\z\\n\nb=https://h:8443\n')


class Harness(unittest.TestCase):
    """Real Host.preflight/compose/smoke over temporary CONFIG, ROOT and RUNTIME; docker and cloud are fakes."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for name in ('config/ai', 'config/configurations', 'run', 'state'):
            (self.root / name).mkdir(parents=True)
        (self.root / 'config' / 'bootstrap-revision').write_text('a' * 40 + '\n')
        self.config = dict(json.loads((GCP / 'cold-start-host.example.json').read_text()), ai=dict(HOST_AI))
        self.calls = []
        self.ready = MagicMock()
        self.secrets = []
        for patcher in [patch.object(host, 'CONFIG', self.root / 'config'), patch.object(host, 'ROOT', self.root / 'state'),
                        patch.object(host, 'RUNTIME', self.root / 'run'), patch.object(host, 'TOOLS', GCP),
                        patch.object(host, 'ROOT_UID', os.getuid()), patch.object(host, 'command', side_effect=self.command),
                        patch.object(host.os, 'chown'), patch.object(host.socket, 'create_connection'),
                        patch.object(host.ssl, 'create_default_context'), patch.object(Host, 'ai_ready', self.ready)]:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.control = self.root / 'state' / COLD_SCOPE / 'ai-control'
        self.control.mkdir(parents=True)
        self.control.chmod(0o755)

    def command(self, args, **kwargs):
        self.calls.append((args, kwargs.get('env')))
        if args[:3] == ['docker', 'image', 'inspect']:
            return json.dumps([{'Architecture': 'amd64', 'Os': 'linux',
                                'Config': {'Labels': {'org.opencontainers.image.revision': 'a' * 40 if 'capstone-be' in args[3] else 'c' * 40},
                                           'User': '10001'}}])
        return ''

    def secret(self, resource):
        self.secrets.append(resource)
        return TOKEN if 'ai-service-token' in resource else 'fixture-secret'

    def host(self):
        target = object.__new__(Host)
        target.scope = COLD_SCOPE
        target.opening = False
        target.ai_strict = True
        target.config = self.config
        target.cloud = MagicMock(token=lambda: 'fixture', secret=self.secret)
        target.check_image_migrations = lambda m, history: None
        target.configuration = lambda m: setattr(target, 'config', self.config)
        return target

    def place(self, m, att=None, policy=POLICY, ca=CA):
        for data, key, suffix in ((text(policy), 'policySha256', '.policy.json'),
                                  (text(att or attestation()), 'attestationSha256', '.attestation.json'),
                                  (ca, 'caSha256', '.ca.pem')):
            path = self.root / 'config' / 'ai' / (m['ai'][key] + suffix)
            path.write_bytes(data)
            path.chmod(0o644)

    def preflight(self, m, resume=False, target=None):
        target = target or self.host()
        with patch.object(host, 'checksum', return_value=m['configRevision']):
            target.preflight(m, [{'installed_rank': 1, 'version': '2', 'checksum': 1, 'success': True}], resume=resume)
        return target

    def properties(self, m):
        lines = (self.root / 'run' / m['releaseId'] / 'be.properties').read_text().splitlines()
        return dict(line.split('=', 1) for line in lines)


class PreflightTest(Harness):
    def test_ai_off_writes_only_the_flag_and_an_empty_mount(self):
        m = manifest('v5a')
        self.preflight(m)
        props = self.properties(m)
        self.assertEqual({k: v for k, v in props.items() if k.startswith('capstone.ai.')}, {'capstone.ai.enabled': 'false'})
        ai = self.root / 'run' / 'v5a' / 'ai'
        self.assertEqual((list(ai.iterdir()), ai.stat().st_mode & 0o777), ([], 0o500))
        self.assertEqual(self.secrets, [m['secretVersions'][k] for k in contract.SECRETS])
        self.ready.assert_not_called()
        env = self.calls[-1][1]
        self.assertEqual((env['CAPSTONE_AI_ENABLED'], env['AI_CONTROL_DIR'], env['RUNTIME_DIR']),
                         ('false', str(ai), str(self.root / 'run' / 'v5a')))

    def test_ai_off_does_not_need_the_control_directory(self):
        shutil.rmtree(self.control)
        self.preflight(manifest('v5a'))

    def test_ai_on_full_property_set_files_and_token_handling(self):
        m = ai_manifest(profiles={'derive': 'derive-sound-1'})
        self.place(m)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.preflight(m)
        self.assertNotIn(TOKEN, output.getvalue())
        props = self.properties(m)
        ai = {k: v for k, v in props.items() if k.startswith('capstone.ai.')}
        self.assertEqual(ai, {
            'capstone.ai.enabled': 'true', 'capstone.ai.runtime.release-authorized': 'true',
            'capstone.ai.runtime.suspension-file': '/run/capstone-ai-control/suspended',
            'capstone.ai.pipeline.policy-file': '/run/capstone-ai/policy.json',
            'capstone.ai.pipeline.attestation-file': '/run/capstone-ai/attestation.json',
            'capstone.ai.pipeline.attestation-sha256': m['ai']['attestationSha256'],
            'capstone.ai.pipeline.synthetic-stub': 'false', 'capstone.ai.pipeline.profiles.derive': 'derive-sound-1',
            'capstone.ai.execution.budget-microunits': '50000000000', 'capstone.ai.execution.currency': 'KRW',
            'capstone.ai.execution.poll-ms': '5000', 'capstone.ai.client.base-url': HOST_AI['baseUrl'],
            'capstone.ai.client.service-token': TOKEN, 'capstone.ai.client.ca-certificate': '/run/capstone-ai/ca.pem'})
        runtime = self.root / 'run' / m['releaseId']
        self.assertEqual((runtime / 'be.properties').stat().st_mode & 0o777, 0o400)
        for name, key in (('policy.json', 'policySha256'), ('attestation.json', 'attestationSha256'), ('ca.pem', 'caSha256')):
            path = runtime / 'ai' / name
            self.assertEqual((sha(path.read_bytes()), path.stat().st_mode & 0o777), (m['ai'][key], 0o400))
        holders = [p for p in self.root.rglob('*') if p.is_file() and TOKEN.encode() in p.read_bytes()]
        self.assertEqual(holders, [runtime / 'be.properties'])
        self.assertIn(m['ai']['serviceToken'], self.secrets)
        self.ready.assert_called_once()
        env = self.calls[-1][1]
        self.assertEqual((env['CAPSTONE_AI_ENABLED'], env['AI_CONTROL_DIR']), ('true', str(self.control)))
        self.assertNotIn(TOKEN, json.dumps([c[0] for c in self.calls]))

    def test_resume_skips_readiness_and_suspension_but_keeps_file_checks(self):
        m = ai_manifest()
        self.place(m)
        (self.control / 'suspended').write_text('{}')
        target = self.preflight(m, resume=True)
        self.ready.assert_not_called()
        self.assertFalse(target.ai_strict)
        (self.root / 'config' / 'ai' / (m['ai']['policySha256'] + '.policy.json')).write_bytes(b'{}')
        with self.assertRaises(ReleaseError):
            self.preflight(m, resume=True)

    def failures(self):
        """8.3-3 and 8.3-9: each must fail in preflight."""
        def content(key, suffix, data):
            def apply(m):
                (self.root / 'config' / 'ai' / (m['ai'][key] + suffix)).write_bytes(data)
            return apply

        def readiness(m):
            self.ready.side_effect = ReleaseError('AI not ready for the release profiles')

        def other_attestation(field, value):
            def apply(m):
                att = attestation()
                att['releaseManifest'][field] = value
                data = text(att)
                m['ai']['attestationSha256'] = sha(data)
                content('attestationSha256', '.attestation.json', data)(m)
            return apply

        def control(change):
            def apply(m):
                shutil.rmtree(self.control)
                change()
            return apply
        target = self.root / 'elsewhere'
        target.mkdir()
        target.chmod(0o755)
        return {
            'policy hash': content('policySha256', '.policy.json', b'{"tampered": true}'),
            'ca hash': content('caSha256', '.ca.pem', CA + b'\n'),
            'attestation image': other_attestation('image', AI_IMAGE.replace('a' * 64, 'f' * 64)),
            'attestation profiles': other_attestation('profiles', ['extract-det-3']),
            'attestation ca': other_attestation('tls', {'caSha256': '0' * 64}),
            'content file missing': lambda m: (self.root / 'config' / 'ai' / (m['ai']['caSha256'] + '.ca.pem')).unlink(),
            'content file writable': lambda m: (self.root / 'config' / 'ai' / (m['ai']['caSha256'] + '.ca.pem')).chmod(0o666),
            'readiness failed or profile missing': readiness,
            'suspended': lambda m: (self.control / 'suspended').write_text('{}'),
            'control missing': control(lambda: None),
            'control symlink': control(lambda: self.control.symlink_to(target)),
            'control mode': lambda m: self.control.chmod(0o775),
            'host ai block': lambda m: self.config.pop('ai'),
            'host token secret': lambda m: self.config.update(ai=dict(HOST_AI, serviceTokenSecret='projects/capsnote/secrets/x')),
            'compose host entry': lambda m: self.config.update(ai=dict(HOST_AI, privateIp='10.60.2.11')),
            'bad token': lambda m: setattr(self, 'secret', lambda resource: 'short'),
        }

    def test_ai_on_failures_stop_before_maintenance_without_journal(self):
        # Control: the same harness without a failure deploys.
        m = ai_manifest()
        self.place(m)
        platform = Fake()
        platform.cloud.state = {'current': manifest('v5a'), 'previous': manifest('v4')}
        real = self.host()
        platform.preflight = lambda target, history, resume=False: self.preflight(target, resume, real)
        self.assertEqual(engine.run(platform, request(m, op='v5b-deploy', expected='v5a')), 'done')
        for name, failure in self.failures().items():
            with self.subTest(failure=name):
                self.setUp()
                m = ai_manifest()
                self.place(m)
                failure(m)
                platform = Fake()
                platform.cloud.state = {'current': manifest('v5a'), 'previous': manifest('v4')}
                real = self.host()
                platform.preflight = lambda target, history, resume=False: self.preflight(target, resume, real)
                with self.assertRaises(ReleaseError):
                    engine.run(platform, request(m, op='v5b-deploy', expected='v5a'))
                self.assertEqual((platform.journals, platform.closed, platform.events), ({}, False, []))
                self.assertFalse((self.root / 'run' / m['releaseId'] / 'be.properties').exists())

    def test_unpatched_ready_check_uses_ca_sni_ip_and_token(self):
        m = ai_manifest()
        target = self.host()
        sent = []

        class Channel:
            def __init__(self, reply):
                self.reply = reply

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def sendall(self, data):
                sent.append(data)

            def makefile(self, mode):
                return io.BytesIO(self.reply)

        def reply(status, body):
            data = json.dumps(body).encode()
            return b'HTTP/1.1 %d X\r\nContent-Length: %d\r\n\r\n' % (status, len(data)) + data
        ready = {'status': 'ready', 'schemaVersion': '1.0', 'profileVersions': sorted(set(host.AI_DEFAULT_PROFILES.values()))}
        for answer, ok in [(reply(200, ready), True), (reply(503, {'error': {}}), False),
                           (reply(200, dict(ready, profileVersions=ready['profileVersions'][1:])), False), (b'garbage', False)]:
            context = MagicMock()
            context.wrap_socket.return_value = Channel(answer)
            with self.subTest(answer=answer[:40]), patch.object(host.ssl, 'create_default_context', return_value=context) as make, \
                    patch.object(host.socket, 'create_connection') as connect:
                call = lambda: REAL_AI_READY(target, m, CA.decode(), TOKEN)
                if ok:
                    call()
                else:
                    with self.assertRaises(ReleaseError) as raised:
                        call()
                    self.assertNotIn(TOKEN, str(raised.exception))
                make.assert_called_with(cadata=CA.decode())
                connect.assert_called_with(('10.60.2.10', 8443), timeout=5)
                self.assertEqual(context.wrap_socket.call_args.kwargs['server_hostname'], SERVER)
        self.assertIn(b'Authorization: Bearer ' + TOKEN.encode(), sent[0])
        self.assertTrue(sent[0].startswith(b'GET /internal/v1/health/ready HTTP/1.1\r\n'))


REAL_AI_READY = Host.ai_ready


class ComposeTest(Harness):
    """8.3-5."""

    def test_compose_file_declares_flag_mounts_and_fixed_ai_host(self):
        be = json.loads((GCP / 'compose.json').read_text())['services']['be']
        self.assertEqual(be['environment']['CAPSTONE_AI_ENABLED'], '${CAPSTONE_AI_ENABLED:?ai flag required}')
        mounts = {v['target']: v for v in be['volumes']}
        self.assertEqual(mounts['/run/capstone-ai']['source'], '${RUNTIME_DIR:?runtime required}/ai')
        self.assertEqual(mounts['/run/capstone-ai-control']['source'], '${AI_CONTROL_DIR:?ai control required}')
        for target in ('/run/capstone-ai', '/run/capstone-ai-control', '/run/secrets/be.properties'):
            self.assertEqual((mounts[target]['read_only'], mounts[target]['bind']), (True, {'create_host_path': False}))
        self.assertEqual(be['extra_hosts'], [SERVER + ':10.60.2.10'])
        self.assertEqual(be['logging'], {'driver': 'none'})

    @unittest.skipUnless(shutil.which('docker'), 'docker CLI not installed')
    def test_docker_compose_config_renders_both_modes(self):
        for enabled in ('false', 'true'):
            env = dict(os.environ, BE_IMAGE='be@sha256:' + 'b' * 64, WEB_IMAGE='web@sha256:' + 'd' * 64,
                       PROXY_IMAGE='proxy@sha256:' + 'e' * 64, SQL_CONNECTION='p:r:i', RUNTIME_DIR='/run/capstone-gcp/r1',
                       CAPSTONE_AI_ENABLED=enabled, AI_CONTROL_DIR='/var/lib/capstone-gcp/x/ai-control')
            result = subprocess.run(['docker', 'compose', '-p', 'capstone-gcp', '-f', str(GCP / 'compose.json'), 'config',
                                     '--format', 'json'], env=env, capture_output=True, text=True, timeout=60)
            if result.returncode and 'format' in result.stderr:
                self.skipTest('docker compose without --format json')
            self.assertEqual(result.returncode, 0, result.stderr)
            be = json.loads(result.stdout)['services']['be']
            self.assertEqual(be['environment']['CAPSTONE_AI_ENABLED'], enabled)
            sources = {v['target']: v['source'] for v in be['volumes']}
            self.assertEqual(sources['/run/capstone-ai'], '/run/capstone-gcp/r1/ai')
            self.assertEqual(sources['/run/capstone-ai-control'], '/var/lib/capstone-gcp/x/ai-control')
            self.assertTrue(any(SERVER in h and '10.60.2.10' in h for h in be['extra_hosts']))
        env.pop('CAPSTONE_AI_ENABLED')
        missing = subprocess.run(['docker', 'compose', '-p', 'capstone-gcp', '-f', str(GCP / 'compose.json'), 'config', '--quiet'],
                                 env=env, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(missing.returncode, 0)

    def test_ai_off_mount_paths_exist_after_preflight(self):
        m = manifest('v5a')
        self.preflight(m)
        env = self.calls[-1][1]
        self.assertTrue(Path(env['RUNTIME_DIR'], 'ai').is_dir() and Path(env['AI_CONTROL_DIR']).is_dir())


class SmokeTest(Harness):
    """8.3-6."""

    def answers(self, values):
        replies = iter(values)

        class Reply:
            status = 200

            def __init__(self, data):
                self.data = data

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self, *a):
                return self.data

        def opener(url, timeout):
            if url.endswith('/api/capabilities'):
                return Reply(json.dumps(next(replies)).encode())
            if '18080' in url:
                return Reply(b'{"status": "UP"}')
            return Reply(b'<html><div id="root"></div></html>')
        return opener

    def smoke(self, target, m, capabilities):
        clock = iter(range(0, 100000, 7))
        with patch.object(host.urllib.request, 'urlopen', side_effect=self.answers(capabilities)), \
                patch.object(host.time, 'sleep'), patch.object(host.time, 'monotonic', side_effect=lambda: next(clock)):
            target.smoke(m)

    def test_expectations_by_release_and_mode(self):
        on, off = ai_manifest(), manifest('v5a')
        true3 = dict.fromkeys(host.CAPABILITIES, True)
        false3 = dict.fromkeys(host.CAPABILITIES, False)
        mixed = dict(true3, graphEnabled=False)
        target = self.host()
        self.smoke(target, off, [false3])
        self.smoke(target, on, [false3, mixed, true3])
        for m, values in [(off, [true3] * 5), (on, [false3] * 5), (on, [mixed] * 5)]:
            with self.subTest(release=m['releaseId'], values=values[0]), self.assertRaises(ReleaseError):
                self.smoke(target, m, values)
        target.ai_strict = False
        self.smoke(target, on, [false3])
        self.smoke(target, on, [true3])
        with self.assertRaises(ReleaseError):
            self.smoke(target, on, [mixed] * 5)

    def test_ai_on_smoke_failure_recovers_previous_ai_off_release(self):
        v5a, v5b = manifest('v5a'), ai_manifest()
        platform = Fake()
        platform.cloud.state = {'current': v5a, 'previous': manifest('v4')}

        def smoke(m):
            require(m['aiEnabled'] is not True, 'Capabilities did not match the release')
        platform.smoke = smoke
        deploy = request(v5b, op='v5b-deploy', expected='v5a')
        with self.assertRaises(ReleaseError):
            engine.run(platform, deploy)
        self.assertTrue(platform.closed)
        self.assertEqual(engine.run(platform, deploy), 'rolled-back')
        self.assertEqual(platform.cloud.state['current'], v5a)
        self.assertEqual([e[1] for e in platform.events if isinstance(e, tuple) and e[0] == 'activate'], ['v5b', 'v5a'])
        self.assertFalse(platform.closed)


class RollbackTest(unittest.TestCase):
    """8.3-7: only releases sharing the installed tool revision roll back to each other."""

    def platform(self, state, revision):
        platform = Fake()
        platform.cloud.state = state

        def preflight(m, history, resume=False):
            require(revision == m['bootstrapRevision'], 'Bootstrap revision mismatch')
        platform.preflight = preflight
        return platform

    def test_rollback_matrix(self):
        tools_t, tools_old = 'd' * 40, 'a' * 40
        v4 = dict(manifest('v4'), bootstrapRevision=tools_old)
        v5a = dict(manifest('v5a'), bootstrapRevision=tools_t)
        v5b = dict(ai_manifest(), bootstrapRevision=tools_t)
        platform = self.platform({'current': v5b, 'previous': v5a}, tools_t)
        self.assertEqual(engine.run(platform, request(v5a, 'rollback', 'v5b-back', 'v5b')), 'done')
        self.assertEqual(platform.cloud.state, {'current': v5a, 'previous': v5b})
        platform = self.platform({'current': v5a, 'previous': v4}, tools_t)
        with self.assertRaisesRegex(ReleaseError, 'Bootstrap'):
            engine.run(platform, request(v4, 'rollback', 'v5a-back', 'v5a'))
        self.assertEqual(platform.journals, {})
        platform = self.platform({'current': v5b, 'previous': v5a}, tools_t)
        with self.assertRaisesRegex(ReleaseError, 'previous manifest'):
            engine.run(platform, request(v4, 'rollback', 'v5b-to-v4', 'v5b'))
        self.assertEqual(platform.journals, {})


class AiSuspendTest(unittest.TestCase):
    """8.3-8."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for patcher in [patch.object(host, 'ROOT', self.root / 'state'), patch.object(host, 'ROOT_UID', os.getuid()),
                        patch.object(host, 'trusted', side_effect=lambda path: json.loads(path.read_text())),
                        patch.dict(os.environ, {'SUDO_USER': 'operator_1'})]:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.platform = object.__new__(Host)
        self.platform.scope = COLD_SCOPE
        self.control = self.root / 'state' / COLD_SCOPE / 'ai-control'

    def main(self, *args):
        output = io.StringIO()
        with patch.object(host, 'Host', return_value=self.platform), patch.object(host.sys, 'argv', ['host', *args]), \
                contextlib.redirect_stdout(output):
            host.main()
        return json.loads(output.getvalue())

    def log(self):
        return [json.loads(line) for line in (self.root / 'state' / COLD_SCOPE / 'ai-control.log').read_text().splitlines()]

    def test_on_off_idempotent_and_logged(self):
        self.assertEqual(self.main('--ai-suspend', 'status'),
                         {'aiSuspended': False, 'since': None, 'by': None, 'controlDirectory': 'missing'})
        first = self.main('--ai-suspend', 'on')
        self.assertEqual((first['aiSuspended'], first['by'], first['controlDirectory']), (True, 'operator_1', 'ok'))
        self.assertEqual(self.control.stat().st_mode & 0o7777, 0o755)
        self.assertEqual((self.control / 'suspended').stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.main('--ai-suspend', 'on')['since'], first['since'])
        self.assertEqual(self.main('--ai-suspend', 'off')['aiSuspended'], False)
        self.assertEqual(self.main('--ai-suspend', 'off')['aiSuspended'], False)
        self.assertTrue(self.control.is_dir())
        self.assertEqual([(r['action'], r['result'], r['by']) for r in self.log()],
                         [('ai-suspend-on', 'ok', 'operator_1')] * 2 + [('ai-suspend-off', 'ok', 'operator_1')] * 2)

    def test_does_not_wait_for_release_lock_and_ignores_hold_and_pending(self):
        (self.root / 'state' / COLD_SCOPE / 'operations').mkdir(parents=True)
        hold = self.root / 'state' / COLD_SCOPE / 'maintenance-hold.json'
        hold.write_text('{"since":"x","by":null}')
        journal = self.root / 'state' / COLD_SCOPE / 'operations' / 'stuck.json'
        journal.write_text('{"operationId":"stuck","phase":"switching"}')
        before = {p: p.read_bytes() for p in (hold, journal)}
        with (self.root / 'state' / 'release.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            done = []
            worker = threading.Thread(target=lambda: done.append(self.main('--ai-suspend', 'on')), daemon=True)
            worker.start()
            worker.join(10)
            self.assertFalse(worker.is_alive(), '--ai-suspend waited for release.lock')
        self.assertTrue(done[0]['aiSuspended'])
        self.assertEqual({p: p.read_bytes() for p in (hold, journal)}, before)

    def test_untrusted_control_directory_is_refused(self):
        (self.root / 'elsewhere').mkdir()
        self.control.parent.mkdir(parents=True)
        self.control.symlink_to(self.root / 'elsewhere')
        with self.assertRaises(ReleaseError):
            self.main('--ai-suspend', 'on')
        self.assertFalse((self.root / 'elsewhere' / 'suspended').exists())
        self.assertEqual(self.log()[-1]['result'], 'failed')
        self.assertEqual(self.main('--ai-suspend', 'status')['controlDirectory'], 'untrusted')

    def test_invalid_combinations_rejected_before_host_access(self):
        for args in [['--ai-suspend', 'maybe'], ['--ai-suspend'], ['--ai-suspend', 'on', '--resume'],
                     ['--ai-suspend', 'on', '--maintenance', 'on'], ['--ai-suspend', 'off', '--operation', 'op1']]:
            with self.subTest(args=args), patch.object(host, 'Host') as constructor, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
                with patch.object(host.sys, 'argv', ['host', *args]):
                    host.main()
            self.assertEqual(exit.exception.code, 2)
            constructor.assert_not_called()


if __name__ == '__main__':
    unittest.main()
