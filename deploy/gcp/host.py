#!/usr/bin/env python3
"""Ubuntu host entry point; no AWS SDK, shell-evaluated input or credential output."""
import argparse
import fcntl
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
import uuid
import zlib
from contract import (ReleaseError, canonical, checksum, match, require, validate, validate_request, version, ID,
                      AI_OPERATIONS, AI_TOKEN_SECRET, HASH, SHA)
from storage import Cloud
import engine

ROOT = Path('/var/lib/capstone-gcp')
CONFIG = Path('/etc/capstone-gcp')
TOOLS = Path('/opt/capstone-gcp')
RUNTIME = Path('/run/capstone-gcp')
NGINX = Path('/etc/nginx/conf.d/capstone-gcp.conf')

APP_UID = 10001
ROOT_UID = 0
# Container paths of the two read-only AI mounts in compose.json.
AI_MOUNT = '/run/capstone-ai'
AI_CONTROL_MOUNT = '/run/capstone-ai-control'
# Public content files under CONFIG/ai/<sha256><suffix>, copied to RUNTIME/<release>/ai/<name>.
AI_CONTENT = (('policy.json', 'policySha256', '.policy.json'), ('attestation.json', 'attestationSha256', '.attestation.json'),
              ('ca.pem', 'caSha256', '.ca.pem'))
AI_CONFIG = ['baseUrl', 'serverName', 'privateIp', 'serviceTokenSecret']
AI_PORT = 8443
# BE AiPipelineConfiguration defaults once the #110 contract sync lands (facts-llm-3, derive-sound-1);
# manifest ai.profiles overrides them per operation like capstone.ai.pipeline.profiles.<operation>.
AI_DEFAULT_PROFILES = {'extract': 'extract-det-3', 'embeddings': 'embeddings-bge-m3-1', 'merge': 'merge-det-1',
                       'bridges': 'bridges-det-1', 'edges': 'edges-det-1', 'explain': 'explain-llm-8',
                       'verify-explanation': 'explanation-det-3', 'links': 'links-det-1', 'facts': 'facts-llm-3',
                       'derive': 'derive-sound-1'}
AI_EMBEDDING = {'profile': 'embeddings-bge-m3-1', 'name': 'BAAI/bge-m3', 'revision': '5617a9f61b028005a4858fdac845db406aefb181'}
STAMP_LIMITS = {'profileVersion': 80, 'provider': 80, 'model': 120, 'modelRevision': 120, 'promptVersion': 80,
                'schemaVersion': 3, 'preprocessingVersion': 80}
CAPABILITIES = ('analysisEnabled', 'graphEnabled', 'discoveriesEnabled')


def command(args, *, env=None, data=None):
    result = subprocess.run(args, env=env, input=data, text=True, capture_output=True, timeout=600)
    require(result.returncode == 0, 'Host command failed (output suppressed)')
    return result.stdout


def atomic(path, value, mode=0o600):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.write-')
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb' if isinstance(value, bytes) else 'w') as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def trusted(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not (info.st_mode & 0o022), 'Untrusted host file')
    return json.loads(path.read_text())


def trusted_bytes(path):
    try:
        info = path.lstat()
    except FileNotFoundError:
        raise ReleaseError('Required host file missing') from None
    require(stat.S_ISREG(info.st_mode) and info.st_uid == ROOT_UID and not (info.st_mode & 0o022), 'Untrusted host file')
    return path.read_bytes()


def trusted_directory(path):
    # A missing or redirected control mount would read as "not suspended" (fail-open), so it is exact.
    try:
        info = path.lstat()
    except FileNotFoundError:
        raise ReleaseError('AI control directory missing') from None
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == ROOT_UID and info.st_mode & 0o7777 == 0o755, 'Untrusted AI control directory')


# --------------------------------------------------------------------- AI (pure checks)
def ai_profiles(m):
    """Operation -> profile the BE uses for this manifest (defaults + ai.profiles)."""
    return dict(AI_DEFAULT_PROFILES, **m['ai']['profiles'])


def stamp_problems(stamp, profile):
    """BE schemas.json ModelStamp (additionalProperties false) plus profileVersion == attestation key."""
    if not isinstance(stamp, dict) or set(stamp) != set(STAMP_LIMITS):
        return ['stamp fields']
    problems = []
    for field, limit in STAMP_LIMITS.items():
        value = stamp[field]
        if field == 'schemaVersion':
            ok = value == '1.0'
        elif field == 'promptVersion':
            ok = value is None or (isinstance(value, str) and len(value) <= limit)
        else:
            ok = isinstance(value, str) and 1 <= len(value) <= limit
        if not ok:
            problems.append('stamp ' + field)
    if stamp['profileVersion'] != profile:
        problems.append('stamp profileVersion differs from key')
    return problems


