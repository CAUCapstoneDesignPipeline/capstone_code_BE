#!/opt/capstone/tools/bin/python
"""Operator-only, fixed non-secret configuration/evidence staging. Deploy role cannot invoke it."""
import json
import fcntl
import os
import re
import sys
from datetime import datetime,timezone
from manifest import keys,require,RELEASE,version
from release import Host,STATE,atomic,private_json

def configuration(value):
    keys(value,['configRevision','googleEnabled','googleClientId','allowedMode'])
    require(isinstance(value['configRevision'],str) and re.fullmatch(RELEASE,value['configRevision']), 'Invalid revision')
    require(value['allowedMode'] in ['api-only','notes-web'] and type(value['googleEnabled']) is bool, 'Unsupported mode')
    require(value['allowedMode']!='notes-web' or value['googleEnabled'], 'Google login required')
    require(isinstance(value['googleClientId'],str) and (re.fullmatch(r'[A-Za-z0-9.-]+\.apps\.googleusercontent\.com',value['googleClientId']) if value['googleEnabled'] else value['googleClientId']==''), 'Real Google client ID required only when enabled')

try:
    require(os.geteuid()==0, 'Root required')
    lock=open('/run/capstone-release.lock','a')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    operation=os.environ['SSM_ConfigurationOperation']
    value=json.loads(os.environ['SSM_ConfigurationJson'])
    if operation=='initial-config':
        require(not (STATE/'current.json').exists() and not (STATE/'release-pending.json').exists(), 'Existing release configuration cannot be overwritten by initial setup')
        configuration(value)
        atomic(STATE/'production.json',json.dumps(value,sort_keys=True)+'\n')
    elif operation=='stage-config':
        require(not (STATE/'release-pending.json').exists(), 'Unresolved release requires investigation')
        configuration(value)
        current=private_json(STATE/'current.json')
        require(current['releaseId']==os.environ['SSM_ExpectedRelease'], 'Stale expected release')
        require(value['configRevision']!=current['configRevision'], 'Active configuration cannot be overwritten')
        path=STATE/'configurations'/(value['configRevision']+'.json')
        if path.exists(): require(private_json(path)==value, 'Configuration revision already used')
        else: atomic(path,json.dumps(value,sort_keys=True)+'\n')
    elif operation=='backup-evidence':
        keys(value,['backup','status','flywayBefore'])
        keys(value['backup'],['snapshotId','confirmedAt','dbIdentifier'])
        require(value['status']=='available' and value['backup']['dbIdentifier']=='capstone-prod', 'Wrong snapshot evidence')
        require(re.fullmatch(r'[a-z][a-z0-9-]{0,254}',value['backup']['snapshotId']), 'Invalid snapshot ID')
        when=datetime.strptime(value['backup']['confirmedAt'],'%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
        require(0 <= (datetime.now(timezone.utc)-when).total_seconds() <= 3600, 'Evidence expired')
        require(version(Host().history())==value['flywayBefore'], 'Host schema and evidence differ')
        atomic(STATE/'backup-evidence.json',json.dumps(value,sort_keys=True)+'\n')
    else: raise ValueError()
    print('Non-secret reviewed release configuration staged.')
except Exception as error:
    print('Configuration failed: '+type(error).__name__+' (details suppressed)',file=sys.stderr)
    sys.exit(1)
