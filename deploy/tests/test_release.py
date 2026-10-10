import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'host'))
from manifest import ReleaseError, checksum, transition, validate

def manifest(name='next',before='2',after='2'):
    return {'schemaVersion':2,'releaseId':name,'mode':'api-only','be':{'sourceSha':'a'*40,'digest':'sha256:'+'b'*64},
      'web':{'sourceSha':'c'*40,'digest':'sha256:'+'d'*64},'configRevision':'p8-1',
      'secretVersions':{'dbPassword':1,'jwtSecret':1,'googleClientSecret':0,'allowedEmails':0},
      'flyway':{'before':before,'after':after,'rollbackCompatibleWith':['2'],'backup':None},
      'gates':{'aiOffVerified':True},'aiEnabled':False,'aiImage':None}

class Fake:
    def __init__(self,current=True,fail=None,changed=False):
        self.events=[];self.old=manifest('old') if current else None;self.saved=manifest('previous');self.fail=fail
        self.before=[{'installed_rank':1,'version':'2','checksum':123,'success':True}] if current else []
        self.after=copy.deepcopy(self.before);self.changed=changed;self.started=False;self.closed=False
    def current(self):return self.old
    def previous(self):return self.saved
    def unused_release(self,m,a):return True
    def history(self):return self.after if self.started else self.before
    def preflight(self,m):
        self.events.append('preflight')
        if self.fail=='preflight':raise RuntimeError()
    def backup_ready(self,m):self.events.append('backup');raise ReleaseError('Unverified backup')
    def begin(self,m,old):self.events.append('journal')
    def finish(self):self.events.append('finish')
    def smoke_public(self,m):self.smoke(m)
    def maintenance(self,v):self.closed=v;self.events.append(('maintenance',v))
    def stop_be(self):self.events.append('drain')
    def start_be(self,m):
        self.started=True;self.events.append('start-be')
        if self.changed:self.after=[{'installed_rank':1,'version':'3','checksum':456,'success':True}]
    def wait_be(self):
        if self.fail=='health':raise RuntimeError()
    def start_web(self,m):self.events.append('start-web')
    def smoke(self,m):
        if self.fail=='smoke' and m['releaseId']!='old':raise RuntimeError()
    def commit(self,m,old):
        self.events.append('commit')
        if self.fail=='commit':raise RuntimeError()
    def restore(self,m):self.events.append('restore');self.fail=None

class ReleaseTests(unittest.TestCase):
    def test_success_drain_before_replace_and_commit_before_open(self):
        f=Fake();transition(f,manifest(),'old','deploy')
        self.assertLess(f.events.index('preflight'),f.events.index('drain'))
        self.assertLess(f.events.index('drain'),f.events.index('start-be'))
        self.assertLess(f.events.index('commit'),f.events.index(('maintenance',False)))
    def test_stale_release_has_no_effect(self):
        f=Fake()
        with self.assertRaises(ReleaseError):transition(f,manifest(),'stale','deploy')
        self.assertEqual(f.events,[])
    def test_preflight_failure_does_not_enter_maintenance(self):
        f=Fake(fail='preflight')
        with self.assertRaises(RuntimeError):transition(f,manifest(),'old','deploy')
        self.assertFalse(f.closed)
    def test_unchanged_schema_restores_old_pair(self):
        f=Fake(fail='health')
        with self.assertRaises(ReleaseError):transition(f,manifest(),'old','deploy')
        self.assertIn('restore',f.events);self.assertFalse(f.closed)
    def test_changed_schema_stays_closed(self):
        f=Fake(fail='health',changed=True)
        with self.assertRaises(ReleaseError):transition(f,manifest(),'old','deploy')
        self.assertNotIn('restore',f.events);self.assertTrue(f.closed)
    def test_changed_checksum_is_not_same_schema(self):
        f=Fake(fail='health');f.after[0]['checksum']=999
        with self.assertRaises(ReleaseError):transition(f,manifest(),'old','deploy')
        self.assertNotIn('restore',f.events);self.assertTrue(f.closed)
    def test_metadata_commit_failure_stays_closed(self):
        f=Fake(fail='commit')
        with self.assertRaises(ReleaseError):transition(f,manifest(),'old','deploy')
        self.assertNotIn('restore',f.events);self.assertTrue(f.closed)
    def test_first_failure_stays_closed(self):
        f=Fake(current=False,fail='health')
        with self.assertRaises(ReleaseError):transition(f,manifest(before='empty'),'none','deploy')
        self.assertTrue(f.closed)
    def test_migration_requires_reviewed_backup(self):
        f=Fake()
        with self.assertRaises(ReleaseError):transition(f,manifest(after='3'),'old','deploy')
        self.assertEqual(f.events,['backup'])
    def test_rollback_must_equal_previous_success(self):
        f=Fake()
        with self.assertRaises(ReleaseError):transition(f,manifest(),'old','rollback')
        self.assertEqual(f.events,[])
    def test_compatible_previous_rollback(self):
        f=Fake();transition(f,f.saved,'old','rollback');self.assertFalse(f.closed)
    def test_rollback_incompatible_schema_rejected(self):
        f=Fake();f.saved['flyway']['rollbackCompatibleWith']=[]
        with self.assertRaises(ReleaseError):transition(f,f.saved,'old','rollback')
        self.assertEqual(f.events,[])
    def test_reject_unapproved_ai_notes_web_tags_unknown_secret_fields(self):
        for field,value in [('mode','notes-web'),('aiEnabled',True),('releaseId','$(touch /tmp/pwn)'),('be',{'sourceSha':'a'*40,'digest':'latest'}),('password','bad')]:
            m=manifest();m[field]=value
            with self.assertRaises(ReleaseError):validate(m)
        m=manifest();m['gates']['aiOffVerified']=False
        with self.assertRaises(ReleaseError):validate(m)
    def test_hash_is_canonical(self):
        m=manifest();self.assertEqual(checksum(m),checksum(dict(reversed(list(m.items())))))

if __name__=='__main__':unittest.main()
