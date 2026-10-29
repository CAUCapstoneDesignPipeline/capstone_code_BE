import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parents[1]))
from contract import ReleaseError, checksum, validate
from engine import run, recover


def manifest(name='next'):
    return {'schemaVersion': 3, 'releaseId': name,
            'be': {'sourceSha': 'a'*40, 'image': 'asia-northeast3-docker.pkg.dev/capsnote/capstone-be/be@sha256:'+'b'*64},
            'web': {'sourceSha': 'c'*40, 'image': 'asia-northeast3-docker.pkg.dev/capsnote/capstone-web/web@sha256:'+'d'*64},
            'proxy': {'image': 'gcr.io/cloud-sql-connectors/cloud-sql-proxy@sha256:'+'e'*64},
            'configRevision': 'f'*64, 'bootstrapRevision': 'a'*40,
            'secretVersions': {k: 'projects/capsnote/secrets/capsnote-prod-'+v+'/versions/1' for k,v in
                               {'dbPassword':'db-password','jwtSecret':'jwt-secret','googleClientSecret':'google-client-secret','allowedEmails':'allowed-emails'}.items()},
            'flyway': {'before':'2','after':'2','rollbackCompatibleWith':['2'],
                       'migrations':[{'version':'1','sha256':'1'*64},{'version':'2','sha256':'2'*64}], 'backupReference':None},
            'aiEnabled':False,'aiImage':None}


def request(m=None, operation='deploy', op='op1', expected='old'):
    m=m or manifest()
    return {'operationId':op,'operation':operation,'expectedRelease':expected,'manifest':m,'manifestHash':checksum(m)}


class FakeCloud:
    def __init__(self):
        self.state={'current':manifest('old'),'previous':manifest('older')}
        self.generation=1
        self.manifests={}
        self.fail=None
    def read(self,name):
        return copy.deepcopy(self.state),self.generation
    def create_manifest(self,m):
        if m['releaseId'] in self.manifests and self.manifests[m['releaseId']] != m:
            raise ReleaseError('collision')
        self.manifests[m['releaseId']]=copy.deepcopy(m)
    def put(self,name,state,generation):
        if self.fail=='before' or generation!=self.generation:
            raise ReleaseError('cas')
        self.state=copy.deepcopy(state)
        self.generation+=1
        if self.fail=='after':
            raise ReleaseError('response lost')


class Fake:
    def __init__(self):
        self.cloud=FakeCloud();self.journals={};self.closed=False;self.events=[];self.fail=None
        self.rows=[{'installed_rank':1,'version':'1','checksum':10,'success':True},{'installed_rank':2,'version':'2','checksum':20,'success':True}]
    def journal(self,op):return copy.deepcopy(self.journals.get(op))
    def pending(self):return [j for j in self.journals.values() if j['phase'] not in ['done','rolled-back']]
    def save(self,j):self.journals[j['operationId']]=copy.deepcopy(j);self.events.append(j['phase'])
    def prepare_database(self,m):pass
    def history(self,m):return copy.deepcopy(self.rows)
    def preflight(self,m,history,resume=False):
        if self.fail=='preflight':raise ReleaseError('bad secret/config/digest')
    def maintenance(self,closed):self.closed=closed;self.events.append(('closed',closed))
    def activate(self,m):
        self.events.append(('activate',m['releaseId']))
        if self.fail=='schema':self.rows[-1]['checksum']=99;raise ReleaseError('migration changed')
        if self.fail=='health':raise ReleaseError('DB unavailable')
    def smoke(self,m):pass
    def smoke_public(self,m):
        if self.fail=='public':raise ReleaseError('public health')


class EngineTest(unittest.TestCase):
    def test_success_idempotent_and_commit_before_ingress(self):
        f=Fake();r=request();self.assertEqual(run(f,r),'done')
        count=len(f.events);self.assertEqual(run(f,r),'done');self.assertEqual(count,len(f.events))
        self.assertLess(f.events.index('committed'),f.events.index(('closed',False)))
        self.assertEqual(f.cloud.state['previous']['releaseId'],'old')
    def test_stale_and_payload_reuse_rejected(self):
        f=Fake()
        with self.assertRaises(ReleaseError):run(f,request(expected='stale'))
        self.assertEqual(f.events,[])
        run(f,request())
        with self.assertRaises(ReleaseError):run(f,request(manifest('changed')))
    def test_preflight_failure_preserves_release(self):
        f=Fake();f.fail='preflight'
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertFalse(f.closed);self.assertFalse(f.journals)
    def test_db_failure_recovers_unchanged_schema(self):
        f=Fake();f.fail='health'
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertTrue(f.closed);f.fail=None
        self.assertEqual(run(f,request()),'rolled-back')
        self.assertFalse(f.closed);self.assertEqual(f.journals['op1']['phase'],'rolled-back')
        with self.assertRaises(ReleaseError):run(f,request())
    def test_changed_history_cannot_rollback(self):
        f=Fake();f.fail='schema'
        with self.assertRaises(ReleaseError):run(f,request())
        f.fail=None
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertTrue(f.closed)
    def test_lost_cas_response_reconciles_target(self):
        f=Fake();f.cloud.fail='after'
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertTrue(f.closed);self.assertEqual(f.cloud.state['current']['releaseId'],'next')
        self.assertEqual(run(f,request()),'done');self.assertFalse(f.closed)
    def test_cas_not_written_restores_old(self):
        f=Fake();f.cloud.fail='before'
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertEqual(run(f,request()),'rolled-back')
        self.assertFalse(f.closed);self.assertEqual(f.cloud.state['current']['releaseId'],'old')
    def test_foreign_generation_stays_closed(self):
        f=Fake();f.fail='health'
        with self.assertRaises(ReleaseError):run(f,request())
        f.fail=None;f.cloud.generation+=1
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertTrue(f.closed)
    def test_be_web_concurrent_stale_current(self):
        f=Fake();run(f,request())
        with self.assertRaises(ReleaseError):run(f,request(manifest('web-next'),op='op2'))
        self.assertEqual(f.cloud.state['current']['releaseId'],'next')
    def test_cancelled_runner_has_no_engine_cancellation_input(self):
        f=Fake();f.cloud.fail='after'
        with self.assertRaises(ReleaseError):run(f,request())
        self.assertEqual(recover(f,f.journal('op1')),'done')
    def test_rollback_exact_previous_only(self):
        f=Fake()
        with self.assertRaises(ReleaseError):run(f,request(operation='rollback'))
        self.assertEqual(run(f,request(manifest('older'),operation='rollback')),'done')
    def test_bad_manifest_pins_and_ai_rejected(self):
        mutations=[lambda m:m.update(aiEnabled=True),lambda m:m['be'].update(image='latest'),
                   lambda m:m['secretVersions'].update(dbPassword='projects/capsnote/secrets/db/versions/latest'),
                   lambda m:m.update(configRevision='unversioned'),lambda m:m['flyway']['migrations'].reverse()]
        for mutation in mutations:
            m=manifest();mutation(m)
            with self.assertRaises(ReleaseError):validate(m)

if __name__=='__main__':unittest.main()
