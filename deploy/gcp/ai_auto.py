#!/usr/bin/env python3
"""Automatic AI release from an AI develop push: widen the BE attestation, switch the AI, narrow it again.

BE quarantines AI on the first response whose meta matches no attested stamp, and the stamps change with the AI
code. So one AI release is four steps, each its own job in gcp-ai-cd-reusable.yml:

  plan     (app deployer)  read both recorded states and the installed attestation; prove the offline stamps
                           reproduce it; derive the AI target; gate schema/profiles; compute widened/narrow
                           attestations. Emits a bundle (job output) for the next steps.
  widen    (app deployer)  if the stamps change: app release whose attestation accepts current AND target stamps
  ai       (AI deployer)   AI release through the AI host protocol (deploy/gcp/submit.py of the AI repository)
  narrow   (app deployer)  app release pinning the target AI image, release and its own attestation

Every app release goes through the host as in auto.py (expected release, journal, smoke incl. capabilities);
the AI host checks its own expected release and rolls back on failure. If a later step fails, the app keeps
accepting both AIs, so the running AI stays usable and a rerun continues.

  ai_auto.py validate | plan | widen | ai | narrow
"""
import base64
import copy
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

import ai_attestation as att
import auto
from contract import ReleaseError, checksum, match, require, validate, validate_request, ai_enabled, DIGEST, ID, SHA
import host
import pipeline
from storage import Cloud

REPOSITORY = 'CAUCapstoneDesignPipeline/capstone_code_AI'
REPOSITORY_ID = '1397258740'
REF = 'refs/heads/develop'
WORKFLOW = REPOSITORY + '/.github/workflows/gcp-cd.yml@' + REF
AI_BUCKET = 'capsnote-169516793433-ai-releases'
AI_IMAGE = re.escape('asia-northeast3-docker.pkg.dev/capsnote/capstone-ai/ai@') + DIGEST
AI_CONTENT = '/etc/capstone-gcp/ai/'
# The pinned IDF object is reused while the concept extraction code that reads it is unchanged
# (AI manifest.py requires idf.sourceSha == sourceSha; the AI server also refuses an IDF config mismatch).
IDF_CODE = 'app/services/concepts'
STEPS = ('validate', 'plan', 'widen', 'ai', 'narrow')


# ------------------------------------------------------------------------------------------- identity
def identity(env, root):
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_REPOSITORY_ID') == REPOSITORY_ID
            and env.get('GITHUB_REPOSITORY_OWNER_ID') == pipeline.OWNER, 'Unapproved caller')
    require(env.get('GITHUB_REF') == REF and env.get('GITHUB_EVENT_NAME') in auto.EVENTS, 'Develop push or dispatch only')
    require(env.get('GITHUB_WORKFLOW_REF') == WORKFLOW, 'Wrong caller workflow')
    require(env.get('GCP_DEPLOY_ENABLED') == 'true', 'Deployment disabled')
    operation = env.get('OPERATION')
    require(operation in ('deploy', 'rollback'), 'Invalid operation')
    require(match(SHA, env.get('DEPLOY_TOOL_SHA')), 'Immutable tool required')
    actual = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    require(actual == env['DEPLOY_TOOL_SHA'], 'Tool checkout mismatch')
    if operation == 'deploy':
        require(match(SHA, env.get('SOURCE_SHA')) and env.get('SOURCE_SHA') == env.get('GITHUB_SHA'), 'Deploy only the pushed commit')
        require(match(DIGEST, env.get('IMAGE_DIGEST')), 'Immutable digest required')
    return operation


# ------------------------------------------------------------------------------------- pure planning
def ai_release_id(source, now):
    return 'cd-ai-%s-%s' % (time.strftime('%Y%m%d%H%M%S', time.gmtime(now)), source[:7])


