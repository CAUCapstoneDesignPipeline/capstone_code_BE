"""Strict, non-secret release contract shared by runner and root host."""
import hashlib
import json
import re

REGISTRY = '378040395204.dkr.ecr.ap-northeast-2.amazonaws.com'
RELEASE = r'[a-z0-9][a-z0-9-]{0,63}'
SHA = r'[a-f0-9]{40}'
DIGEST = r'sha256:[a-f0-9]{64}'
PIPELINE_PROTOCOL = '4'
AI_OFF_SMOKE = {'path':'/api/capabilities','expected':{'analysisEnabled':False,'graphEnabled':False,'discoveriesEnabled':False}}

class ReleaseError(Exception):
    pass

def require(condition, message):
    if not condition:
        raise ReleaseError(message)

def keys(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected), 'Unexpected manifest fields')

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)

def checksum(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()

def validate(m):
    keys(m, ['schemaVersion','releaseId','mode','be','web','configRevision',
             'secretVersions','flyway','gates','aiEnabled','aiImage'])
    require(type(m['schemaVersion']) is int and m['schemaVersion'] == 2, 'Unsupported manifest version')
    require(isinstance(m['releaseId'], str) and re.fullmatch(RELEASE, m['releaseId']), 'Invalid release ID')
    # notes-web is deliberately closed until actual FE auth/P4 has been implemented.
    require(m['mode'] == 'api-only', 'Only api-only is approved by this pipeline implementation')
    require(m['aiEnabled'] is False and m['aiImage'] is None, 'AI must remain disabled')
    for name in ['be','web']:
        keys(m[name], ['sourceSha','digest'])
        require(isinstance(m[name]['sourceSha'], str) and re.fullmatch(SHA, m[name]['sourceSha']), 'Invalid source SHA')
        require(isinstance(m[name]['digest'], str) and re.fullmatch(DIGEST, m[name]['digest']), 'Digest pin required')
    require(isinstance(m['configRevision'], str) and re.fullmatch(RELEASE, m['configRevision']), 'Invalid configuration revision')
    keys(m['secretVersions'], ['dbPassword','jwtSecret','googleClientSecret','allowedEmails'])
    for name, version in m['secretVersions'].items():
        require(type(version) is int and version >= (1 if name in ['dbPassword','jwtSecret'] else 0), 'Invalid secret version')
    keys(m['flyway'], ['before','after','rollbackCompatibleWith','backup'])
    for name in ['before','after']:
        require(isinstance(m['flyway'][name], str) and re.fullmatch(r'empty|[0-9]+', m['flyway'][name]), 'Invalid Flyway version')
    require(isinstance(m['flyway']['rollbackCompatibleWith'], list) and all(isinstance(x, str) and re.fullmatch(r'[0-9]+', x) for x in m['flyway']['rollbackCompatibleWith']), 'Explicit rollback compatibility required')
    backup=m['flyway']['backup']
    if backup is not None:
        keys(backup, ['snapshotId','confirmedAt','dbIdentifier'])
        require(backup['dbIdentifier'] == 'capstone-prod', 'Wrong backup DB')
        require(isinstance(backup['snapshotId'], str) and re.fullmatch(r'[a-z][a-z0-9-]{0,254}', backup['snapshotId']), 'Invalid snapshot identifier')
        require(isinstance(backup['confirmedAt'], str) and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z', backup['confirmedAt']), 'Invalid snapshot evidence time')
    keys(m['gates'], ['aiOffVerified'])
    require(m['gates']['aiOffVerified'] is True, 'Unverified AI-off image')
    require(len(canonical(m).encode()) <= 3500, 'Manifest too large')
    return m

def image(m, service):
    return REGISTRY + '/capstone/' + ('be' if service == 'be' else 'web') + '@' + m[service]['digest']

def version(history):
    require(all(row['success'] for row in history), 'Failed Flyway migration requires investigation')
    versions=[str(row['version']) for row in history if row['version'] is not None]
    return versions[-1] if versions else 'empty'

def rollback_allowed(target, history):
    return version(history) in target['flyway']['rollbackCompatibleWith']

def transition(platform, target, expected, action):
    """Caller holds flock; never mutate schema or repair a failed migration."""
    validate(target)
    current=platform.current()
    require((current['releaseId'] if current else 'none') == expected, 'Stale expected release')
    require(not current or current['releaseId'] != target['releaseId'], 'Release ID already current')
    require(platform.unused_release(target, action), 'Release ID reuse with different content')
    before=platform.history()
    actual=version(before)
    if action == 'rollback':
        previous=platform.previous()
        require(previous is not None and checksum(previous) == checksum(target), 'Rollback must target previous successful manifest')
        require(rollback_allowed(target, before), 'Previous image is not approved for current schema')
    else:
        require(action == 'deploy', 'Invalid operation')
        require(target['flyway']['before'] == actual, 'Unexpected current schema')
        if actual != 'empty' and actual != target['flyway']['after']:
            platform.backup_ready(target)
    platform.preflight(target)  # TLS, DB, secrets, image labels and pull; no service mutation
    platform.begin(target, current)
    platform.maintenance(True)
    committing=False
    try:
        platform.stop_be()  # grace period/drain while public writes are blocked
        platform.start_be(target)
        platform.wait_be()
        after=platform.history()
        require(version(after) == (actual if action == 'rollback' else target['flyway']['after']), 'Unexpected resulting schema')
        platform.start_web(target)
        platform.smoke(target)
        # Keep ingress closed until durable state and SSM metadata have been committed.
        committing=True
        platform.commit(target, current)
        platform.maintenance(False)
        platform.smoke_public(target)
        platform.finish()
    except Exception:
        # Even a same version with changed checksum or failed row is unsafe.
        try:
            after=platform.history()
            if not committing and current and after == before and rollback_allowed(current, after):
                platform.restore(current)
                platform.wait_be()
                platform.smoke(current)
                platform.maintenance(False)
                platform.smoke_public(current)
                platform.finish()
            else:
                platform.maintenance(True)
        except Exception:
            platform.maintenance(True)
        raise ReleaseError('Release failed; inspect maintenance and recorded state (details suppressed)') from None