def expected_stamps(entry):
    """One stamp, or a list of 1-8 stamps (a profile whose meta depends on the input, e.g. derive-sound-1)."""
    if isinstance(entry, dict):
        return [entry]
    return list(entry) if isinstance(entry, list) and 1 <= len(entry) <= 8 else []


def policy_problems(policy):
    """BE schemas.json Policy and AiPipelineConfiguration rule checks."""
    if not isinstance(policy, dict) or set(policy) != {'version', 'relationTypes', 'compositionRules'}:
        return ['policy fields']
    problems = []
    if not (isinstance(policy['version'], str) and 1 <= len(policy['version']) <= 80):
        problems.append('policy version')
    types, rules = policy['relationTypes'], policy['compositionRules']
    if not (isinstance(types, list) and 1 <= len(types) <= 32 and isinstance(rules, list) and len(rules) <= 128):
        return problems + ['policy list sizes']
    codes = set()
    for item in types:
        if not (isinstance(item, dict) and set(item) == {'code', 'label', 'description'}
                and match(r'[a-z][a-z0-9_]{0,39}', item['code']) and isinstance(item['label'], str)
                and 1 <= len(item['label']) <= 80 and isinstance(item['description'], str)
                and 1 <= len(item['description']) <= 1000):
            problems.append('relation type shape')
            continue
        if item['code'] in codes:
            problems.append('duplicate relation type ' + item['code'])
        codes.add(item['code'])
    pairs = set()
    for rule in rules:
        if not (isinstance(rule, dict) and set(rule) == {'firstType', 'secondType', 'resultType'}
                and all(isinstance(v, str) and 1 <= len(v) <= 40 for v in rule.values())):
            problems.append('composition rule shape')
            continue
        if not {rule['firstType'], rule['secondType'], rule['resultType']} <= codes:
            problems.append('composition rule uses an unknown type')
        if (rule['firstType'], rule['secondType']) in pairs:
            problems.append('duplicate composition rule')
        pairs.add((rule['firstType'], rule['secondType']))
    return problems


def attestation_problems(attestation, m):
    """AiPipelineConfiguration attestation checks, plus the manifest's own AI image, release and CA pins."""
    if not isinstance(attestation, dict) or not isinstance(attestation.get('releaseManifest'), dict) \
            or not isinstance(attestation.get('expectedMeta'), dict):
        return ['attestation shape']
    release, expected, ai = attestation['releaseManifest'], attestation['expectedMeta'], m['ai']
    problems = []
    if release.get('kind') != 'ai' or not match(r'.+@sha256:[0-9a-f]{64}', release.get('image')):
        problems.append('release kind/image')
    if release.get('image') != m['aiImage']:
        problems.append('release image differs from aiImage')
    if release.get('releaseId') != ai['release']:
        problems.append('release id differs from ai.release')
    idf, tls = release.get('idf'), release.get('tls')
    if not match(SHA, release.get('sourceSha')) or not (isinstance(idf, dict) and match(HASH, idf.get('sha256'))):
        problems.append('release sourceSha/idf')
    model = release.get('model')
    if not isinstance(model, dict) or any(model.get(k) != v for k, v in AI_EMBEDDING.items()):
        problems.append('release embedding model')
    if not isinstance(tls, dict) or tls.get('caSha256') != ai['caSha256']:
        problems.append('release CA differs from ai.caSha256')
    used = ai_profiles(m)
    if used['embeddings'] != AI_EMBEDDING['profile']:
        problems.append('embeddings profile is fixed')
    listed = release.get('profiles') if isinstance(release.get('profiles'), list) else []
    missing = sorted(set(used.values()) - set(listed))
    if missing:
        problems.append('release profiles missing ' + ','.join(missing))
    for profile in sorted(set(used.values())):
        stamps = expected_stamps(expected.get(profile))
        if not stamps:
            problems.append('expectedMeta missing ' + profile)
        for stamp in stamps:
            problems += ['%s %s' % (profile, p) for p in stamp_problems(stamp, profile)]
    for stamp in expected_stamps(expected.get(used['embeddings'])):
        if isinstance(stamp, dict) and (stamp.get('model'), stamp.get('modelRevision')) != (AI_EMBEDDING['name'], AI_EMBEDDING['revision']):
            problems.append('embedding stamp differs from release model')
    return problems


def ai_config_problems(config, m):
    """Host configuration `ai` block: reviewed endpoint and the token secret the manifest version belongs to."""
    ai = config.get('ai')
    if not isinstance(ai, dict) or set(ai) != set(AI_CONFIG):
        return ['host ai block']
    problems = []
    name = ai['serverName']
    if not match(r'[a-z0-9]([a-z0-9-]{0,62}\.)+internal', name):
        problems.append('serverName')
    if ai['baseUrl'] != 'https://%s:%d' % (name, AI_PORT):
        problems.append('baseUrl')
    try:
        if not ipaddress.IPv4Address(ai['privateIp']).is_private:
            problems.append('privateIp')
    except (ValueError, TypeError):
        problems.append('privateIp')
    if ai['serviceTokenSecret'] != AI_TOKEN_SECRET or m['ai']['serviceToken'].rsplit('/versions/', 1)[0] != ai['serviceTokenSecret']:
        problems.append('service token secret')
    return problems


