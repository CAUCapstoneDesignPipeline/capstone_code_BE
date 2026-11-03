#!/usr/bin/env python3
"""PostgreSQL 16 migration guard. Credentials use a private pg_service file.

Never print database rows, SQL error text, credentials, or dump contents.
All external operations require a separately reviewed approval record.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time

class MigrationError(Exception):
    pass


def require(value, message):
    if not value:raise MigrationError(message)


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def private_write(path, value):
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as output:
        json.dump(value,output,sort_keys=True);output.write('\n');output.flush();os.fsync(output.fileno())


def approval(path, action, source, target):
    info=path.stat()
    require(stat.S_ISREG(info.st_mode) and not path.is_symlink() and not info.st_mode&0o077,'Approval record must be private')
    value=json.loads(path.read_text())
    require(value['action']==action and value['sourceService']==source and value['targetService']==target,'Approval scope mismatch')
    require(value['approvedBy'] and value['approvalReference'] and value['expiresAtEpoch']>time.time(),'Approval record missing or expired')
    require(value['dataClass'] in ['synthetic-isolated','approved-migration'],'Unreviewed data class')
    return value


class Client:
    def __init__(self, service_file, container=None):
        self.file=str(service_file);self.container=container
        if not container:
            info=Path(service_file).stat()
            require(not info.st_mode&0o077,'pg_service file permissions must be 0600')
    def call(self, program, args, data=None, output=None):
        cmd=[program,*args]
        env=dict(os.environ,PGSERVICEFILE=self.file,PGCONNECT_TIMEOUT='10')
        if self.container:
            require(re.fullmatch(r'[a-f0-9]{12,64}',self.container),'Invalid isolated client container')
            cmd=['docker','exec','-i','-e','PGSERVICEFILE='+self.file,'-e','PGCONNECT_TIMEOUT=10',self.container,*cmd]
        result=subprocess.run(cmd,input=data,stdout=output or subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=1800)
        require(result.returncode==0,'PostgreSQL command failed; output suppressed')
        return result.stdout if output is None else None
    def version(self):
        for program in ['pg_dump','pg_restore','psql']:
            require(re.search(rb'\b16\.',self.call(program,['--version'])),'PostgreSQL 16 clients required')
    def identity(self, service):
        return json.loads(self.sql(service, "SELECT json_build_object('database',current_database(),'user',current_user,'serverAddress',inet_server_addr()::text,'serverPort',inet_server_port());").strip())
    def sql(self,service,sql):
        require(re.fullmatch(r'[a-z][a-z0-9_-]{0,62}',service),'Service alias required')
        return self.call('psql',['-X','-qAt','-v','ON_ERROR_STOP=1','--dbname=service='+service],data=sql.encode()).decode()


SNAPSHOT_SQL=r"""
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='300000';
SELECT json_build_object('kind','database','version',current_setting('server_version_num')::int/10000,
 'encoding',pg_encoding_to_char(encoding),'collation',datcollate,'ctype',datctype,
 'timezone',current_setting('TimeZone')) FROM pg_database WHERE datname=current_database();
SELECT json_build_object('kind','extension','name',extname,'version',extversion) FROM pg_extension ORDER BY extname;
SELECT json_build_object('kind','constraint','table',conrelid::regclass::text,'name',conname,
 'definition',pg_get_constraintdef(oid),'validated',convalidated)
 FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY conrelid::regclass::text,conname;
SELECT json_build_object('kind','index','table',t.relname,'name',i.relname,
 'definition',pg_get_indexdef(i.oid),'valid',x.indisvalid,'ready',x.indisready)
 FROM pg_index x JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_class t ON t.oid=x.indrelid
 JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' ORDER BY t.relname,i.relname;
SELECT json_build_object('kind','column','table',table_name,'name',column_name,'position',ordinal_position,
 'type',data_type,'nullable',is_nullable,'default',column_default)
 FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position;
SELECT format($query$SELECT json_build_object('kind','table','name',%L,'rows',count(*),
 'sha256',CASE WHEN count(*) <= 1000000 THEN encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h),''),'UTF8')),'hex') ELSE NULL END)
 FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') h FROM %I.%I t) hashed$query$,
 tablename,schemaname,tablename) FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
