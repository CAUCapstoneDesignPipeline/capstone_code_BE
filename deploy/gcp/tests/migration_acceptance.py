#!/usr/bin/env python3
"""Synthetic data only; invoked by the PostgreSQL Testcontainers Java harness."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import json
import subprocess
import time
spec=importlib.util.spec_from_file_location('migration',Path(__file__).parents[1]/'migration/migrate.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
client=m.Client('/tmp/migration-services.conf',sys.argv[1])
client.version()
with tempfile.TemporaryDirectory(prefix='capstone-migration-test-') as directory:
    dump=Path(directory)/'synthetic.dump'
    scope={'action':'inventory','sourceService':'source','targetService':'target',
           'approvedBy':'synthetic-local-test','approvalReference':'user-authorized-isolated-test',
           'expiresAtEpoch':time.time()+60,'dataClass':'synthetic-isolated','sourceIdentity':client.identity('source')}
    approval=Path(directory)/'approval.json';m.private_write(approval,scope)
    args=[sys.executable,str(Path(__file__).parents[1]/'migration/migrate.py'),'inventory','--client-container',sys.argv[1],
          '--service-file','/tmp/migration-services.conf','--approval',str(approval),'--output',str(Path(directory)/'inventory.json')]
    subprocess.run(args,check=True,capture_output=True)
    scope['sourceIdentity']['database']='wrong-target'
    approval.write_text(json.dumps(scope))
    assert subprocess.run(args,capture_output=True).returncode != 0
    before=m.inventory(client,'source')
    before_identity=client.identity('source')
    evidence=m.dump_database(client,'source',dump)
    m.restore_database(client,'target',dump,evidence,before_identity,evidence['sha256'])
    after=m.inventory(client,'target')
    m.compare(before,after)
    try:m.restore_database(client,'target',dump,evidence,before_identity,evidence['sha256'])
    except m.MigrationError:pass
    else:raise AssertionError('Nonempty target accepted')
    wrong=dict(evidence,sha256='0'*64)
    try:m.restore_database(client,'target',dump,wrong,before_identity,evidence['sha256'])
    except m.MigrationError:pass
    else:raise AssertionError('Wrong checksum accepted')
    client.sql('target','DROP INDEX analysis_job_one_active;')
    try:m.compare(before,m.inventory(client,'target'))
    except m.MigrationError:pass
    else:raise AssertionError('Lost partial unique index accepted')
    index=next(v['definition'] for v in before['inventory'] if v['kind']=='index' and v['name']=='analysis_job_one_active')
    client.sql('target',index+';')
    m.compare(before,m.inventory(client,'target'))
    # Data change, including another user's note, must change comparison evidence.
    client.sql('target',"UPDATE note SET version=version+1 WHERE user_id='00000000-0000-0000-0000-000000000002';")
    try:m.compare(before,m.inventory(client,'target'))
    except m.MigrationError:pass
    else:raise AssertionError('Changed user data accepted')
    client.sql('target',"UPDATE note SET version=version-1 WHERE user_id='00000000-0000-0000-0000-000000000002'; UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='2';")
    try:m.compare(before,m.inventory(client,'target'))
    except m.MigrationError:pass
    else:raise AssertionError('Changed Flyway checksum accepted')
    client.sql('target',"UPDATE flyway_schema_history SET checksum=checksum-1 WHERE version='2';")
    client.sql('admin_target',"SET session_replication_role=replica; INSERT INTO note(user_id,title,body) VALUES ('00000000-0000-0000-0000-000000009999','orphan fixture','synthetic'); SET session_replication_role=origin;")
    try:m.inventory(client,'target')
    except m.MigrationError:pass
    else:raise AssertionError('Orphan data accepted')
    print('PostgreSQL16 Testcontainers: dump/empty restore, full table and sequence hashes, two users, OAuth identities, refresh hashes, Flyway history, nonempty target and corruption rejection PASS')