def derive_ai(base, source, digest, now):
    """Target AI manifest: the recorded one with this image and source; host bindings and bootstrap stay."""
    require(base.get('kind') == 'ai' and base.get('schemaVersion') == 1, 'Recorded AI manifest required')
    target = copy.deepcopy(base)
    target['image'] = base['image'].split('@', 1)[0] + '@' + digest
    target['sourceSha'] = source
    if (target['image'], target['sourceSha']) == (base['image'], base['sourceSha']):
        return None
    target['idf'] = dict(base['idf'], sourceSha=source)
    target['releaseId'] = ai_release_id(source, now)
    require(match(AI_IMAGE, target['image']) and match(ID, target['releaseId']), 'Invalid AI target')
    return target


def app_release(base, kind, ai, attestation_sha, now):
    """App manifest pinning `ai` (image and release) and the attestation; everything else is the recorded release."""
    validate(base)
    require(ai_enabled(base), 'App release must already have AI on')
    target = copy.deepcopy(base)
    target['aiImage'] = ai['image']
    target['ai']['release'] = ai['releaseId']
    target['ai']['attestationSha256'] = attestation_sha
    if target == base:
        return None
    flyway = base['flyway']
    require(flyway['after'] in flyway['rollbackCompatibleWith'], 'Base schema must stay rollback compatible')
    target['flyway']['before'] = flyway['after']
    target['releaseId'] = 'cd-%s-%s-%s' % (kind, time.strftime('%Y%m%d%H%M%S', time.gmtime(now)), ai['sourceSha'][:7])
    return validate(target)


def plan(app, ai_state, ai_current, ai_previous, installed, sources, be_schemas, exported, operation, source, digest, now):
    """Bundle for the later steps, or {'noop': True}. Raises ReleaseError when a gate stops the release.

    app          recorded app state {'current','previous'} (manifests)
    ai_state     recorded AI state {'current','previous'} (release IDs); ai_current/ai_previous their manifests
    installed    bytes of the attestation file the app release pins
    sources      sha -> ai_attestation.Source for AI commits
    be_schemas   schemas.json of the running BE; exported: the same names exported from the target AI source
    """
    current = app['current']
    validate(current)
    require(ai_enabled(current), 'App release must already have AI on')
    require(ai_current and ai_state['current'] == ai_current['releaseId'], 'Recorded AI manifest missing')
    require(att.sha256(installed) == current['ai']['attestationSha256'], 'Installed attestation differs from its pin')
    document = json.loads(installed)
    pinned = document['releaseManifest']
    require(pinned.get('releaseId') == current['ai']['release'] and pinned.get('image') == current['aiImage'],
            'Installed attestation is for another AI')
    require(pinned['releaseId'] != ai_current['releaseId'] or pinned == ai_current, 'Recorded AI manifest differs from the attested one')
    used = sorted(set(host.ai_profiles(current).values()))
    meta = att.normalized(document['expectedMeta'])
    # The method must reproduce the installed stamps of the pinned AI before it may produce anything new
    # (equal when narrowed, a subset while a widened release is installed).
    again = att.expected(sources[pinned['sourceSha']], pinned, used, document['expectedMeta'])
    require(all(all(s in meta.get(p, []) for s in again[p]) for p in used), 'Offline stamps do not reproduce the installed attestation')
    if pinned['releaseId'] != ai_current['releaseId']:
        # An earlier run switched the AI but did not narrow: finish that first (the AI step sees it is current).
        require(ai_state.get('previous') == pinned['releaseId'], 'App and AI records disagree; reconcile manually')
        print('Finishing the pending narrow step for %s; push or dispatch again for newer commits' % ai_current['releaseId'])
        new = att.expected(sources[ai_current['sourceSha']], ai_current, used, document['expectedMeta'])
        return finish({'noop': False, 'operation': 'deploy', 'expectedAi': pinned['releaseId'], 'currentAi': pinned,
                       'target': ai_current, 'widen': None, 'narrow': att.build(ai_current, new),
                       'sourceSha': ai_current['sourceSha']}, current)
    if operation == 'rollback':
        require(ai_previous and ai_state.get('previous') == ai_previous['releaseId'], 'No previous AI release')
        target = ai_previous
    else:
        target = derive_ai(ai_current, source, digest, now)
        if target is None:
            return {'noop': True}
    tsource = sources[target['sourceSha']]
    require(set(target['profiles']) <= att.listed(tsource), 'Target AI source no longer serves a listed profile')
    if operation == 'deploy':
        old = sources[ai_current['sourceSha']]
        require(old.tree(IDF_CODE) == tsource.tree(IDF_CODE) and old.profiles()[1] == tsource.profiles()[1],
                'Concept extraction changed; the pinned IDF needs a reviewed rebuild (manual release)')
    if exported is None:
        # A rollback returns to an AI that already served this BE contract line; there is no export job for it.
        require(operation == 'rollback', 'Schema export required')
    else:
        problems = att.schema_problems(be_schemas, exported)
        require(not problems, 'Running BE does not accept the target AI responses (sync schemas.json first): ' + ', '.join(problems[:5]))
    new = att.expected(tsource, target, used, document['expectedMeta'])
    narrow = att.build(target, new)
    widen = None
    if any(not all(s in meta.get(p, []) for s in new[p]) for p in used):
        widen = att.build(pinned, att.union(document['expectedMeta'], new))
    return finish({'noop': False, 'operation': operation, 'expectedAi': ai_current['releaseId'], 'currentAi': ai_current,
                   'target': target, 'widen': widen, 'narrow': narrow, 'sourceSha': target['sourceSha']}, current)


