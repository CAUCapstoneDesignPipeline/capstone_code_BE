#!/usr/bin/env python3
"""Return operation phase only, never manifest, secrets or service output."""
import json
import subprocess
import sys
from contract import ID, match, require
from host import ROOT, trusted

try:
    require(len(sys.argv)==2 and match(ID,sys.argv[1]),'Invalid operation')
    operation=sys.argv[1]
    path=ROOT/'operations'/(operation+'.json')
    status=trusted(path)['phase'] if path.exists() else 'queued'
    unit=subprocess.run(['systemctl','show','--property=ActiveState','--value','capstone-gcp-operation@'+operation+'.service'],capture_output=True,text=True,timeout=10)
    if unit.stdout.strip()=='failed' and status not in ['done','rolled-back']:
        status='failed'
    print(json.dumps({'operationId':operation,'status':status}))
except Exception:
    raise SystemExit('Status unavailable') from None
