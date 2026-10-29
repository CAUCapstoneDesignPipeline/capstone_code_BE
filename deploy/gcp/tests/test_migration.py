import copy
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('migration',Path(__file__).parents[1]/'migration/migrate.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class MigrationTest(unittest.TestCase):
    def test_every_writer_is_required(self):
        writers={k:True for k in ['awsAutoDeploy','gcpAutoDeploy','be','oauthCallback','oauthRefresh','worker','operatorScripts','databaseSessions']}
        m.writers_closed(writers)
        for key in writers:
            with self.assertRaises(m.MigrationError):m.writers_closed(dict(writers,**{key:False}))
    def test_data_and_flyway_mutation_rejected(self):
        source={'schemaVersion':1,'inventory':[{'kind':'table','rows':2,'sha256':'a'},{'kind':'flyway','history':[{'version':'2','checksum':123}]}]}
        m.compare(source,copy.deepcopy(source))
        for index,key,value in [(0,'rows',1),(0,'sha256','b'),(1,'history',[])]:
            target=copy.deepcopy(source);target['inventory'][index][key]=value
            with self.assertRaises(m.MigrationError):m.compare(source,target)
    def test_dns_only_rollback_after_writes_rejected(self):
        record={'writersStopped':{k:True for k in ['awsAutoDeploy','gcpAutoDeploy','be','oauthCallback','oauthRefresh','worker','operatorScripts','databaseSessions']},
                'gates':{k:True for k in ['restoreCompared','flywayVerified','tlsVerified','oauthVerified','aiOffVerified','backupVerified','aiStandaloneAccepted','feBeDeployAccepted']},
                'rollback':'before-writes-source-preserved','gcpWritesResumed':False}
        self.assertFalse(m.cutover_check(record)['executed'])
        for invalid in ['false',1,None]:
            bad=copy.deepcopy(record);bad['gates']['restoreCompared']=invalid
            with self.assertRaises(m.MigrationError):m.cutover_check(bad)
            bad=copy.deepcopy(record);bad['gcpWritesResumed']=invalid
            with self.assertRaises(m.MigrationError):m.cutover_check(bad)
        record['gcpWritesResumed']=True
        with self.assertRaises(m.MigrationError):m.cutover_check(record)
        record['rollback']='gcp-forward-or-release-rollback';m.cutover_check(record)

    def test_other_dump_source_or_digest_rejected_before_restore(self):
        class NoCalls:
            def __getattr__(self,name):raise AssertionError('Database access before provenance validation')
        evidence={'sourceIdentity':{'database':'source-a'},'sha256':'a'*64,'format':'postgresql16-custom'}
        for source,sha in [({'database':'source-b'},'a'*64),({'database':'source-a'},'b'*64)]:
            with self.assertRaises(m.MigrationError):m.restore_database(NoCalls(),'target',Path('/unused'),evidence,source,sha)