def finish(bundle, current):
    """Host attestation checks on what the widen and narrow releases will pin."""
    for doc, release in ((bundle['narrow'], bundle['target']), (bundle['widen'], bundle['currentAi'])):
        if doc is not None:
            view = {'aiImage': release['image'], 'ai': dict(current['ai'], release=release['releaseId'])}
            problems = host.attestation_problems(doc, view)
            require(not problems, 'Attestation self-check failed: ' + ', '.join(problems))
    return bundle


def pack(bundle):
    return base64.b64encode(gzip.compress(json.dumps(bundle, sort_keys=True, separators=(',', ':')).encode())).decode()


def unpack(text):
    return json.loads(gzip.decompress(base64.b64decode(text, validate=True)))


# --------------------------------------------------------------------------------- app host channel
def install_command(digest):
    """Fixed root command writing one hash-named AI content file (root:root 0644, as host.trusted_bytes needs)."""
    require(match(r'[0-9a-f]{64}', digest), 'Invalid content hash')
    final = AI_CONTENT + digest + '.attestation.json'
    temp = AI_CONTENT + '.' + digest + '.tmp'
    return ("sudo sh -c 'umask 022; cat > %s && echo \"%s  %s\" | sha256sum -c --quiet && chown root:root %s && "
            "chmod 0444 %s && mv -f %s %s && echo {}'" % (temp, digest, temp, temp, temp, temp, final))


def read_command(digest):
    require(match(r'[0-9a-f]{64}', digest), 'Invalid content hash')
    return 'sudo cat ' + AI_CONTENT + digest + '.attestation.json'


def remote(channel, command, data=b''):
    result = subprocess.run(channel + [command], input=data, capture_output=True, timeout=90)
    require(result.returncode == 0, 'IAP command failed')
    return result.stdout


