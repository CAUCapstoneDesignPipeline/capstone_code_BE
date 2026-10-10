#!/usr/bin/env python3
"""Create a reviewable manifest from actual publish workflow metadata, never sample digests."""
import argparse
import json
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'host'))
from manifest import require, validate, canonical, ReleaseError

def metadata(path,be=False):
    value=json.loads(Path(path).read_text())
    expected={'sourceSha','digest','platform','releaseProtocol','runtimeVerified'}|({'aiOffVerified'} if be else set())
    require(isinstance(value,dict) and set(value)==expected, 'Use actual image-metadata.json from publish workflow')
    require(value['platform']=='linux/amd64' and type(value['releaseProtocol']) is int and value['releaseProtocol']==4 and value['runtimeVerified'] is True, 'Image runtime verification required')
    if be:require(value['aiOffVerified'] is True, 'AI-off image verification required')
    return {k:value[k] for k in ['sourceSha','digest']}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--be-metadata',required=True);p.add_argument('--web-metadata',required=True)
    p.add_argument('--release-id',required=True);p.add_argument('--config-revision',required=True)
    p.add_argument('--flyway-before',required=True);p.add_argument('--flyway-after',required=True)
    p.add_argument('--compatible-with',action='append',default=[])
    p.add_argument('--db-password-version',type=int,required=True);p.add_argument('--jwt-secret-version',type=int,required=True)
    p.add_argument('--backup-evidence',help='Actual available snapshot evidence; required for an existing DB schema change')
    a=p.parse_args();backup=None
    if a.backup_evidence:
        evidence=json.loads(Path(a.backup_evidence).read_text())
        require(evidence['status']=='available' and evidence['flywayBefore']==a.flyway_before, 'Snapshot/schema evidence mismatch')
        backup=evidence['backup']
    require(a.flyway_before=='empty' or a.flyway_before==a.flyway_after or backup is not None, 'Existing DB migration needs actual backup evidence')
    m=validate({'schemaVersion':2,'releaseId':a.release_id,'mode':'api-only',
        'be':metadata(a.be_metadata,True),'web':metadata(a.web_metadata),
        'configRevision':a.config_revision,'secretVersions':{'dbPassword':a.db_password_version,'jwtSecret':a.jwt_secret_version,'googleClientSecret':0,'allowedEmails':0},
        'flyway':{'before':a.flyway_before,'after':a.flyway_after,'rollbackCompatibleWith':a.compatible_with,'backup':backup},
        'gates':{'aiOffVerified':True},'aiEnabled':False,'aiImage':None})
    root=Path(__file__).resolve().parents[2];target=root/'deploy/releases'/(a.release_id+'.json')
    require(not target.exists(),'Existing release manifest must not be overwritten')
    with target.open('x') as f:f.write(json.dumps(m,indent=2)+'\n')
    print('Prepared review file: '+str(target.relative_to(root))+'. Commit and merge before production execution.')

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Manifest preparation failed: '+type(error).__name__+' (details suppressed)',file=sys.stderr);sys.exit(1)
