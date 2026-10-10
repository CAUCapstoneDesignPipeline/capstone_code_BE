#!/usr/bin/env python3
"""Validate reviewed main manifest, resolve exact ECR images and wait for SSM terminal state."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'host'))
from manifest import canonical, checksum, require, validate, ReleaseError

REGION='ap-northeast-2'
ACCOUNT='378040395204'
HOST='i-066c6e88c43ff81a5'

def aws(*args):
    r=subprocess.run(['aws',*args,'--region',REGION,'--output','json'],capture_output=True,text=True)
    require(r.returncode==0, 'AWS operation failed (details suppressed)')
    return json.loads(r.stdout)

def load(path):
    require(re.fullmatch(r'deploy/releases/[a-z0-9][a-z0-9-]{0,63}\.json',path), 'Manifest must be a reviewed release file')
    require(not Path(path).is_symlink(), 'Manifest symlink rejected')
    return validate(json.loads(Path(path).read_text()))

def check(m, remote=False):
    require(os.environ.get('GITHUB_REF')=='refs/heads/main', 'Production requires main')
    require(os.environ.get('PRODUCTION_DEPLOY_ENABLED')=='true', 'Production execution is disabled')
    require(re.fullmatch(r'[1-9][0-9]*',os.environ.get('SSM_DOCUMENT_VERSION','')) and int(os.environ['SSM_DOCUMENT_VERSION'])>=4, 'Reviewed protocol 4 document version required')
    if not remote: return
    identity=aws('sts','get-caller-identity')
    require(identity['Account']==ACCOUNT and ':assumed-role/capstone-prod-deploy/' in identity['Arn'], 'Wrong deploy identity')
    for name in ['be','web']:
        images=aws('ecr','describe-images','--repository-name','capstone/'+name,'--image-ids','imageDigest='+m[name]['digest'])['imageDetails']
        require(len(images)==1 and 'sha-'+m[name]['sourceSha'] in images[0].get('imageTags',[]), 'Digest/source tag mismatch')

def wait(command_id, deadline_seconds=930):
    deadline=time.monotonic()+deadline_seconds
    while time.monotonic()<deadline:
        # Eventual consistency is expected immediately after SendCommand.
        r=subprocess.run(['aws','ssm','get-command-invocation','--command-id',command_id,'--instance-id',HOST,'--region',REGION,'--output','json'],capture_output=True,text=True)
        if r.returncode:
            require('InvocationDoesNotExist' in r.stderr, 'SSM invocation query failed')
            time.sleep(5);continue
        invocation=json.loads(r.stdout);status=invocation['Status']
        if status in ['Pending','InProgress','Delayed']: time.sleep(5);continue
        # Do not print command stdout/stderr: future scripts may contain sensitive error text.
        print(json.dumps({'commandId':command_id,'status':status,'responseCode':invocation['ResponseCode']}))
        require(status=='Success' and invocation['ResponseCode']==0, 'SSM command failed; inspect maintenance before retry')
        return
    raise ReleaseError('SSM wait deadline exceeded; command may still be running. Inspect it before retrying')

def main():
    p=argparse.ArgumentParser()
    p.add_argument('operation',choices=['validate','deploy','rollback','wait'])
    p.add_argument('--manifest');p.add_argument('--expected-release');p.add_argument('--command-id')
    a=p.parse_args()
    if a.operation=='wait':
        require(bool(a.command_id and re.fullmatch(r'[a-f0-9-]{36}',a.command_id)), 'Command ID required')
        wait(a.command_id);return
    m=load(a.manifest);check(m,remote=a.operation!='validate')
    if a.operation=='validate': print(json.dumps({'releaseId':m['releaseId'],'manifestSha':checksum(m),'mode':m['mode']}));return
    require(isinstance(a.expected_release,str) and re.fullmatch(r'none|[a-z0-9][a-z0-9-]{0,63}',a.expected_release), 'Explicit expected release required')
    parameters={k:[v] for k,v in {'ReleaseId':m['releaseId'],'BeDigest':m['be']['digest'],'WebDigest':m['web']['digest'],
                'ManifestSha':checksum(m),'ExpectedRelease':a.expected_release,'ManifestJson':canonical(m),'Operation':a.operation}.items()}
    response=aws('ssm','send-command','--document-name','capstone-deploy','--document-version',os.environ['SSM_DOCUMENT_VERSION'],
        '--instance-ids',HOST,'--parameters',json.dumps(parameters),'--timeout-seconds','900',
        '--cloud-watch-output-config','CloudWatchOutputEnabled=false','--comment','Reviewed '+a.operation+' '+m['releaseId'])
    command=response['Command']['CommandId'];print(json.dumps({'commandId':command,'releaseId':m['releaseId']}),flush=True)
    wait(command)

if __name__=='__main__':
    try: main()
    except Exception as e:
        print('Release runner failed: '+type(e).__name__+' (details suppressed)',file=sys.stderr)
        sys.exit(1)
