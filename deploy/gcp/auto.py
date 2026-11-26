#!/usr/bin/env python3
"""Automatic app release from a main push.

The host's own release state (gs://<releases>/<scope>/state.json, written by engine.run with a generation match)
is the base. The next manifest changes only the calling component's image and source; everything else
(secrets, config, proxy, AI pins, bootstrap) is copied.

A BE source that only appends Flyway migrations also moves the schema: before submitting, this takes an on-demand
Cloud SQL backup and writes the host's backup evidence (the same file host.py preflight requires of a reviewed
manual release), then the manifest records the backup and the new inventory. A source that edits, removes or
reorders an applied migration still stops here and keeps the reviewed manual release (gcp-deploy.yml).

  auto.py validate    caller identity and inputs, before cloud authentication
  auto.py             derive, submit over IAP, observe (same host protocol as pipeline.py)
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import urllib.request

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
SQL = 'https://sqladmin.googleapis.com/v1/projects/capsnote/'
INSTANCE = 'capsnote-prod-pg16'
BACKUP_REFERENCE = 'projects/capsnote/instances/capsnote-prod-pg16/backupRuns/%s'
BACKUP_WAIT = 1200


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


def migration_target(flyway, inventory):
    """Flyway version a BE inventory moves the base schema to, or None when it adds nothing.

    Only appended migrations are automatic: every applied one must keep its version and bytes."""
    applied = flyway['migrations']
    require(isinstance(inventory, list) and inventory, 'Migration inventory required')
    if inventory == applied:
        return None
    require(inventory[:len(applied)] == applied and applied[-1]['version'] == flyway['after'],
            'Applied migration changed; use the reviewed manual release')
    return inventory[-1]['version']


def derive(base, component, source, digest, inventory, now, backup=None):
    """Next manifest, or None when the base already runs exactly this image.

    backup is the Cloud SQL backup reference taken for this release; it is required exactly when the BE
    inventory appends migrations."""
    validate(base)
    require(component in ('be', 'web'), 'Unsupported component')
    repository = base[component]['image'].split('@', 1)[0]
    target = {'sourceSha': source, 'image': repository + '@' + digest}
    if base[component] == target:
        return None
    flyway = base['flyway']
    after = migration_target(flyway, inventory) if component == 'be' else None
    manifest = copy.deepcopy(base)
    manifest[component] = target
    manifest['releaseId'] = release_id(component, source, now)
    if after is None:
        require(backup is None, 'Backup without a migration')
        # An image-only release starts and ends at the base's schema.
        require(flyway['after'] in flyway['rollbackCompatibleWith'], 'Base schema must stay rollback compatible')
        manifest['flyway']['before'] = flyway['after']
    else:
        require(backup is not None, 'Migration release requires a backup')
        # Older images cannot start on the new history (host check_image_migrations), so only the new schema is
        # rollback compatible; going back means restoring this backup.
        manifest['flyway'] = {'before': flyway['after'], 'after': after, 'rollbackCompatibleWith': [after],
                              'migrations': copy.deepcopy(inventory), 'backupReference': backup}
    return validate(manifest)


def needs_backup(state, component, operation, source, digest, inventory):
    """True when a deploy of this source would migrate the recorded current schema."""
    current = (state or {}).get('current')
    if component != 'be' or operation != 'deploy' or not current:
        return False
    validate(current)
    repository = current['be']['image'].split('@', 1)[0]
    if current['be'] == {'sourceSha': source, 'image': repository + '@' + digest}:
        return False
    return migration_target(current['flyway'], inventory) is not None


def plan(state, component, operation, source=None, digest=None, inventory=None, now=None, backup=None):
    """Host request for the recorded state, or None when there is nothing to do."""
    require(isinstance(state, dict) and state.get('current'), 'No recorded current release')
    current = state['current']
    if operation == 'rollback':
        require(state.get('previous'), 'No previous release to roll back to')
        manifest = validate(copy.deepcopy(state['previous']))
        operation_id = 'cd-rollback-' + current['releaseId'][:51]
    else:
        manifest = derive(current, component, source, digest, inventory, time.time() if now is None else now, backup)
        if manifest is None:
            return None
        operation_id = manifest['releaseId']
    require(match(ID, operation_id), 'Invalid operation ID')
    return validate_request({'operationId': operation_id, 'operation': operation, 'expectedRelease': current['releaseId'],
                             'manifest': manifest, 'manifestHash': checksum(manifest)})


def token():
    return subprocess.run(['gcloud', 'auth', 'print-access-token'], check=True, capture_output=True,
                          text=True, timeout=30).stdout.strip()


def run(env, root, cloud, execute, attempts=2, backup=None):
    """backup(current) takes the backup and writes the host evidence for current; returns the backup reference."""
    component, operation = identity(env, root)
    inventory = migrations(root / 'source') if component == 'be' and operation == 'deploy' else None
    for attempt in range(attempts):
        state, generation = cloud.read('state.json')
        reference = None
        if needs_backup(state, component, operation, env.get('SOURCE_SHA'), env.get('IMAGE_DIGEST'), inventory):
            require(backup is not None, 'Migration release requires a backup')
            print('Migrations appended; backing up before releasing over %s' % state['current']['releaseId'])
            reference = backup(state['current'])
        request = plan(state, component, operation, env.get('SOURCE_SHA'), env.get('IMAGE_DIGEST'), inventory,
                       backup=reference)
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


# ------------------------------------------------------------------------------------- backup before migrating
def sql(method, path, body=None):
    """Cloud SQL Admin call as the deployer (custom role: backupRuns create/get, instances get for operations)."""
    request = urllib.request.Request(SQL + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token(), 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())


def backup_id_of(operation):
    backup_id = str((operation.get('backupContext') or {}).get('backupId', ''))
    require(re.fullmatch(r'[0-9]{1,20}', backup_id) is not None, 'Cloud SQL backup id missing')
    return backup_id


def backup_run(operation, run, now):
    """The backup an on-demand backup operation produced, checked before the host relies on it."""
    require(operation.get('status') == 'DONE' and not operation.get('error'), 'Cloud SQL backup failed')
    backup_id = backup_id_of(operation)
    require(str(run.get('id')) == backup_id and run.get('status') == 'SUCCESSFUL' and run.get('type') == 'ON_DEMAND'
            and run.get('instance') == INSTANCE, 'Cloud SQL backup not usable')
    return {'id': backup_id, 'reference': BACKUP_REFERENCE % backup_id, 'endTime': run.get('endTime'), 'takenAt': int(now)}


def take_backup(release):
    operation = sql('POST', 'instances/%s/backupRuns' % INSTANCE, {'description': 'before automatic release over ' + release})
    require(re.fullmatch(r'[A-Za-z0-9-]{1,100}', operation.get('name', '')) is not None, 'Cloud SQL operation missing')
    deadline = time.time() + BACKUP_WAIT
    while operation.get('status') != 'DONE':
        require(time.time() < deadline, 'Cloud SQL backup timed out')
        time.sleep(10)
        operation = sql('GET', 'operations/' + operation['name'])
    return backup_run(operation, sql('GET', 'instances/%s/backupRuns/%s' % (INSTANCE, backup_id_of(operation))), time.time())


# Runs as root on the app host (the deployer already has OS Login admin there) with the installed host tools.
# Under release.lock it re-reads the recorded state, refuses a stale release, a pending operation or an operator
# hold, reads the live Flyway history and writes the evidence host.py preflight compares: same reference,
# SUCCESSFUL, the same history, confirmed within the hour.
EVIDENCE_PROGRAM = r'''
import fcntl, json, os, sys, time
sys.dont_write_bytecode = True
sys.path.insert(0, '/opt/capstone-gcp')
import host
args = json.load(sys.stdin)
fd = os.open(str(host.ROOT / 'release.lock'), os.O_RDONLY)
try:
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    h = host.Host()
    state, _ = h.cloud.read('state.json')
    current = (state or {}).get('current') or {}
    host.require(current.get('releaseId') == args['expectedRelease'], 'Stale expected release')
    host.require(not h.pending(), 'Previous operation requires recovery')
    host.require(not os.path.lexists(str(h.hold_path())), 'Operator maintenance hold')
    history = h.history(current)
    host.require(host.version(history) == current['flyway']['after'], 'Unexpected migration history')
    value = {'reference': args['backup']['reference'], 'status': 'SUCCESSFUL', 'history': history,
             'confirmedAtEpoch': int(time.time()), 'backup': args['backup'], 'expectedRelease': args['expectedRelease'],
             'writer': 'deploy/gcp/auto.py'}
    host.atomic(host.CONFIG / 'backup-evidence.json', host.canonical(value), 0o600)
    print(json.dumps({'reference': value['reference'], 'version': host.version(history)}))
finally:
    os.close(fd)
'''


def write_evidence(channel, current, backup):
    reply = pipeline.ssh(channel, 'sudo /usr/bin/python3 -c ' + shlex.quote(EVIDENCE_PROGRAM),
                         json.dumps({'expectedRelease': current['releaseId'], 'backup': backup}))
    require(reply == {'reference': backup['reference'], 'version': current['flyway']['after']}, 'Backup evidence not recorded')


def report(reason):
    """Expose why the release stopped to the caller's notification job."""
    path = os.environ.get('GITHUB_OUTPUT')
    if path:
        with open(path, 'a') as output:
            output.write('stop_reason=' + reason.replace('\n', ' ')[:300] + '\n')


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

    def backup(current):
        taken = take_backup(current['releaseId'])
        print('Backup %s taken' % taken['reference'])
        with pipeline.connection() as channel:
            write_evidence(channel, current, taken)
        return taken['reference']

    run(os.environ, root, Cloud(BUCKET, token=token, prefix=SCOPE), execute, backup=backup)


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        report(str(error))
        raise SystemExit('Automatic release stopped: ' + str(error)) from None
    except Exception:
        report('did not complete; inspect operation status')
        raise SystemExit('Automatic release did not complete; inspect operation status; details suppressed') from None