def compose_ai_host():
    """The fixed extra_hosts entry of compose.json, as (name, address)."""
    entries = json.loads((TOOLS / 'compose.json').read_text())['services']['be'].get('extra_hosts') or []
    require(len(entries) == 1, 'Unexpected compose host entries')
    name, address = entries[0].rsplit(':', 1)
    return name, address


def render_properties(properties):
    def escape(value):
        return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t').replace(' ', '\\ ')
    return ''.join(k + '=' + escape(v) + '\n' for k, v in properties.items())


def token_ok(token):
    # AiHttpClient: at least 16 printable ASCII characters (33-126).
    return isinstance(token, str) and len(token) >= 16 and all(33 <= ord(c) <= 126 for c in token)


COLD_DATABASE = 'capstone_live'
COLD_ROLE = 'capstone_live_app'
COLD_SCOPE = 'cold-start/' + COLD_DATABASE + '/'

# Check catalog objects without sampling application rows. PostgreSQL-owned
# system schemas and the default plpgsql extension exist in a fresh database.
COLD_EMPTY_QUERY = """
WITH app_schemas AS (
    SELECT oid, nspname FROM pg_namespace
    WHERE nspname !~ '^pg_' AND nspname <> 'information_schema'
)
SELECT current_database() = 'capstone_live' AND current_user = 'capstone_live_app'
    AND NOT EXISTS (SELECT FROM pg_roles WHERE rolname = current_user
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member = (current_user::regrole)::oid)
    AND NOT EXISTS (SELECT FROM pg_database WHERE datname = current_database() AND datdba = (current_user::regrole)::oid)
    AND has_schema_privilege(current_user, 'public', 'CREATE')
    AND NOT EXISTS (SELECT FROM app_schemas WHERE nspname <> 'public')
    AND NOT EXISTS (SELECT FROM pg_class WHERE relnamespace IN (SELECT oid FROM app_schemas))
    AND NOT EXISTS (SELECT FROM pg_proc WHERE pronamespace IN (SELECT oid FROM app_schemas))
    AND NOT EXISTS (SELECT FROM pg_type WHERE typnamespace IN (SELECT oid FROM app_schemas))
    AND NOT EXISTS (SELECT FROM pg_depend WHERE refclassid = 'pg_namespace'::regclass
        AND refobjid = 'public'::regnamespace AND deptype = 'n')
    AND NOT EXISTS (SELECT FROM pg_extension WHERE extname <> 'plpgsql')
    AND NOT EXISTS (SELECT FROM pg_largeobject_metadata)
"""


def release_scope(config):
    require(config['releaseBucket'] == 'capsnote-169516793433-releases', 'Wrong release bucket')
    instance = config['sqlConnection']
    if config['databasePurpose'] == 'isolated-rehearsal':
        prefix = 'capsnote:asia-northeast3:'
        require(isinstance(instance, str) and re.fullmatch(prefix + r'capsnote-rehearsal-[a-z0-9-]{1,40}', instance),
                'Synthetic releases require a dedicated private rehearsal instance')
        return 'rehearsals/' + instance[len(prefix):] + '/'
    if config['databasePurpose'] == 'cold-start':
        require(instance == 'capsnote:asia-northeast3:capsnote-prod-pg16'
                and config.get('database') == COLD_DATABASE and config.get('databaseUser') == COLD_ROLE
                and config.get('restoreVerified') is False, 'Wrong cold-start target')
        require(type(config.get('jwtSecretVersion')) is int and config['jwtSecretVersion'] >= 2,
                'Cold start requires a separate GCP signing key')
        return COLD_SCOPE
    require(config['databasePurpose'] == 'restored-cutover' and config['restoreVerified'] is True
            and instance == 'capsnote:asia-northeast3:capsnote-prod-pg16', 'Verified production restore required')
    return ''


def local_state():
    return ROOT / release_scope(trusted(CONFIG / 'host.json'))


