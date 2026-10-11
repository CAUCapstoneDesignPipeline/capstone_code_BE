"""Fixed status/finish operation for a committed release; never redeploy or migrate."""
import base64
import fcntl
import gzip
import hashlib
import json
import os
import re
import sys
import urllib.request
import urllib.error
from pathlib import Path
import boto3
sys.path.insert(0,'/opt/capstone/host')
from release import Host, STATE, HOST, private_json, atomic
from manifest import checksum, image, require, validate, version

def main():
    require(os.geteuid()==0,'Fixed root operation required')
    operation=os.environ['SSM_RecoveryOperation']
    expected=os.environ['SSM_ReleaseId'];expected_sha=os.environ['SSM_ManifestSha']
    with open('/run/capstone-release.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        host=object.__new__(Host)
        host.config=private_json(STATE/'production.json')
        host.infrastructure=private_json(STATE/'infrastructure.json')
        host.ssm=boto3.client('ssm',region_name='ap-northeast-2');host.db_env=None
        current=host.current();pending=private_json(STATE/'release-pending.json') if (STATE/'release-pending.json').exists() else None
        require(current is not None,'Committed current release required')
        validate(current)
        require(current['releaseId']==expected and checksum(current)==expected_sha,'Unexpected committed release')
        history=host.history()
        summary={'releaseId':expected,'manifestSha':checksum(current),'schemaVersion':version(history),
                 'pendingPhase':pending.get('phase') if pending else None,'pendingMatches':bool(pending and pending.get('target')==expected)}
        require(summary['pendingPhase'] in [None,'switch','commit'],'Unexpected journal phase')
        def get(url):
            try:
                with urllib.request.urlopen(url,timeout=5) as response:return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,b''
            except urllib.error.URLError:return 0,b''
        for name,url in [('beHealth','http://127.0.0.1:18080/actuator/health'),('webHealth','http://127.0.0.1:18081/api/v1/health'),('publicHealth','https://capsnote.art/api/v1/health')]:
            summary[name]=get(url)[0]
        # Inspect only image references and status; never expose container config/environment.
        import subprocess
        for service in ['be','web']:
            ids=subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project=capstone-production','--filter','label=com.docker.compose.service='+service],text=True).split()
            if len(ids)!=1:
                summary[service+'DigestMatches']=False
                continue
            output=subprocess.check_output(['docker','inspect','--format','{{json .Config.Image}}',ids[0]],text=True)
            summary[service+'DigestMatches']=json.loads(output)==image(current,service)
        if operation=='status':
            print(json.dumps(summary,sort_keys=True));return
        require(operation=='finish-commit','Unsupported fixed operation')
        require(summary['pendingPhase']=='commit' and summary['pendingMatches'],'Only committed journal may be completed')
        require(summary['schemaVersion']==current['flyway']['after'],'Schema must already match; migrations are forbidden')
        require(all(summary[k] is True for k in ['beDigestMatches','webDigestMatches']),'Running digest mismatch')
        require((STATE/'pipeline-version').read_text().strip()=='4','Expected protocol 4')
        host.wait_be();host.smoke(current)
        # Only the reviewed public-readiness fix may replace this one root-owned file.
        payload=REVIEWED_RELEASE_BYTES
        original_sha='__ORIGINAL_RELEASE_SHA__'
        hashes=private_json(HOST/'pipeline-files.json')
        actual_sha=hashlib.sha256((HOST/'release.py').read_bytes()).hexdigest()
        patched_sha=hashlib.sha256(payload).hexdigest()
        require(actual_sha in [original_sha,patched_sha] and hashes['release.py']==actual_sha,'Unknown host source')
        atomic(HOST/'release.py',payload.decode(),0o700)
        hashes['release.py']=patched_sha;atomic(HOST/'pipeline-files.json',json.dumps(hashes,sort_keys=True))
        # Load the reviewed method after replacing the exact expected source.
        import importlib,release
        importlib.reload(release)
        try:
            host.maintenance(False)
            release.Host.smoke_public(host,current)
            host.finish()
        except Exception:
            host.maintenance(True)
            raise
        summary.update({'pendingPhase':None,'publicHealth':200,'completed':True,'migrationsExecuted':False,'containersRecreated':False,'publicReadinessFixInstalled':True})
        print(json.dumps(summary,sort_keys=True))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Fixed release recovery failed: '+type(error).__name__+' (details suppressed)',file=sys.stderr);sys.exit(1)