SELECT format('SELECT json_build_object(''kind'',''sequence'',''name'',%L,''lastValue'',last_value,''isCalled'',is_called) FROM %I.%I',
 sequencename,schemaname,sequencename) FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename
\gexec
SELECT format($q$SELECT json_build_object('kind','foreignKey','name',%L,'orphans',count(*)) FROM %s s WHERE %s AND NOT EXISTS (SELECT 1 FROM %s r WHERE %s)$q$,
 c.conname,c.conrelid::regclass,
 (SELECT string_agg(format('s.%I IS NOT NULL',a.attname),' AND ' ORDER BY k.ord)
  FROM unnest(c.conkey) WITH ORDINALITY k(attnum,ord) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum),
 c.confrelid::regclass,
 (SELECT string_agg(format('s.%I = r.%I',a.attname,b.attname),' AND ' ORDER BY k.ord)
  FROM unnest(c.conkey,c.confkey) WITH ORDINALITY k(src,dst,ord)
  JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.src
  JOIN pg_attribute b ON b.attrelid=c.confrelid AND b.attnum=k.dst))
 FROM pg_constraint c WHERE c.contype='f' AND c.connamespace='public'::regnamespace ORDER BY c.conrelid::regclass::text,c.conname
\gexec
SELECT json_build_object('kind','flyway','history',coalesce(json_agg(t ORDER BY installed_rank),'[]'))
 FROM (SELECT installed_rank,version,type,script,checksum,success FROM public.flyway_schema_history) t;
