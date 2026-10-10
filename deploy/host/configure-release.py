#!/opt/capstone/tools/bin/python
"""Operator-only, fixed non-secret configuration/evidence staging. Deploy role cannot invoke it."""
import json
import os
import re
import sys
from datetime import datetime,timezone
from manifest import keys,require,RELEASE,SHA,version
from release import Host,STATE,atomic

try:
    require(os.geteuid()==0, 'Root required')
    operation=os.environ['SSM_ConfigurationOperation']
    value=json.loads(os.environ['SSM_ConfigurationJson'])
    if operation=='initial-config':
        require(not (STATE/'current.json').exists() and not (STATE/'release-pending.json').exists(), 'Existing release configuration cannot be overwritten by initial setup')
        keys(value,['configRevision','approvedContractSha','googleEnabled','googleClientId','allowedMode','aiOffSmoke'])
        require(isinstance(value['configRevision'],str) and re.fullmatch(RELEASE,value['configRevision']), 'Invalid revision')
        require(isinstance(value['approvedContractSha'],str) and re.fullmatch(SHA,value['approvedContractSha']), 'Approved contract pin required')
        require(value['allowedMode']=='api-only' and type(value['googleEnabled']) is bool, 'Unsupported mode')
        require(isinstance(value['googleClientId'],str) and (re.fullmatch(r'[A-Za-z0-9.-]+\.apps\.googleusercontent\.com',value['googleClientId']) if value['googleEnabled'] else value['googleClientId']==''), 'Real Google client ID required only when enabled')
        keys(value['aiOffSmoke'],['path','expected'])
        require(isinstance(value['aiOffSmoke']['path'],str) and re.fullmatch(r'/api/[a-z][a-z0-9/-]{0,100}',value['aiOffSmoke']['path']), 'Approved public smoke path required')
        expected=value['aiOffSmoke']['expected']
        require(isinstance(expected,dict) and 1 <= len(expected) <= 8 and all(re.fullmatch(r'[A-Za-z][A-Za-z0-9]{0,63}',k) and v is False for k,v in expected.items()), 'Approved boolean AI-off smoke required')
        atomic(STATE/'production.json',json.dumps(value,sort_keys=True)+'\n')
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
