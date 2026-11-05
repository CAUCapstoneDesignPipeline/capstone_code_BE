#!/usr/bin/env python3
"""Ubuntu host entry point; no AWS SDK, shell-evaluated input or credential output."""
import argparse
import fcntl
import hashlib
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
from contract import ReleaseError, canonical, checksum, match, require, validate, validate_request, version, ID
from storage import Cloud
import engine

ROOT = Path('/var/lib/capstone-gcp')
CONFIG = Path('/etc/capstone-gcp')
TOOLS = Path('/opt/capstone-gcp')
RUNTIME = Path('/run/capstone-gcp')


def command(args, *, env=None, data=None):
    result = subprocess.run(args, env=env, input=data, text=True, capture_output=True, timeout=600)
    require(result.returncode == 0, 'Host command failed (output suppressed)')
    return result.stdout


def atomic(path, value, mode=0o600):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.write-')
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w') as handle:
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
        env = dict(os.environ, BE_IMAGE=manifest['be']['image'], WEB_IMAGE=manifest['web']['image'],
                   PROXY_IMAGE=manifest['proxy']['image'], SQL_CONNECTION=self.config['sqlConnection'],
                   RUNTIME_DIR=str(RUNTIME / manifest['releaseId']))
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
        def escape(value):
            return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t').replace(' ', '\\ ')
        path = RUNTIME / m['releaseId'] / 'be.properties'
        atomic(path, ''.join(k + '=' + escape(v) + '\n' for k, v in properties.items()), 0o400)
        os.chown(path.parent, 10001, 10001)
        os.chown(path, 10001, 10001)
        self.compose(m, 'config', '--quiet')

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
        source = TOOLS / ('nginx-maintenance.conf' if enabled else 'nginx-live.conf')
        atomic(Path('/etc/nginx/conf.d/capstone-gcp.conf'), source.read_text(), 0o644)
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
        self.wait_json('http://127.0.0.1:18081/api/capabilities', {'analysisEnabled': False, 'graphEnabled': False, 'discoveriesEnabled': False})
        with urllib.request.urlopen('http://127.0.0.1:18081/', timeout=5) as response:
            require(b'<div id="root"></div>' in response.read(), 'Web readiness failed')

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
        self.wait_tls_status(200)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    RUNTIME.mkdir(mode=0o755, parents=True, exist_ok=True)
    with (ROOT / 'release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        host = Host()
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
                        host.prepare_database(m)
                        require(version(host.history(m)) == m['flyway']['after'], 'Current schema differs at boot; migration forbidden')
                        host.preflight(m, host.history(m), resume=True)
                        host.activate(m)
                        require(version(host.history(m)) == m['flyway']['after'], 'Schema changed during boot')
                        host.smoke(m)
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