COMMIT;
"""


def inventory(client,service):
    text=client.sql(service,SNAPSHOT_SQL).strip()
    values=[]
    decoder=json.JSONDecoder()
    while text:
        value,end=decoder.raw_decode(text)
        values.append(value)
        text=text[end:].lstrip()
    require(values and values[0]['kind']=='database' and values[0]['version']==16,'Expected PostgreSQL 16')
    for value in values:
        if value['kind']=='table':require(value['sha256'] is not None,'Table too large for reviewed digest budget')
        if value['kind']=='constraint':require(value['validated'],'Unvalidated constraint')
        if value['kind']=='index':require(value['valid'] is True and value['ready'] is True,'Invalid index')
        if value['kind']=='foreignKey':require(value['orphans']==0,'Orphan records detected')
        if value['kind']=='flyway':require(all(row['success'] for row in value['history']),'Failed source Flyway migration')
    require(any(v['kind']=='flyway' for v in values),'Flyway history required')
    return {'schemaVersion':1,'inventory':values}


def compare(source,target):
    require(source['schemaVersion']==target['schemaVersion']==1,'Wrong inventory format')
    require(source['inventory']==target['inventory'],'Restored data/schema/sequence/history differs')
    return {'status':'equal','tables':sum(v['kind']=='table' for v in source['inventory'])}


def writers_closed(record):
    required={'awsAutoDeploy','gcpAutoDeploy','be','oauthCallback','oauthRefresh','worker','operatorScripts','databaseSessions'}
    require(set(record)==required and all(v is True for v in record.values()),'All writers and both automatic deployments must be stopped')


def cutover_check(record):
    writers_closed(record['writersStopped'])
    required={'restoreCompared','flywayVerified','tlsVerified','oauthVerified','aiOffVerified','backupVerified','aiStandaloneAccepted','feBeDeployAccepted'}
    require(set(record['gates'])==required and all(v is True for v in record['gates'].values()),'Cutover acceptance missing')
    require(type(record['gcpWritesResumed']) is bool,'Write-resume state must be boolean')
    require(record['rollback'] in ['before-writes-source-preserved','gcp-forward-or-release-rollback'],'Explicit rollback strategy required')
    require(not record['gcpWritesResumed'] or record['rollback']=='gcp-forward-or-release-rollback','DNS-only rollback forbidden after GCP writes')
    return {'readyForApproval':True,'executed':False}


def dump_database(client,source,path,final=False,writers=None):
    if final:
        writers_closed(writers or {})
        sessions=int(client.sql(source,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend';").strip())
        require(sessions==0,'Other database sessions remain')
    require(not path.exists(),'Never overwrite a dump')
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    try:
        with os.fdopen(fd,'wb') as output:
            client.call('pg_dump',['--dbname=service='+source,'--format=custom','--no-owner','--no-acl'],output=output)
            output.flush();os.fsync(output.fileno())
    except Exception:
        # Preserve partial dump for explicit operator inspection/removal, never use it as successful evidence.
        raise
    return {'sha256':digest(path),'bytes':path.stat().st_size,'complete':True,'sourceIdentity':client.identity(source),'createdAtEpoch':time.time(),'format':'postgresql16-custom'}


def restore_database(client,target,path,evidence,expected_source,expected_digest):
    require(evidence['sourceIdentity']==expected_source and evidence['sha256']==expected_digest,'Dump provenance differs from approved source/digest')
    require(evidence['format']=='postgresql16-custom','Unexpected dump format')
    require(path.is_file() and not path.is_symlink() and not path.stat().st_mode&0o077,'Dump must be private regular file')
    require(evidence['complete'] is True and digest(path)==evidence['sha256'] and path.stat().st_size==evidence['bytes'],'Dump evidence mismatch')
    identity=client.identity(target)
    require(identity['user']=='capstone_app','Restore must run as the application schema owner')
    role=client.sql(target, "SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication) FROM pg_roles WHERE rolname=current_user;").strip()
    require(role=='t','Restore role must not be privileged')
    count=int(client.sql(target,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','S','v','m','f');").strip())
    require(count==0,'Restore target must be empty, including Flyway history')
    # pg_restore reads stdin; no local/container dump path confusion or shell redirection.
    with path.open('rb') as stream:
        cmd=['pg_restore','--dbname=service='+target,'--no-owner','--no-acl','--exit-on-error','--single-transaction']
        if client.container:cmd=['docker','exec','-i','-e','PGSERVICEFILE='+client.file,client.container,*cmd]
        result=subprocess.run(cmd,stdin=stream,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                              env=dict(os.environ,PGSERVICEFILE=client.file,PGCONNECT_TIMEOUT='10'),timeout=1800)
        require(result.returncode==0,'Restore failed atomically; output suppressed')
    owned=client.sql(target, "SELECT coalesce(bool_and(pg_get_userbyid(c.relowner)=current_user),false) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','S');").strip()
    require(owned=='t','Restored tables/sequences must be owned by runtime role')
    return {'restored':True,'sha256':evidence['sha256']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['inventory','dump','restore','compare','cutover-check'])
    parser.add_argument('--service-file');parser.add_argument('--client-container')
    parser.add_argument('--source',default='source');parser.add_argument('--target',default='target')
    parser.add_argument('--approval',type=Path);parser.add_argument('--input',type=Path)
    parser.add_argument('--evidence',type=Path);parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--final',action='store_true');parser.add_argument('--writers',type=Path)
    args=parser.parse_args()
    if args.action=='compare':
        result=compare(json.loads(args.input.read_text()),json.loads(args.evidence.read_text()))
    elif args.action=='cutover-check':result=cutover_check(json.loads(args.input.read_text()))
    else:
        require(args.approval is not None,'Explicit scoped approval record required')
        scope=approval(args.approval,args.action,args.source,args.target)
        require(args.source!=args.target,'Source and target service aliases must differ')
        client=Client(args.service_file,args.client_container);client.version()
        service=args.target if args.action=='restore' else args.source
        identity_key='targetIdentity' if args.action=='restore' else 'sourceIdentity'
        require(client.identity(service)==scope[identity_key], 'Connected database identity differs from approved target')
        if args.action=='inventory':result=inventory(client,args.source)
        elif args.action=='dump':
            result=dump_database(client,args.source,args.input,args.final,json.loads(args.writers.read_text()) if args.writers else None)
        else:result=restore_database(client,args.target,args.input,json.loads(args.evidence.read_text()),scope['sourceIdentity'],scope['dumpSha256'])
    private_write(args.output,result)
    print('Migration step completed; private evidence saved; no data emitted')

if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Migration step rejected or failed; evidence incomplete; values suppressed') from None
