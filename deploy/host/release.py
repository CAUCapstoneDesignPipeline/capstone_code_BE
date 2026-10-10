#!/opt/capstone/tools/bin/python
"""Fixed digest deployment. No CLI credentials, arbitrary commands or secret output."""
import base64
import fcntl
import json
import os
import shutil
import stat
import socket
import ssl
import subprocess
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path
import boto3
from manifest import REGISTRY, ReleaseError, canonical, checksum, image, require, transition, validate, PIPELINE_PROTOCOL, AI_OFF_SMOKE

BASE=Path('/opt/capstone/releases')
RUNTIME=Path('/run/capstone/releases')
STATE=Path('/etc/capstone')
HOST=Path('/opt/capstone/host')


def run(args, env=None, input=None):
    r=subprocess.run(args, env=env, input=input, capture_output=True, text=True, timeout=600)
    require(r.returncode == 0, 'Fixed host command failed (output suppressed)')
    return r.stdout


def atomic(path, value, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp=path.with_suffix(path.suffix+'.tmp')
    fd=os.open(tmp, os.O_CREAT|os.O_TRUNC|os.O_WRONLY, mode)
    with os.fdopen(fd, 'w') as f:
        f.write(value); f.flush(); os.fsync(f.fileno())
    os.chmod(tmp, mode); os.replace(tmp, path)
    fd=os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def private_json(path):
    info=path.stat()
    require(info.st_uid == 0 and not stat.S_IMODE(info.st_mode) & 0o022 and not path.is_symlink(), 'Untrusted host configuration')
    return json.loads(path.read_text())


class Host:
    def __init__(self):
        require(os.geteuid() == 0, 'Root host entry point required')
        require(not (STATE/'release-pending.json').exists(), 'Unresolved release requires investigation')
        require((STATE/'pipeline-version').read_text().strip()==PIPELINE_PROTOCOL, 'Reviewed pipeline installation required')
        self.config=private_json(STATE/'production.json')
        self.infrastructure=private_json(STATE/'infrastructure.json')
        self.ssm=boto3.client('ssm',region_name='ap-northeast-2')
        self.db_env=None

    def current(self):
        return private_json(STATE/'current.json') if (STATE/'current.json').exists() else None

    def previous(self):
        return private_json(STATE/'previous.json') if (STATE/'previous.json').exists() else None

    def unused_release(self, m, action):
        p=BASE/m['releaseId']/'manifest.json'
        if not p.exists(): return action == 'deploy'
        return action == 'rollback' and checksum(private_json(p)) == checksum(m)

    def secret(self, suffix, expected=None):
        p=self.ssm.get_parameter(Name='/capstone/prod/be/'+suffix,WithDecryption=True)['Parameter']
        if expected is not None: require(p['Version']==expected, 'Secret revision changed; do not restore old credentials')
        return p['Value']

    def history(self):
        if self.db_env is None:
            self.db_env=dict(os.environ, PGHOST=self.infrastructure['dbEndpoint'], PGPORT='5432',PGDATABASE='capstone',
                PGUSER='capstone_app', PGPASSWORD=self.secret('db-password'), PGSSLMODE='verify-full',
                PGSSLROOTCERT='/etc/capstone/rds-ca.pem', PGCONNECT_TIMEOUT='10')
        exists=run(['psql','-X','-qAt','-c',"SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;"],env=self.db_env).strip()
        if exists=='f': return []
        rows=run(['psql','-X','-qAt','-c',"SELECT COALESCE(json_agg(x ORDER BY installed_rank),'[]') FROM (SELECT installed_rank,version,checksum,success FROM public.flyway_schema_history) x;"],env=self.db_env)
        return json.loads(rows)

    def backup_ready(self, m):
        evidence=private_json(STATE/'backup-evidence.json')
        require(m['flyway']['backup'] is not None and evidence['backup']==m['flyway']['backup'], 'Reviewed backup evidence required')
        require(evidence['status']=='available' and evidence['flywayBefore']==m['flyway']['before'], 'Snapshot/schema evidence mismatch')
        when=datetime.strptime(evidence['backup']['confirmedAt'],'%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
        require(0 <= (datetime.now(timezone.utc)-when).total_seconds() <= 3600, 'Backup evidence expired')
        # Evidence is staged by an operator with RDS read access. Deploy role/host have no RDS admin access.

    def compose(self, m, *args):
        env=dict(os.environ,BE_IMAGE=image(m,'be'),WEB_IMAGE=image(m,'web'),WEB_MODE=m['mode'],RUNTIME_DIR=str(RUNTIME/m['releaseId']))
        return run(['docker','compose','-f',str(HOST/'production-compose.json'),*args],env=env)

    def preflight(self, m, is_resume=False):
        require(self.config['configRevision']==m['configRevision'], 'Host configuration revision differs')
        require(self.config['allowedMode']==m['mode'], 'Host mode differs')
        require(shutil.disk_usage('/').free >= 3*1024**3, 'Insufficient free disk')
        require((STATE/'db-bootstrap-complete').exists(), 'DB account bootstrap not complete')
        require(socket.gethostbyname('capsnote.art')=='15.165.127.143', 'DNS does not point to this release host')
        with socket.create_connection(('capsnote.art',443),timeout=5) as connection:
            with ssl.create_default_context().wrap_socket(connection,server_hostname='capsnote.art'):
                pass
        cert=Path('/etc/letsencrypt/live/capsnote.art/fullchain.pem')
        run(['openssl','x509','-checkend','0' if is_resume else '1209600','-checkhost','capsnote.art','-noout','-in',str(cert)])
        auth=boto3.client('ecr',region_name='ap-northeast-2').get_authorization_token()['authorizationData'][0]
        user, password=base64.b64decode(auth['authorizationToken']).decode().split(':',1)
        docker_config=RUNTIME/'docker-auth';docker_config.mkdir(parents=True,exist_ok=True,mode=0o700)
        docker_env=dict(os.environ,DOCKER_CONFIG=str(docker_config))
        try:
            run(['docker','login','--username',user,'--password-stdin',REGISTRY],env=docker_env,input=password)
            for service in ['be','web']:
                run(['docker','pull',image(m,service)],env=docker_env)
                metadata=json.loads(run(['docker','image','inspect',image(m,service)]))[0]
                labels=metadata['Config'].get('Labels',{})
                repo='capstone_code_BE' if service=='be' else 'capstone_code_FE'
                require(metadata['Architecture']=='amd64' and metadata['Os']=='linux', 'Wrong image platform')
                require(labels.get('org.opencontainers.image.revision')==m[service]['sourceSha'], 'Image source label mismatch')
                require(labels.get('org.opencontainers.image.source')=='https://github.com/CAUCapstoneDesignPipeline/'+repo, 'Wrong image repository')
                require(labels.get('art.capsnote.release-protocol')==PIPELINE_PROTOCOL, 'Image release protocol mismatch')
        finally:
            shutil.rmtree(docker_config,ignore_errors=True)
        versions=m['secretVersions']
        db=self.secret('db-password',versions['dbPassword']);jwt=self.secret('jwt-secret',versions['jwtSecret'])
        props={'spring.datasource.url':'jdbc:postgresql://'+self.infrastructure['dbEndpoint']+':5432/capstone?sslmode=verify-full&sslrootcert=/run/secrets/rds-ca.pem&logServerErrorDetail=false',
               'spring.datasource.username':'capstone_app','spring.datasource.password':db,
               'capstone.auth.jwt.secret':jwt,'capstone.auth.app-url':'https://capsnote.art',
               'capstone.auth.dev-token.enabled':'false','capstone.auth.google.enabled':str(self.config['googleEnabled']).lower(),
               'capstone.auth.google.redirect-uri':'https://capsnote.art/api/auth/oauth2/google/callback',
               'spring.jpa.hibernate.ddl-auto':'validate','spring.jpa.open-in-view':'false',
               'logging.file.name':'/var/log/capstone/be.log','logging.level.org.flywaydb':'WARN',
               'logging.level.org.hibernate.SQL':'OFF','logging.level.org.hibernate.orm.jdbc.bind':'OFF',
               'logging.logback.rollingpolicy.max-file-size':'10MB','logging.logback.rollingpolicy.max-history':'7',
               'logging.logback.rollingpolicy.total-size-cap':'70MB'}
        if self.config['googleEnabled']:
            require(bool(self.config['googleClientId']), 'Google client ID missing')
            require(versions['googleClientSecret']>0 and versions['allowedEmails']>0, 'Real OAuth values required')
            props.update({'capstone.auth.google.client-id':self.config['googleClientId'],
                          'capstone.auth.google.client-secret':self.secret('google-client-secret',versions['googleClientSecret']),
                          'capstone.auth.allowed-emails':self.secret('allowed-emails',versions['allowedEmails'])})
        else:
            require(versions['googleClientSecret']==0 and versions['allowedEmails']==0, 'Disabled OAuth must not inject invented credentials')
        def escape(v):
            return str(v).replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r').replace('\t','\\t').replace(' ','\\ ')
        path=RUNTIME/m['releaseId']/'be.properties'
        atomic(path,''.join(k+'='+escape(v)+'\n' for k,v in props.items()),0o400)
        os.chown(path.parent,10001,10001);os.chmod(path.parent,0o700);os.chown(path,10001,10001)
        log=Path('/var/log/capstone/be');log.mkdir(exist_ok=True,mode=0o750);os.chown(log,10001,10001)
        self.compose(m,'config','--quiet')
        atomic(BASE/m['releaseId']/'manifest.json',canonical(m)+'\n')

    def begin(self, m, previous):
        atomic(STATE/'release-pending.json',canonical({'target':m['releaseId'],'previous':previous['releaseId'] if previous else 'none','phase':'switch'}))

    def finish(self):
        (STATE/'release-pending.json').unlink()

    def maintenance(self, enabled):
        config='server {\n listen 80 default_server;\n server_name capsnote.art;\n access_log off;\n error_log /var/log/capstone/nginx-error.log crit;\n location ^~ /.well-known/acme-challenge/ { root /var/www/capstone; }\n location / { return 301 https://capsnote.art$request_uri; }\n}\nserver {\n listen 443 ssl;\n server_name capsnote.art;\n ssl_certificate /etc/letsencrypt/live/capsnote.art/fullchain.pem;\n ssl_certificate_key /etc/letsencrypt/live/capsnote.art/privkey.pem;\n ssl_protocols TLSv1.2 TLSv1.3;\n access_log off;\n error_log /var/log/capstone/nginx-error.log crit;\n client_max_body_size 16m;\n location / {\n CAPSTONE_ROUTE\n }\n}\n'
        route='add_header Retry-After 30 always; return 503;' if enabled else 'proxy_pass http://127.0.0.1:18081; proxy_set_header Host capsnote.art; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-For $remote_addr;'
        atomic(Path('/etc/nginx/conf.d/capstone.conf'),config.replace('CAPSTONE_ROUTE',route),0o644)
        run(['nginx','-t']);run(['systemctl','reload','nginx'])

    def stop_be(self):
        current=self.current()
        if current: self.compose(current,'stop','-t','30','be')

    def start_be(self, m):
        self.active=m;self.compose(m,'up','-d','--no-deps','be')

    def wait_be(self):
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:
                with urllib.request.urlopen('http://127.0.0.1:18080/actuator/health',timeout=3) as r:
                    if r.status==200 and json.load(r).get('status')=='UP': return
            except Exception: pass
            time.sleep(3)
        raise ReleaseError('DB health timeout')

    def start_web(self, m):
        self.compose(m,'up','-d','--no-deps','--force-recreate','web')
        self.compose(m,'exec','-T','web','nginx','-t','-c','/tmp/nginx.conf')

    def smoke(self, m):
        def get(path):
            try:
                req=urllib.request.Request('http://127.0.0.1:18081'+path,headers={'Host':'capsnote.art','X-Forwarded-Proto':'https'})
                with urllib.request.urlopen(req,timeout=5) as r: return r.status,r.read()
            except urllib.error.HTTPError as e: return e.code,e.read()
        require(get('/api/v1/health')[0]==200, 'API health smoke failed')
        require(get('/api/auth/dev/token')[0]==404 and get('/actuator/health')[0]==404, 'Public management boundary failed')
        smoke=AI_OFF_SMOKE
        status, body=get(smoke['path'])
        require(status==200 and all(json.loads(body).get(k) is v for k,v in smoke['expected'].items()), 'Required AI-off public response is not implemented')
        status,body=get('/')
        require(status==200 and b'api-only' in body, 'Preparation page mode mismatch')

    def commit(self, m, previous):
        atomic(STATE/'release-pending.json',canonical({'target':m['releaseId'],'phase':'commit'}))
        for name,value in [('previous',previous),('current',m)]:
            self.ssm.put_parameter(Name='/capstone/prod/deploy/'+name,Value=canonical(value),Type='String',Overwrite=True)
        if previous: atomic(STATE/'previous.json',canonical(previous)+'\n')
        atomic(STATE/'current.json',canonical(m)+'\n')

    def smoke_public(self, m):
        with urllib.request.urlopen('https://capsnote.art/api/v1/health',timeout=5) as r:
            require(r.status==200 and json.load(r).get('status')=='UP', 'External HTTPS API smoke failed')
        with urllib.request.urlopen('https://capsnote.art/',timeout=5) as r:
            require(r.status==200 and b'api-only' in r.read(), 'External HTTPS web smoke failed')

    def restore(self, m):
        # Existing successful runtime config is retained in tmpfs; never fetch old secret versions.
        self.start_be(m);self.start_web(m)


def main():
    if sys.argv[1:]==['--resume']:
        with open('/run/capstone-release.lock','a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            host=Host(); current=host.current()
            if current is None: return
            validate(current)
            host.maintenance(True)
            from manifest import version
            require(version(host.history())==current['flyway']['after'], 'Unexpected schema during boot')
            host.preflight(current,is_resume=True);host.start_be(current);host.wait_be();host.start_web(current);host.smoke(current)
            host.maintenance(False);host.smoke_public(current)
        print('Current release resumed after boot.')
        return
    require(not sys.argv[1:], 'Unsupported host arguments')
    manifest=json.loads(os.environ['SSM_ManifestJson'])
    validate(manifest)
    require(checksum(manifest)==os.environ['SSM_ManifestSha'], 'Manifest hash mismatch')
    require(manifest['releaseId']==os.environ['SSM_ReleaseId'] and manifest['be']['digest']==os.environ['SSM_BeDigest'] and manifest['web']['digest']==os.environ['SSM_WebDigest'], 'Document parameters differ from manifest')
    STATE.mkdir(exist_ok=True,mode=0o700)
    with open('/run/capstone-release.lock','a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise ReleaseError('Another release is running') from None
        transition(Host(),manifest,os.environ['SSM_ExpectedRelease'],os.environ['SSM_Operation'])
    print('Release completed successfully; credentials and command output suppressed.')

if __name__=='__main__':
    try: main()
    except Exception as error:
        # Arbitrary SDK/SQL/container messages may contain credentials. Never print them.
        print('Release failed: '+type(error).__name__+' (details suppressed)',file=sys.stderr)
        sys.exit(1)