class Host:
    def __init__(self):
        require(os.geteuid() == 0, 'Root host required')
        self.config = trusted(CONFIG / 'host.json')
        self.scope = release_scope(self.config)
        self.cloud = Cloud(self.config['releaseBucket'], prefix=self.scope)
        self.opening = False
        self.ai_strict = True

    def hold_path(self):
        return ROOT / self.scope / 'maintenance-hold.json'

    def ai_control_dir(self):
        # Persistent (/var/lib), so an operator suspension survives reboots and redeploys.
        return ROOT / self.scope / 'ai-control'

    def held(self):
        # Only the operator's checked reopen may serve live while a hold exists.
        return not self.opening and self.hold_path().exists()

    def configuration(self, manifest):
        self.config = trusted(CONFIG / 'configurations' / (manifest['configRevision'] + '.json'))
        require(checksum(self.config) == manifest['configRevision'], 'Configuration hash mismatch')
        require(release_scope(self.config) == self.scope, 'Manifest changes host database scope')
        if self.scope == COLD_SCOPE:
            require(self.config['jwtSecretVersion'] == trusted(CONFIG / 'host.json')['jwtSecretVersion'],
                    'Manifest changes the host signing key version')
        require(re.fullmatch(r'capsnote:asia-northeast3:[a-z0-9-]+', self.config['sqlConnection']), 'Wrong SQL instance')
        if self.scope == COLD_SCOPE:
            expected = 'projects/capsnote/secrets/capsnote-prod-jwt-secret/versions/' + str(self.config['jwtSecretVersion'])
            require(manifest['secretVersions']['jwtSecret'] == expected, 'Wrong cold-start JWT version')
        else:
            require(self.config['database'] == 'capstone' and self.config['databaseUser'] == 'capstone_app', 'Wrong DB role')

    def check_initial_release(self, manifest, history):
        if self.scope == COLD_SCOPE:
            require(not history and manifest['flyway']['before'] == 'empty', 'First cold release requires a fresh database')

    def journal(self, operation):
        path = ROOT / self.scope / 'operations' / (operation + '.json')
        return trusted(path) if path.exists() else None

    def pending(self):
        return [trusted(p) for p in (ROOT / self.scope / 'operations').glob('*.json') if trusted(p)['phase'] not in ['done', 'rolled-back']]

    def save(self, value):
        atomic(ROOT / self.scope / 'operations' / (value['operationId'] + '.json'), canonical(value))

    def compose(self, manifest, *args):
        self.configuration(manifest)
        runtime = RUNTIME / manifest['releaseId']
        enabled = manifest['aiEnabled'] is True
        # AI-off mounts its own empty runtime directory as the control directory: no host prerequisite and
        # no suspension path is configured in be.properties.
        env = dict(os.environ, BE_IMAGE=manifest['be']['image'], WEB_IMAGE=manifest['web']['image'],
                   PROXY_IMAGE=manifest['proxy']['image'], SQL_CONNECTION=self.config['sqlConnection'],
                   RUNTIME_DIR=str(runtime), CAPSTONE_AI_ENABLED='true' if enabled else 'false',
                   AI_CONTROL_DIR=str(self.ai_control_dir() if enabled else runtime / 'ai'))
        return command(['docker', 'compose', '-p', 'capstone-gcp', '-f', str(TOOLS / 'compose.json'), *args], env=env)

    def prepare_database(self, manifest):
        self.compose(manifest, 'up', '-d', '--no-deps', 'proxy')

    def history(self, manifest):
        self.configuration(manifest)
        env = dict(os.environ, PGHOST='127.0.0.1', PGPORT='15432', PGDATABASE=self.config['database'],
                   PGUSER=self.config['databaseUser'], PGPASSWORD=self.cloud.secret(manifest['secretVersions']['dbPassword']),
                   PGCONNECT_TIMEOUT='10', PGSSLMODE='disable')
        # Local proxy transport; proxy itself uses TLS + private connector to SQL.
        for attempt in range(20):
            try:
                exists = command(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL"], env=env).strip()
                if exists == 'f':
                    if self.scope == COLD_SCOPE:
                        state, _ = self.cloud.read('state.json')
                        require(state is None and manifest['flyway']['before'] == 'empty',
                                'Cold-start schema or previous state already initialized')
                        empty = command(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', COLD_EMPTY_QUERY], env=env)
                        require(empty.strip() == 't', 'Cold-start database is not empty or role is unsafe')
                    else:
                        require(self.config['databasePurpose'] == 'isolated-rehearsal', 'Restored database has no Flyway history')
                    return []
                sql = "SELECT coalesce(json_agg(t ORDER BY installed_rank),'[]') FROM (SELECT installed_rank,version,checksum,success FROM public.flyway_schema_history) t"
                rows = json.loads(command(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql], env=env))
                require(self.scope != COLD_SCOPE or rows, 'Unexpected empty Flyway history table')
                return rows
            except ReleaseError:
                if attempt == 19:
                    raise
                time.sleep(1)

    def preflight(self, m, history, resume=False):
        validate(m)
        self.configuration(m)
        require(checksum(self.config) == m['configRevision'], 'Host configuration revision mismatch')
        require((CONFIG / 'bootstrap-revision').read_text().strip() == m['bootstrapRevision'], 'Bootstrap revision mismatch')
        # The certificate must validate for the real name before serving any writes.
        with socket.create_connection(('127.0.0.1', 443), timeout=5) as connection:
            with ssl.create_default_context().wrap_socket(connection, server_hostname='capsnote.art'):
                pass
        self.boundary_check()
        ai_properties = self.ai_preflight(m, resume)
        token = self.cloud.token()
        with tempfile.TemporaryDirectory(prefix='ar-auth-', dir=RUNTIME) as auth:
            env = dict(os.environ, DOCKER_CONFIG=auth)
            command(['docker', 'login', '-u', 'oauth2accesstoken', '--password-stdin', 'https://asia-northeast3-docker.pkg.dev'], env=env, data=token)
            for component in ['be', 'web', 'proxy']:
                command(['docker', 'pull', '--platform', 'linux/amd64', m[component]['image']], env=env)
                image = json.loads(command(['docker', 'image', 'inspect', m[component]['image']]))[0]
                require(image['Architecture'] == 'amd64' and image['Os'] == 'linux', 'Wrong image platform')
                if component != 'proxy':
                    require(image['Config']['Labels']['org.opencontainers.image.revision'] == m[component]['sourceSha'], 'Image/source mismatch')
                    require(image['Config']['User'].split(':')[0] not in ['', 'root', '0'], 'Nonroot app image required')
        self.check_image_migrations(m, history)
        if not resume and version(history) != 'empty' and version(history) != m['flyway']['after']:
            backup = trusted(CONFIG / 'backup-evidence.json')
            require(backup['reference'] == m['flyway']['backupReference'] and backup['status'] == 'SUCCESSFUL' and backup['history'] == history, 'Matching completed SQL backup required')
            require(0 <= time.time() - backup['confirmedAtEpoch'] < 3600, 'Backup evidence expired')
        properties = {'spring.datasource.url': f"jdbc:postgresql://proxy:5432/{self.config['database']}?logServerErrorDetail=false",
                      'spring.datasource.username': self.config['databaseUser'], 'capstone.ai.enabled': 'false',
                      'capstone.auth.dev-token.enabled': 'false', 'capstone.auth.google.enabled': 'true',
                      'capstone.auth.app-url': 'https://capsnote.art',
                      'capstone.auth.google.client-id': self.config['googleClientId'],
                      'capstone.auth.google.redirect-uri': 'https://capsnote.art/api/auth/oauth2/google/callback',
                      'spring.jpa.hibernate.ddl-auto': 'validate', 'spring.jpa.open-in-view': 'false',
                      'logging.level.org.hibernate.SQL': 'OFF', 'logging.level.org.hibernate.orm.jdbc.bind': 'OFF'}
        mapping = {'dbPassword': 'spring.datasource.password', 'jwtSecret': 'capstone.auth.jwt.secret',
                   'googleClientSecret': 'capstone.auth.google.client-secret', 'allowedEmails': 'capstone.auth.allowed-emails'}
        for key, prop in mapping.items():
            properties[prop] = self.cloud.secret(m['secretVersions'][key])
        properties.update(ai_properties)
        path = RUNTIME / m['releaseId'] / 'be.properties'
        atomic(path, render_properties(properties), 0o400)
        os.chown(path.parent, 10001, 10001)
        os.chown(path, 10001, 10001)
        self.compose(m, 'config', '--quiet')

    def ai_preflight(self, m, resume):
        """Before maintenance: AI-off gets an empty mount directory; AI-on is checked end to end here.

        Returns the AI be.properties entries (the service token value stays in memory and in the 0400 file)."""
        release = RUNTIME / m['releaseId']
        release.mkdir(mode=0o700, exist_ok=True)
        directory = release / 'ai'
        directory.mkdir(mode=0o700, exist_ok=True)
        require(directory.is_dir() and not directory.is_symlink(), 'Unexpected AI runtime path')
        self.ai_strict = not resume
        if m['aiEnabled'] is not True:
            require(not any(directory.iterdir()), 'Unexpected AI runtime files')
            os.chown(directory, APP_UID, APP_UID)
            os.chmod(directory, 0o500)
            return {}
        ai = m['ai']
        require(not ai_config_problems(self.config, m), 'AI host configuration mismatch')
        require(compose_ai_host() == (self.config['ai']['serverName'], self.config['ai']['privateIp']),
                'Compose AI host entry differs from host configuration')
        control = self.ai_control_dir()
        trusted_directory(control)
        if not resume:
            # An AI-on deploy must observe capabilities true; a suspended deploy would only roll back.
            require(not os.path.lexists(control / 'suspended'), 'AI suspended; turn it off before an AI-on deploy')
        contents = {}
        for name, key, suffix in AI_CONTENT:
            data = trusted_bytes(CONFIG / 'ai' / (ai[key] + suffix))
            require(hashlib.sha256(data).hexdigest() == ai[key], 'AI content hash mismatch')
            contents[name] = data
        try:
            policy = json.loads(contents['policy.json'].decode('utf-8'))
            attestation = json.loads(contents['attestation.json'].decode('utf-8'))
            ca = contents['ca.pem'].decode('ascii')
        except ValueError:
            raise ReleaseError('AI content files are not UTF-8 JSON / ASCII PEM') from None
        require(not policy_problems(policy), 'AI policy rejected')
        require(not attestation_problems(attestation, m), 'AI attestation rejected')
        require('-----BEGIN CERTIFICATE-----' in ca and 'PRIVATE KEY' not in ca, 'AI CA must be a public certificate')
        token = self.cloud.secret(ai['serviceToken'])
        require(token_ok(token), 'AI service token format rejected')
        if not resume:
            self.ai_ready(m, ca, token)
        for name, data in contents.items():
            atomic(directory / name, data, 0o400)
            os.chown(directory / name, APP_UID, APP_UID)
        os.chown(directory, APP_UID, APP_UID)
        os.chmod(directory, 0o500)
        properties = {'capstone.ai.enabled': 'true', 'capstone.ai.runtime.release-authorized': 'true',
                      'capstone.ai.runtime.suspension-file': AI_CONTROL_MOUNT + '/suspended',
                      'capstone.ai.pipeline.policy-file': AI_MOUNT + '/policy.json',
                      'capstone.ai.pipeline.attestation-file': AI_MOUNT + '/attestation.json',
                      'capstone.ai.pipeline.attestation-sha256': ai['attestationSha256'],
                      'capstone.ai.pipeline.synthetic-stub': 'false'}
        for operation in AI_OPERATIONS:
            if operation in ai['profiles']:
                properties['capstone.ai.pipeline.profiles.' + operation] = ai['profiles'][operation]
        properties.update({'capstone.ai.execution.budget-microunits': str(ai['budgetMicrounits']),
                           'capstone.ai.execution.currency': ai['currency'],
                           'capstone.ai.execution.poll-ms': str(ai['pollMs']),
                           'capstone.ai.client.base-url': self.config['ai']['baseUrl'],
                           'capstone.ai.client.service-token': token,
                           'capstone.ai.client.ca-certificate': AI_MOUNT + '/ca.pem'})
        return properties

    def ai_ready(self, m, ca, token):
        """GET /internal/v1/health/ready from the host with the BE's CA, SNI and token (never printed)."""
        endpoint = self.config['ai']
        wanted = set(ai_profiles(m).values())
        try:
            context = ssl.create_default_context(cadata=ca)
            with socket.create_connection((endpoint['privateIp'], AI_PORT), timeout=5) as raw:
                with context.wrap_socket(raw, server_hostname=endpoint['serverName']) as tls:
                    head = ['GET /internal/v1/health/ready HTTP/1.1', 'Host: ' + endpoint['serverName'],
                            'X-Request-Id: ' + str(uuid.uuid4()), 'Accept: application/json',
                            'Authorization: Bearer ' + token, 'Connection: close']
                    tls.sendall(('\r\n'.join(head) + '\r\n\r\n').encode())
                    response = http.client.HTTPResponse(tls, method='GET')
                    response.begin()
                    status, body = response.status, response.read(262144)
            value = json.loads(body)
            ready = status == 200 and value.get('status') == 'ready' and wanted <= set(value.get('profileVersions') or [])
        except Exception:
            raise ReleaseError('AI readiness check failed (details suppressed)') from None
        require(ready, 'AI not ready for the release profiles')

    def check_image_migrations(self, m, history):
        name = 'capstone-inventory-' + uuid.uuid4().hex
        command(['docker', 'create', '--label', 'art.capsnote.purpose=migration-inventory', '--name', name, m['be']['image']])
        try:
            with tempfile.TemporaryDirectory(prefix='migration-inventory-') as directory:
                jar = Path(directory) / 'app.jar'
                command(['docker', 'cp', name + ':/app/app.jar', str(jar)])
                with zipfile.ZipFile(jar) as archive:
                    actual = []
                    checksums = {}
                    for entry in archive.namelist():
                        found = re.fullmatch(r'BOOT-INF/classes/db/migration/V([0-9]+)__[^/]+\.sql', entry)
                        if found:
                            raw = archive.read(entry)
                            actual.append({'version': found[1], 'sha256': hashlib.sha256(raw).hexdigest()})
                            crc = 0
                            for line in raw.decode('utf-8-sig').splitlines():
                                crc = zlib.crc32(line.encode('utf-8'), crc)
                            checksums[found[1]] = crc if crc < 2**31 else crc - 2**32
                require(all(row['success'] is True and (row['version'] is None or checksums.get(str(row['version'])) == row['checksum']) for row in history), 'Applied Flyway checksum differs from image')
                require(sorted(actual, key=lambda x: int(x['version'])) == m['flyway']['migrations'], 'Image migration inventory mismatch')
        finally:
            command(['docker', 'rm', name])

    def boundary_check(self):
        command(['iptables', '-C', 'DOCKER-USER', '-s', '172.30.36.2/32', '-d', '169.254.169.254/32', '-j', 'ACCEPT'])
        command(['iptables', '-C', 'DOCKER-USER', '-s', '172.30.36.0/24', '-d', '169.254.169.254/32', '-j', 'REJECT'])

    def maintenance(self, enabled):
        enabled = enabled or self.held()
        source = TOOLS / ('nginx-maintenance.conf' if enabled else 'nginx-live.conf')
        atomic(NGINX, source.read_text(), 0o644)
        command(['nginx', '-t'])
        command(['systemctl', 'reload', 'nginx'])
        self.wait_tls_status(503 if enabled else 200)

    def activate(self, m):
        self.compose(m, 'stop', '-t', '30', 'be', 'web')
        self.compose(m, 'up', '-d', '--no-deps', 'proxy')
        self.compose(m, 'up', '-d', '--no-deps', '--force-recreate', 'be')
        self.wait_json('http://127.0.0.1:18080/actuator/health', {'status': 'UP'}, 180)
        self.compose(m, 'up', '-d', '--no-deps', '--force-recreate', 'web')

    @staticmethod
    def wait_json(url, expected, seconds=30):
        deadline = time.monotonic() + seconds
        while True:
            try:
                with urllib.request.urlopen(url, timeout=5) as response:
                    actual = json.load(response)
                    require(response.status == 200 and all(actual.get(k) == v for k, v in expected.items()), 'Health mismatch')
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise ReleaseError('Readiness failed') from None
                time.sleep(1)

    def smoke(self, m):
        self.wait_json('http://127.0.0.1:18080/actuator/health', {'status': 'UP'})
        if m['aiEnabled'] is True:
            # Deploy: all three true within 60 s (each call runs one AI readiness), else engine restores previous.
            # Reboot/resume/reopen: AI being down or suspended must not keep the notes service closed.
            self.wait_capabilities((True,) if getattr(self, 'ai_strict', True) else (True, False), 60)
        else:
            self.wait_json('http://127.0.0.1:18081/api/capabilities', {'analysisEnabled': False, 'graphEnabled': False, 'discoveriesEnabled': False})
        with urllib.request.urlopen('http://127.0.0.1:18081/', timeout=5) as response:
            require(b'<div id="root"></div>' in response.read(), 'Web readiness failed')

    @staticmethod
    def wait_capabilities(accepted, seconds):
        deadline = time.monotonic() + seconds
        while True:
            try:
                with urllib.request.urlopen('http://127.0.0.1:18081/api/capabilities', timeout=5) as response:
                    actual = json.load(response)
                    values = [actual.get(k) for k in CAPABILITIES]
                    require(response.status == 200 and all(type(v) is bool for v in values) and len(set(values)) == 1
                            and values[0] in accepted, 'Capabilities mismatch')
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise ReleaseError('Capabilities did not match the release') from None
                time.sleep(1)

    @staticmethod
    def wait_tls_status(expected, seconds=10):
        # Nginx signals its master asynchronously; old workers can still accept
        # a connection after systemctl reload returns. Check the real vhost.
        context = ssl.create_default_context()
        deadline = time.monotonic() + seconds
        while True:
            try:
                with socket.create_connection(('127.0.0.1', 443), timeout=2) as connection:
                    with context.wrap_socket(connection, server_hostname='capsnote.art') as tls:
                        tls.sendall(b'GET /api/v1/health HTTP/1.1\r\nHost: capsnote.art\r\nConnection: close\r\n\r\n')
                        with tls.makefile('rb') as response:
                            status = response.readline(1024).split()
                        if len(status) >= 2 and status[1] == str(expected).encode():
                            return
            except ssl.SSLCertVerificationError:
                raise
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise ReleaseError('Nginx did not serve the requested mode')
            time.sleep(0.1)

    def smoke_public(self, m):
        # Under an operator hold the public check is the held 503; `off` verifies 200.
        self.wait_tls_status(503 if self.held() else 200)


def restart_current(host, m):
    host.prepare_database(m)
    require(version(host.history(m)) == m['flyway']['after'], 'Current schema differs at boot; migration forbidden')
    host.preflight(m, host.history(m), resume=True)
    host.activate(m)
    require(version(host.history(m)) == m['flyway']['after'], 'Schema changed during boot')
    host.smoke(m)


def reopen(host):
    require(not host.pending(), 'Unresolved operation; recover before reopening')
    state, _ = host.cloud.read('state.json')
    require(state and state['current'], 'No current release to reopen')
    m = state['current']
    try:
        host.maintenance(True)
        restart_current(host, m)
        host.opening = True
        host.maintenance(False)
        host.smoke_public(m)
        host.hold_path().unlink(missing_ok=True)
    except Exception:
        host.maintenance(True)
        raise
    finally:
        host.opening = False


def maintenance_status(host):
    path = host.hold_path()
    hold = trusted(path) if path.exists() else None
    try:
        active = NGINX.read_text()
    except OSError:
        active = None
    modes = [('maintenance', 'nginx-maintenance.conf'), ('live', 'nginx-live.conf')]
    nginx = next((mode for mode, name in modes if active == (TOOLS / name).read_text()), 'unknown')
    return {'hold': hold is not None, 'holdSince': hold and hold['since'], 'holdBy': hold and hold['by'],
            'nginx': nginx, 'pendingOperations': len(host.pending())}


def operator_maintenance(host, action):
    user = os.environ.get('SUDO_USER')
    record = {'action': action, 'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'by': user if match(r'[A-Za-z0-9_.-]{1,64}', user) else None, 'result': 'failed'}
    (ROOT / host.scope).mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        if action == 'on':
            if not host.hold_path().exists():
                atomic(host.hold_path(), canonical({'since': record['at'], 'by': record['by']}))
            host.maintenance(True)
        else:
            reopen(host)
        record['result'] = 'ok'
    finally:
        fd = os.open(ROOT / host.scope / 'maintenance.log', os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, (canonical(record) + '\n').encode())
            os.fsync(fd)
        finally:
            os.close(fd)


def ai_suspend_status(host):
    control = host.ai_control_dir()
    path = control / 'suspended'
    try:
        trusted_directory(control)
        directory = 'ok'
    except ReleaseError:
        directory = 'untrusted' if os.path.lexists(control) else 'missing'
    marker = trusted(path) if directory == 'ok' and os.path.lexists(path) else None
    return {'aiSuspended': directory == 'ok' and os.path.lexists(path), 'since': marker and marker.get('since'),
            'by': marker and marker.get('by'), 'controlDirectory': directory}


def ai_suspend(host, action):
    """Create or remove ROOT/<scope>/ai-control/suspended. Read by the BE on every gate check (no restart).

    Emergency control: never waits for release.lock and does not touch Nginx, containers or journals."""
    user = os.environ.get('SUDO_USER')
    record = {'action': 'ai-suspend-' + action, 'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'by': user if match(r'[A-Za-z0-9_.-]{1,64}', user) else None, 'result': 'failed'}
    control = host.ai_control_dir()
    (ROOT / host.scope).mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        if not os.path.lexists(control):
            control.mkdir(mode=0o755)
            os.chmod(control, 0o755)
        trusted_directory(control)
        path = control / 'suspended'
        if action == 'on':
            if not os.path.lexists(path):
                atomic(path, canonical({'since': record['at'], 'by': record['by']}), 0o644)
        else:
            path.unlink(missing_ok=True)
        record['result'] = 'ok'
    finally:
        fd = os.open(ROOT / host.scope / 'ai-control.log', os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, (canonical(record) + '\n').encode())
            os.fsync(fd)
        finally:
            os.close(fd)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--maintenance', choices=['on', 'off', 'status'])
    parser.add_argument('--ai-suspend', choices=['on', 'off', 'status'])
    args = parser.parse_args()
    if args.maintenance and (args.operation is not None or args.resume):
        parser.error('--maintenance cannot be combined with other operations')
    if args.ai_suspend and (args.maintenance or args.operation is not None or args.resume):
        parser.error('--ai-suspend cannot be combined with other operations')
    if args.ai_suspend:
        host = Host()
        if args.ai_suspend != 'status':
            ai_suspend(host, args.ai_suspend)
        print(json.dumps(ai_suspend_status(host), sort_keys=True))
        return
    if args.maintenance == 'status':
        print(json.dumps(maintenance_status(Host()), sort_keys=True))
        return
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    RUNTIME.mkdir(mode=0o755, parents=True, exist_ok=True)
    with (ROOT / 'release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        host = Host()
        if args.maintenance:
            operator_maintenance(host, args.maintenance)
            print(json.dumps(maintenance_status(host), sort_keys=True))
            return
        if args.resume:
            pending = host.pending()
            require(len(pending) <= 1, 'Multiple unresolved operations')
            if pending:
                engine.recover(host, pending[0])
            else:
                state, _ = host.cloud.read('state.json')
                if state and state['current']:
                    m = state['current']
                    try:
                        host.maintenance(True)
                        restart_current(host, m)
                        host.maintenance(False)
                        host.smoke_public(m)
                    except Exception:
                        host.maintenance(True)
                        raise
        else:
            require(match(ID, args.operation), 'Invalid operation ID')
            request = trusted(ROOT / host.scope / 'requests' / (args.operation + '.json'))
            engine.run(host, request)
    print('Operation finished; details remain on the host.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Host operation failed; inspect durable state (details suppressed)', file=sys.stderr)
        sys.exit(1)
