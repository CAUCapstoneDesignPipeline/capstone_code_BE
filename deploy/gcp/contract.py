"""GCP release protocol. Only reviewed immutable inputs cross the host boundary."""
import hashlib
import json
import re

ID = r'[a-z0-9][a-z0-9-]{0,63}'
SHA = r'[a-f0-9]{40}'
HASH = r'[a-f0-9]{64}'
DIGEST = r'sha256:' + HASH
REGISTRY = 'asia-northeast3-docker.pkg.dev/capsnote/'
SECRETS = {'dbPassword': 'db-password', 'jwtSecret': 'jwt-secret',
           'googleClientSecret': 'google-client-secret', 'allowedEmails': 'allowed-emails'}


class ReleaseError(Exception):
    pass


def require(value, message):
    if not value:
        raise ReleaseError(message)


def match(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def keys(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected), 'Unexpected fields')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def checksum(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def validate(m):
    keys(m, ['schemaVersion', 'releaseId', 'be', 'web', 'proxy', 'configRevision',
             'bootstrapRevision', 'secretVersions', 'flyway', 'aiEnabled', 'aiImage'])
    require(type(m['schemaVersion']) is int and m['schemaVersion'] == 3, 'Unsupported schema')
    require(match(ID, m['releaseId']), 'Invalid release ID')
    require(m['aiEnabled'] is False and m['aiImage'] is None, 'Initial GCP release must remain AI-off')
    for component, repository in [('be', 'capstone-be'), ('web', 'capstone-web')]:
        keys(m[component], ['sourceSha', 'image'])
        require(match(SHA, m[component]['sourceSha']), 'Invalid source SHA')
        require(match(re.escape(REGISTRY + repository + '/') + r'[a-z0-9-]+@' + DIGEST, m[component]['image']), 'Wrong registry or mutable image')
    keys(m['proxy'], ['image'])
    require(match(r'gcr\.io/cloud-sql-connectors/cloud-sql-proxy@' + DIGEST, m['proxy']['image']), 'Proxy digest required')
    require(match(HASH, m['configRevision']) and match(SHA, m['bootstrapRevision']), 'Configuration/bootstrap pins required')
    keys(m['secretVersions'], SECRETS)
    for key, suffix in SECRETS.items():
        require(match(r'projects/capsnote/secrets/capsnote-prod-' + suffix + r'/versions/[1-9][0-9]*', m['secretVersions'][key]), 'Wrong secret scope/version')
    f = m['flyway']
    keys(f, ['before', 'after', 'rollbackCompatibleWith', 'migrations', 'backupReference'])
    require(all(match(r'empty|[1-9][0-9]*', f[k]) for k in ['before', 'after']), 'Invalid schema version')
    require(isinstance(f['rollbackCompatibleWith'], list) and all(match(r'[1-9][0-9]*', v) for v in f['rollbackCompatibleWith']), 'Rollback compatibility required')
    require(isinstance(f['migrations'], list) and bool(f['migrations']), 'Migration inventory required')
    versions = []
    for migration in f['migrations']:
        keys(migration, ['version', 'sha256'])
        require(match(r'[1-9][0-9]*', migration['version']) and match(HASH, migration['sha256']), 'Invalid migration inventory')
        versions.append(migration['version'])
    require(len(versions) == len(set(versions)) and versions == sorted(versions, key=int), 'Migration order/duplicates')
    require(f['after'] in versions, 'Target migration missing')
    require(f['backupReference'] is None or match(r'projects/capsnote/instances/[a-z0-9-]+/backupRuns/[0-9]+', f['backupReference']), 'Invalid backup reference')
    require(len(canonical(m)) < 16384, 'Oversized manifest')
    return m


def validate_request(r):
    keys(r, ['operationId', 'operation', 'expectedRelease', 'manifest', 'manifestHash'])
    require(match(ID, r['operationId']) and match(r'none|' + ID, r['expectedRelease']), 'Invalid operation identity')
    require(r['operation'] in ['deploy', 'rollback'], 'Invalid operation')
    validate(r['manifest'])
    require(checksum(r['manifest']) == r['manifestHash'], 'Manifest checksum mismatch')
    return r


def version(history):
    require(all(row.get('success') is True for row in history), 'Failed Flyway history')
    return next((str(row['version']) for row in reversed(history) if row['version'] is not None), 'empty')
