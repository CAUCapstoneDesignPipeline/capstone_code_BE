#!/usr/bin/env python3
"""Deploy a CI-published main image while preserving the other service and live config."""
import argparse
import copy
import importlib.util
import json
import os
import re
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

spec=importlib.util.spec_from_file_location('release_runner',Path(__file__).with_name('release-command.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
from manifest import canonical,checksum,require,validate,ReleaseError

OWNER='CAUCapstoneDesignPipeline'
REPOS={'be':'capstone_code_BE','web':'capstone_code_FE'}
CURRENT='/capstone/prod/deploy/current'

def github(repo,path):
    headers={'Accept':'application/vnd.github+json','User-Agent':'capstone-auto-release','X-GitHub-Api-Version':'2022-11-28'}
    token=os.environ.get('GH_TOKEN')
    if token and os.environ.get('GITHUB_REPOSITORY')==OWNER+'/'+repo:
        headers['Authorization']='Bearer '+token
    request=urllib.request.Request('https://api.github.com/repos/'+OWNER+'/'+repo+'/'+path,
        headers=headers)
    # Use the caller's short-lived token only for its own repository.
    with urllib.request.urlopen(request,timeout=20) as response:return json.load(response)

def migrations(source):
    tree=github(REPOS['be'],'commits/'+source)['commit']['tree']['sha']
    result=github(REPOS['be'],'git/trees/'+tree+'?recursive=1')
    require(result.get('truncated') is False,'Migration inventory is incomplete')
    prefix='src/main/resources/db/migration/'
    files={v['path']:v['sha'] for v in result['tree'] if v['type']=='blob' and v['path'].startswith(prefix)}
    require(bool(files),'Migration inventory is missing')
    return files

def current():
    parameter=runner.aws('ssm','get-parameter','--name',CURRENT,'--no-with-decryption')['Parameter']
    require(parameter['Type']=='String','Current metadata must not contain secrets')
    return validate(json.loads(parameter['Value']))

def idle():
    # GitHub concurrency groups are per repository. The shared host lock is authoritative.
    # Wait for existing fixed deployment commands; never cancel another release.
    cutoff=(datetime.now(timezone.utc)-timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ')
    filters=json.dumps([{'key':'DocumentName','value':'capstone-deploy'},{'key':'InvokedAfter','value':cutoff}])
    deadline=time.monotonic()+600
    while True:
        commands=runner.aws('ssm','list-commands','--filters',filters)['Commands']
        active=[c for c in commands if runner.HOST in c.get('InstanceIds',[]) and c['Status'] in ['Pending','InProgress','Delayed','Cancelling']]
        if not active:return
        require(time.monotonic()<deadline,'Another deployment is still running; do not resend')
        time.sleep(10)

def target(before,service,source,digest,attempt):
    if service=='be' and before['be']['sourceSha']!=source:
        require(migrations(before['be']['sourceSha'])==migrations(source),
            'DB migrations changed; use a reviewed manual release with backup evidence')
    after=copy.deepcopy(before)
    run=os.environ.get('GITHUB_RUN_ID','');run_attempt=os.environ.get('GITHUB_RUN_ATTEMPT','')
    require(re.fullmatch(r'[0-9]+',run) and re.fullmatch(r'[0-9]+',run_attempt),'GitHub run identity required')
    after['releaseId']='auto-'+service+'-'+run+'-'+run_attempt+'-'+str(attempt)
    after[service]={'sourceSha':source,'digest':digest}
    schema=before['flyway']['after']
    after['flyway']={'before':schema,'after':schema,'rollbackCompatibleWith':[schema],'backup':None}
    return validate(after)

def send(after,before):
    values={'ReleaseId':after['releaseId'],'BeDigest':after['be']['digest'],'WebDigest':after['web']['digest'],
        'ManifestSha':checksum(after),'ExpectedRelease':before['releaseId'],'ManifestJson':canonical(after),'Operation':'deploy'}
    response=runner.aws('ssm','send-command','--document-name','capstone-deploy','--document-version','4',
        '--instance-ids',runner.HOST,'--parameters',json.dumps({k:[v] for k,v in values.items()}),
        '--timeout-seconds','900','--cloud-watch-output-config','CloudWatchOutputEnabled=false',
        '--comment','Automatic main deploy '+after['releaseId'])
    return response['Command']['CommandId']

def deploy(service,source,digest,folder):
    require(service in REPOS,'Unknown service')
    require(os.environ.get('GITHUB_REPOSITORY')==OWNER+'/'+REPOS[service],'Service/caller mismatch')
    require(os.environ.get('GITHUB_EVENT_NAME') in ['push','workflow_dispatch'],'Only main publication may deploy')
    require(os.environ.get('GITHUB_SHA')==source and re.fullmatch(r'[a-f0-9]{40}',source),'Published source differs from caller')
    require(re.fullmatch(r'sha256:[a-f0-9]{64}',digest),'Invalid published digest')
    require(os.environ.get('SSM_DOCUMENT_VERSION')=='4','Fixed deploy document v4 required')
    record={'status':'preparing','service':service,'sourceSha':source,'digest':digest,'commands':[]}
    def save():
        folder.mkdir(parents=True,exist_ok=True)
        (folder/'result.json').write_text(json.dumps(record,indent=2)+'\n')
    save()
    for attempt in range(1,4):
        idle()
        latest=github(REPOS[service],'git/ref/heads/main')['object']['sha']
        if latest!=source:
            record.update(status='superseded',latestMain=latest);save();return record
        before=current();runner.check(before)
        if before[service]=={'sourceSha':source,'digest':digest}:
            record.update(status='already-current',observedCurrent=before['releaseId']);save();return record
        after=target(before,service,source,digest,attempt);runner.check(after,remote=True)
        save();(folder/'manifest.json').write_text(json.dumps(after,indent=2)+'\n')
        (folder/'previous-manifest.json').write_text(json.dumps(before,indent=2)+'\n')
        command=send(after,before);record['commands'].append({'commandId':command,'releaseId':after['releaseId'],'expectedRelease':before['releaseId']});save()
        try:
            runner.wait(command)
        except runner.CommandFailed as error:
            record.update(status='failed');save()
            # Only retry a terminal Failed command when a different release has committed.
            # Timeouts/cancellations/ordinary application failures are never blindly retried.
            if error.status!='Failed':raise
            idle();observed=current()
            require(checksum(observed)!=checksum(before) and observed['releaseId']!=after['releaseId'],
                'Deployment failed without a proven competing commit; inspect host before retry')
            continue
        observed=current()
        record.update(status='success',releaseId=after['releaseId'],observedCurrent=observed['releaseId'],
            currentAdvanced=checksum(observed)!=checksum(after));save();return record
    raise ReleaseError('Concurrent releases changed repeatedly; inspect before retry')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service',choices=list(REPOS),required=True)
    parser.add_argument('--source',required=True);parser.add_argument('--digest',required=True)
    args=parser.parse_args()
    result=deploy(args.service,args.source,args.digest,Path('auto-release'))
    print(json.dumps(result,sort_keys=True))

if __name__=='__main__':
    try:main()
    except Exception as error:
        # SDK, SQL and command output may carry credentials. Do not print raw errors.
        reason=str(error) if isinstance(error,ReleaseError) else 'details suppressed'
        print('Automatic release stopped: '+type(error).__name__+' ('+reason+'). Inspect the artifact and SSM command; DB changes require the manual release path.',file=sys.stderr)
        sys.exit(1)