def app_step(env, cloud, kind, ai, document, execute, attempts=2):
    """Install `document` on the app host and release the app pinning it, from the freshest recorded state."""
    data = att.content(document)
    digest = att.sha256(data)
    for attempt in range(attempts):
        state, generation = cloud.read('state.json')
        target = app_release(state['current'], kind, ai, digest, time.time())
        if target is None:
            print('App already pins %s; nothing to release' % digest)
            return None
        request = validate_request({'operationId': target['releaseId'], 'operation': 'deploy',
                                    'expectedRelease': state['current']['releaseId'], 'manifest': target,
                                    'manifestHash': checksum(target)})
        print('Releasing %s over %s' % (target['releaseId'], request['expectedRelease']))
        try:
            execute(request, data, digest)
            return request
        except ReleaseError:
            _, latest = cloud.read('state.json')
            if latest == generation or attempt + 1 == attempts:
                raise
            print('Another release committed meanwhile; deriving again from the new state')


# ---------------------------------------------------------------------------------- AI host protocol
def ai_digest(manifest):
    """deploy/gcp/manifest.py digest of the AI repository (canonical JSON, non-ASCII kept)."""
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def ai_request(bundle):
    target = bundle['target']
    return {'operationId': target['releaseId'] if bundle['operation'] == 'deploy' else 'cd-rollback-' + bundle['expectedAi'][:50],
            'operation': bundle['operation'], 'releaseId': target['releaseId'], 'expectedRelease': bundle['expectedAi'],
            'manifestSha256': ai_digest(target)}


def ai_step(env, cloud, bundle, ssh, sleep=time.sleep):
    """Same submission as the AI repository's deploy/gcp/submit.py: immutable objects, detached unit, journal."""
    request = ai_request(bundle)
    require(match(r'[a-z0-9][a-z0-9-]{0,62}', request['operationId']), 'Invalid AI operation ID')
    state, _ = cloud.read('state.json')
    if state and state.get('current') == request['releaseId']:
        print('AI already runs %s' % request['releaseId'])
        return request
    require(state and state.get('current') == request['expectedRelease'], 'AI state changed since planning')
    for key, value in ((f"releases/{request['releaseId']}.json", bundle['target']), (f"operations/{request['operationId']}.json", request)):
        existing, _ = cloud.read(key)
        if existing is None:
            cloud.put(key, value, 0)
        else:
            require(existing == value, 'Immutable AI object conflict')
    operation = request['operationId']
    ssh(f'sudo systemd-run --unit=capstone-ai-{operation} --collect --property=Type=exec --property=UMask=0077 '
        f'--property=TimeoutStartSec=1800 /usr/bin/python3 /opt/capstone-ai/release.py run --operation-id {operation}', check=False)
    for attempt in range(90):
        journal = ssh(f'sudo cat /var/lib/capstone-ai/operations/{operation}.json', check=False)
        if journal is None and attempt:
            # The coordinator writes its journal before any change; no journal and no running unit = rejected input.
            state_text = ssh(f'systemctl is-active capstone-ai-{operation}', check=False)
            require(state_text is not None and state_text.strip() in (b'active', b'activating'),
                    'AI host rejected the request before starting; the current AI keeps running')
        if journal is not None:
            record = json.loads(journal)
            require(record.get('requestHash') == ai_digest(request), 'AI host operation mismatch')
            if record['phase'] == 'succeeded':
                print('AI now runs %s' % request['releaseId'])
                return request
            require(record['phase'] not in ('reverted', 'failed-no-baseline', 'reconcile-required', 'preparation-failed'),
                    'AI host failed (%s); it keeps or restores the previous release' % record['phase'])
        sleep(20)
    raise ReleaseError('AI host still running; rerun continues the same operation')


# ------------------------------------------------------------------------------------------------ main
def token():
    return auto.token()


def git_reader(repo):
    def source(sha):
        require(match(SHA, sha), 'Invalid AI source')
        return att.Source(lambda path: subprocess.check_output(['git', '-C', repo, 'show', '%s:%s' % (sha, path)]),
                          lambda path: subprocess.check_output(['git', '-C', repo, 'rev-parse', '%s:%s' % (sha, path)], text=True).strip())
    return source


class Sources(dict):
    def __init__(self, factory):
        super().__init__()
        self.factory = factory

    def __missing__(self, sha):
        self[sha] = self.factory(sha)
        return self[sha]


