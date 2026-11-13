#!/usr/bin/env python3
"""Automatic image-only app release from a main push.

The host's own release state (gs://<releases>/<scope>/state.json, written by engine.run with a generation match)
is the base. The next manifest changes only the calling component's image and source; everything else
(secrets, config, proxy, AI pins, bootstrap) is copied. A BE source whose Flyway inventory differs from the base
stops here: schema changes keep the reviewed manual release (gcp-deploy.yml).

  auto.py validate    caller identity and inputs, before cloud authentication
  auto.py             derive, submit over IAP, observe (same host protocol as pipeline.py)
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from contract import ReleaseError, checksum, match, require, validate, validate_request, DIGEST, ID, SHA
import pipeline
from storage import Cloud

BUCKET = 'capsnote-169516793433-releases'
# host.py release_scope() for the cold-start production host (COLD_SCOPE).
SCOPE = 'cold-start/capstone_live/'
WORKFLOW = '/.github/workflows/gcp-cd.yml@refs/heads/main'
EVENTS = ('push', 'workflow_dispatch')
MIGRATIONS = 'src/main/resources/db/migration'
MIGRATION_FILE = re.compile(r'V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql')


def identity(env, root):
    """Same numeric caller rules as pipeline.prepare, for the push/dispatch CD workflow."""
    repo = env.get('GITHUB_REPOSITORY')
    require(repo in pipeline.REPOSITORIES, 'Unapproved caller')
    number, component = pipeline.REPOSITORIES[repo]
    require(env.get('GITHUB_REPOSITORY_ID') == number and env.get('GITHUB_REPOSITORY_OWNER_ID') == pipeline.OWNER,
            'Wrong numeric identity')
    require(env.get('GITHUB_REF') == 'refs/heads/main' and env.get('GITHUB_EVENT_NAME') in EVENTS, 'Main push or dispatch only')
    require(env.get('GITHUB_WORKFLOW_REF') == repo + WORKFLOW, 'Wrong caller workflow')
    require(env.get('GCP_DEPLOY_ENABLED') == 'true', 'Deployment disabled')
    require(env.get('COMPONENT') == component, 'Caller may only select its own component')
    operation = env.get('OPERATION')
    require(operation in ('deploy', 'rollback'), 'Invalid operation')
    require(match(SHA, env.get('DEPLOY_TOOL_SHA')), 'Immutable tool required')
    actual = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    require(actual == env['DEPLOY_TOOL_SHA'], 'Tool checkout mismatch')
    if operation == 'deploy':
        require(match(SHA, env.get('SOURCE_SHA')) and env.get('SOURCE_SHA') == env.get('GITHUB_SHA'), 'Deploy only the pushed commit')
        require(match(DIGEST, env.get('IMAGE_DIGEST')), 'Immutable digest required')
    require(env.get('GCP_PROJECT_ID') == 'capsnote' and env.get('GCP_ZONE') == 'asia-northeast3-a'
            and env.get('GCP_VM_NAME') == 'capsnote-prod-app', 'Wrong deployment target')
    return component, operation


def migrations(source):
    """Flyway inventory of a BE checkout in manifest form (sha256 of the file bytes)."""
    rows = []
    for path in sorted((source / MIGRATIONS).iterdir()):
        found = MIGRATION_FILE.fullmatch(path.name)
        require(found is not None and path.is_file() and not path.is_symlink(), 'Unexpected migration file')
        rows.append({'version': found.group(1), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    rows.sort(key=lambda row: int(row['version']))
    require(rows and len({row['version'] for row in rows}) == len(rows), 'Migration inventory required')
    return rows


def release_id(component, source, now):
    return 'cd-%s-%s-%s' % (component, time.strftime('%Y%m%d%H%M%S', time.gmtime(now)), source[:7])


def derive(base, component, source, digest, inventory, now):
    """Next manifest, or None when the base already runs exactly this image."""
    validate(base)
    require(component in ('be', 'web'), 'Unsupported component')
    repository = base[component]['image'].split('@', 1)[0]
    target = {'sourceSha': source, 'image': repository + '@' + digest}
    if base[component] == target:
        return None
    flyway = base['flyway']
    if component == 'be':
        require(inventory == flyway['migrations'], 'Migration inventory changed; use the reviewed manual release')
    # An image-only release starts and ends at the base's schema.
    require(flyway['after'] in flyway['rollbackCompatibleWith'], 'Base schema must stay rollback compatible')
    manifest = copy.deepcopy(base)
    manifest[component] = target
    manifest['releaseId'] = release_id(component, source, now)
    manifest['flyway']['before'] = flyway['after']
    return validate(manifest)


def plan(state, component, operation, source=None, digest=None, inventory=None, now=None):
    """Host request for the recorded state, or None when there is nothing to do."""
    require(isinstance(state, dict) and state.get('current'), 'No recorded current release')
    current = state['current']
    if operation == 'rollback':
        require(state.get('previous'), 'No previous release to roll back to')
        manifest = validate(copy.deepcopy(state['previous']))
        operation_id = 'cd-rollback-' + current['releaseId'][:51]
    else:
        manifest = derive(current, component, source, digest, inventory, time.time() if now is None else now)
        if manifest is None:
            return None
        operation_id = manifest['releaseId']
    require(match(ID, operation_id), 'Invalid operation ID')
    return validate_request({'operationId': operation_id, 'operation': operation, 'expectedRelease': current['releaseId'],
                             'manifest': manifest, 'manifestHash': checksum(manifest)})


def token():
    return subprocess.run(['gcloud', 'auth', 'print-access-token'], check=True, capture_output=True,
                          text=True, timeout=30).stdout.strip()


def run(env, root, cloud, execute, attempts=2):
    component, operation = identity(env, root)
    inventory = migrations(root / 'source') if component == 'be' and operation == 'deploy' else None
    for attempt in range(attempts):
        state, generation = cloud.read('state.json')
        request = plan(state, component, operation, env.get('SOURCE_SHA'), env.get('IMAGE_DIGEST'), inventory)
        if request is None:
            print('Production already runs this image; nothing to deploy')
            return None
        print('Releasing %s over %s' % (request['manifest']['releaseId'], request['expectedRelease']))
        try:
            execute(request)
            return request
        except ReleaseError:
            # Retry only when another release committed meanwhile (stale expected release); never after our own failure.
            _, latest = cloud.read('state.json')
            if latest == generation or attempt + 1 == attempts or operation == 'rollback':
                raise
            print('Another release committed meanwhile; deriving again from the new state')


def main():
    root = Path.cwd()
    if sys.argv[1:] == ['validate']:
        identity(os.environ, root)
        print('Caller, immutable tool and inputs validated')
        return
    require(not sys.argv[1:], 'Unsupported command')

    def execute(request):
        with pipeline.connection() as channel:
            pipeline.execute(channel, request)

    run(os.environ, root, Cloud(BUCKET, token=token, prefix=SCOPE), execute)


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        raise SystemExit('Automatic release stopped: ' + str(error)) from None
    except Exception:
        raise SystemExit('Automatic release did not complete; inspect operation status; details suppressed') from None
