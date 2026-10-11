#!/usr/bin/env python3
"""Isolated disposable image checks; never use AWS, real OAuth, or existing databases."""
import json
import os
import secrets
import subprocess
import time
import urllib.request
import urllib.error
import uuid
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'host'))
from manifest import AI_OFF_SMOKE

IMAGE=os.environ.get('TEST_IMAGE','capstone-ci:test')
PREFIX='capstone-image-test-'+uuid.uuid4().hex[:12]
created=[]
network=False

def docker(*args,env=None,input=None):
    result=subprocess.run(['docker',*args],capture_output=True,text=True,env=env,input=input,timeout=180)
    if result.returncode: raise RuntimeError('Image test Docker operation failed (output suppressed)')
    return result.stdout.strip()

def request(port,path,method='GET'):
    try:
        r=urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}'+path,method=method),timeout=3)
        with r: return r.status,r.read(),dict(r.headers)
    except urllib.error.HTTPError as e: return e.code,e.read(),dict(e.headers)

def mapped(name,port):
    return int(docker('port',name,str(port)+'/tcp').split(':')[-1])

try:
    metadata=json.loads(docker('image','inspect',IMAGE))[0]
    assert metadata['Architecture']=='amd64' and metadata['Os']=='linux'
    assert metadata['Config']['User'] not in ['', 'root','0','0:0']
    docker('network','create',PREFIX);network=True
    if True:
        db=PREFIX+'-db';created.append(db)
        password=secrets.token_urlsafe(36)
        env=dict(os.environ,POSTGRES_PASSWORD=password)
        docker('run','-d','--name',db,'--network',PREFIX,'--network-alias','db','-e','POSTGRES_PASSWORD','-e','POSTGRES_DB=capstone','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','postgres:16.15@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d',env=env)
        for _ in range(60):
            p=subprocess.run(['docker','exec',db,'pg_isready','-U','postgres'],capture_output=True)
            if p.returncode==0: break
            time.sleep(1)
        else: raise RuntimeError('Disposable DB startup timeout')
        app_password=secrets.token_urlsafe(36)
        docker('exec','-i',db,'psql','-X','-q','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1',input="CREATE ROLE capstone_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"+app_password+"'; ALTER DATABASE capstone OWNER TO capstone_app;")
        name=PREFIX+'-be';created.append(name)
        # Generated test secrets go through stdin to container tmpfs, never a host artifact or argument.
        props='spring.datasource.url=jdbc:postgresql://db:5432/capstone\nspring.datasource.username=capstone_app\nspring.datasource.password='+app_password+'\ncapstone.auth.jwt.secret='+secrets.token_urlsafe(48)+'\ncapstone.auth.app-url=https://capsnote.art\ncapstone.auth.google.enabled=false\ncapstone.auth.dev-token.enabled=false\n'
        docker('run','-d','--name',name,'--network',PREFIX,'--network-alias','be','--platform','linux/amd64','--read-only','--memory','1100m','--cpus','1.25','--pids-limit','256','--tmpfs','/tmp:rw,nosuid,size=128m','--cap-drop','ALL','--security-opt','no-new-privileges:true','-e','SPRING_PROFILES_ACTIVE=prod','-e','SPRING_CONFIG_ADDITIONAL_LOCATION=file:/tmp/be.properties','-p','127.0.0.1::8080','--entrypoint','/bin/sh',IMAGE,'-c','while test ! -f /tmp/start.marker; do sleep 1; done; exec java -jar /app/app.jar')
        docker('exec','-i',name,'/bin/sh','-c','umask 077; cat > /tmp/be.properties; touch /tmp/start.marker',input=props)
        port=mapped(name,8080)
        for _ in range(90):
            try:
                status,body,_=request(port,'/actuator/health')
                if status==200 and json.loads(body)['status']=='UP':break
            except Exception:pass
            time.sleep(2)
        else:raise RuntimeError('BE database health timeout; application output suppressed')
        assert request(port,'/api/v1/health')[0]==200
        assert request(port,'/api/notes')[0]==401
        assert request(port,'/api/auth/dev/token','POST')[0] in [404,403]
        smoke=AI_OFF_SMOKE
        status,body,_=request(port,smoke['path'])
        assert status==200 and json.loads(body)==smoke['expected']
        docker('exec',name,'java','-cp','/app/health','Healthcheck')
        print(json.dumps({'amd64':True,'nonRoot':True,'flywayJpaDbHealth':True,'appRoleNonSuperuser':True,'memoryLimit1100MiB':True,'unauthenticatedNotes401':True,'productionDevTokenDisabled':True,'imageHealthcheck':True,'aiOffVerified':True}))
    else:
        name=PREFIX+'-web';created.append(name)
        docker('run','-d','--name',name,'--network',PREFIX,'--platform','linux/amd64','--read-only','--tmpfs','/tmp:rw,nosuid,size=32m','--cap-drop','ALL','--security-opt','no-new-privileges:true','-p','127.0.0.1::8080',IMAGE)
        port=mapped(name,8080)
        for _ in range(30):
            try:
                if request(port,'/healthz')[0]==200:break
            except Exception:pass
            time.sleep(1)
        else:raise RuntimeError('FE startup timeout')
        status,body,headers=request(port,'/auth/callback')
        assert status==200 and b'api-only' in body and headers.get('Cache-Control')=='no-store'
        for path in ['/actuator/health','/swagger-ui.html','/v3/api-docs','/internal','/ai','/api/auth/dev/token']:
            assert request(port,path)[0]==404,path
        status,body,_=request(port,'/api/notes')
        assert status==502 and b'api-only' not in body
        print(json.dumps({'amd64':True,'nonRoot':True,'apiOnlyDefault':True,'spaRouting':True,'adminPathsBlocked':True,'apiDoesNotFallbackToHtml200':True}))
finally:
    for name in reversed(created):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
    if network:subprocess.run(['docker','network','rm',PREFIX],capture_output=True)