def output(**values):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as handle:
        for key, value in values.items():
            handle.write('%s=%s\n' % (key, value))


def main():
    step = sys.argv[1] if len(sys.argv) == 2 else None
    require(step in STEPS, 'Unsupported command')
    root = Path.cwd()
    env = os.environ
    operation = identity(env, root)
    if step == 'validate':
        print('Caller, immutable tool and inputs validated')
        return
    if step == 'ai':
        bundle = unpack(env['BUNDLE'])
        require(env.get('GCP_VM_NAME') == 'capsnote-prod-ai' and env.get('GCP_ZONE') == 'asia-northeast3-a'
                and env.get('GCP_PROJECT_ID') == 'capsnote', 'Wrong AI target')
        base = ['gcloud', 'compute', 'ssh', 'capsnote-prod-ai', '--project', 'capsnote', '--zone', 'asia-northeast3-a',
                '--tunnel-through-iap', '--quiet', '--command']

        def ssh(command, check=True):
            result = subprocess.run(base + [command], capture_output=True, timeout=120)
            if result.returncode != 0:
                require(not check, 'AI host command failed')
                return None
            return result.stdout

        ai_step(env, Cloud(AI_BUCKET, token=token), bundle, ssh)
        return
    require(env.get('GCP_PROJECT_ID') == 'capsnote' and env.get('GCP_ZONE') == 'asia-northeast3-a'
            and env.get('GCP_VM_NAME') == 'capsnote-prod-app', 'Wrong app target')
    app_cloud = Cloud(auto.BUCKET, token=token, prefix=auto.SCOPE)
    if step == 'plan':
        app, _ = app_cloud.read('state.json')
        ai_cloud = Cloud(AI_BUCKET, token=token)
        ai_state, _ = ai_cloud.read('state.json')
        require(ai_state and ai_state.get('current'), 'No recorded AI release')
        ai_current, _ = ai_cloud.read('releases/%s.json' % ai_state['current'])
        ai_previous = ai_cloud.read('releases/%s.json' % ai_state['previous'])[0] if ai_state.get('previous') else None
        with pipeline.connection() as channel:
            installed = remote(channel, read_command(app['current']['ai']['attestationSha256']))
        be_schemas = json.loads(subprocess.check_output(['git', '-C', str(root), 'show', '%s:src/main/resources/ai/schemas.json'
                                                         % app['current']['be']['sourceSha']]))
        exported = unpack(env['SCHEMA_EXPORT']) if env.get('SCHEMA_EXPORT') else None
        bundle = plan(app, ai_state, ai_current, ai_previous, installed, Sources(git_reader(str(root / 'ai-source'))),
                      be_schemas, exported, operation, env.get('SOURCE_SHA'), env.get('IMAGE_DIGEST'), time.time())
        if bundle['noop']:
            print('Production already runs this AI image; nothing to deploy')
        else:
            print('Plan: AI %s -> %s, widen=%s' % (bundle['expectedAi'], bundle['target']['releaseId'], bundle['widen'] is not None))
        output(bundle=pack(bundle), noop=str(bundle['noop']).lower(), widen=str(not bundle['noop'] and bundle['widen'] is not None).lower())
        return
    bundle = unpack(env['BUNDLE'])

    def execute(request, data, digest):
        with pipeline.connection() as channel:
            remote(channel, install_command(digest), data)
            pipeline.execute(channel, request)

    if step == 'widen':
        app_step(env, app_cloud, 'widen', bundle['currentAi'], bundle['widen'], execute)
    else:
        app_step(env, app_cloud, 'narrow', bundle['target'], bundle['narrow'], execute)


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        raise SystemExit('Automatic AI release stopped: ' + str(error)) from None
    except Exception:
        raise SystemExit('Automatic AI release did not complete; inspect operation status; details suppressed') from None
