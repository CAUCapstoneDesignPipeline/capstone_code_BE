#!/usr/bin/env python3
"""Read an existing approved pre-migration snapshot; never create/delete a DB or snapshot."""
import argparse,json,subprocess,re
from datetime import datetime,timezone
p=argparse.ArgumentParser();p.add_argument('--snapshot',required=True);p.add_argument('--flyway-before',required=True)
a=p.parse_args();assert re.fullmatch(r'[a-z][a-z0-9-]{0,254}',a.snapshot) and re.fullmatch(r'[0-9]+',a.flyway_before)
r=subprocess.run(['aws','rds','describe-db-snapshots','--db-snapshot-identifier',a.snapshot,'--profile','capstone-deploy','--region','ap-northeast-2','--output','json'],capture_output=True,text=True,check=True)
snapshot=json.loads(r.stdout)['DBSnapshots'][0]
assert snapshot['DBInstanceIdentifier']=='capstone-prod' and snapshot['Status']=='available' and snapshot['Encrypted'] and snapshot['SnapshotType']=='manual'
created=datetime.fromisoformat(snapshot['SnapshotCreateTime'].replace('Z','+00:00'))
assert 0 <= (datetime.now(timezone.utc)-created).total_seconds() <= 7200
print(json.dumps({'backup':{'snapshotId':a.snapshot,'confirmedAt':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),'dbIdentifier':'capstone-prod'},'status':'available','flywayBefore':a.flyway_before},sort_keys=True))
