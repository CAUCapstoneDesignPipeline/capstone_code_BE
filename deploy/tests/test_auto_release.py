import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import test_release

spec=importlib.util.spec_from_file_location('auto_release',Path(__file__).resolve().parents[1]/'pipeline/auto-release.py')
auto=importlib.util.module_from_spec(spec);spec.loader.exec_module(auto)
SOURCE='b'*40
DIGEST='sha256:'+'b'*64

def before():
    value=test_release.manifest()
    value['mode']='notes-web';value['configRevision']='google-notes-v1'
    value['secretVersions'].update(googleClientSecret=1,allowedEmails=2)
    return value

class AutoReleaseTests(unittest.TestCase):
    def setUp(self):
        self.env=patch.dict(os.environ,{'GITHUB_REPOSITORY':auto.OWNER+'/'+auto.REPOS['web'],'GITHUB_EVENT_NAME':'push',
            'GITHUB_REF':'refs/heads/main','GITHUB_SHA':SOURCE,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1',
            'SSM_DOCUMENT_VERSION':'4','PRODUCTION_DEPLOY_ENABLED':'true'},clear=True)
        self.env.start();self.addCleanup(self.env.stop)
        self.folder=tempfile.TemporaryDirectory();self.addCleanup(self.folder.cleanup)
        self.path=Path(self.folder.name)

    def test_component_update_preserves_live_oauth_and_other_image(self):
        initial=before();target=auto.target(initial,'web',SOURCE,DIGEST,1)
        self.assertEqual(target['be'],initial['be'])
        self.assertEqual(target['secretVersions'],initial['secretVersions'])
        self.assertEqual(target['configRevision'],initial['configRevision'])
        self.assertEqual(target['mode'],'notes-web')
        self.assertEqual(target['flyway']['after'],initial['flyway']['after'])
        self.assertFalse(target['aiEnabled'])
        self.assertEqual(initial,before())

    def test_changed_db_migration_blocks_before_send_command(self):
        os.environ['GITHUB_REPOSITORY']=auto.OWNER+'/'+auto.REPOS['be']
        with patch.object(auto,'idle'),patch.object(auto,'current',return_value=before()),\
             patch.object(auto,'github',return_value={'object':{'sha':SOURCE}}),\
             patch.object(auto,'migrations',side_effect=[{'V1':'old'},{'V1':'old','V3':'new'}]),\
             patch.object(auto,'send') as send:
            with self.assertRaises(auto.ReleaseError):auto.deploy('be',SOURCE,DIGEST,self.path)
            send.assert_not_called()

    def test_superseded_main_run_never_deploys(self):
        with patch.object(auto,'idle'),patch.object(auto,'github',return_value={'object':{'sha':'c'*40}}),patch.object(auto,'send') as send:
            self.assertEqual(auto.deploy('web',SOURCE,DIGEST,self.path)['status'],'superseded')
            send.assert_not_called()

    def test_concurrent_commit_is_rebased_without_losing_other_service(self):
        initial=before();peer=before();peer['releaseId']='peer-be';peer['be']={'sourceSha':'c'*40,'digest':'sha256:'+'c'*64}
        sent=[]
        def send(target,base):sent.append((target,base));return 'command'
        # First terminal failure is a stale snapshot; peer commit is independently observed.
        with patch.object(auto,'idle'),patch.object(auto,'github',return_value={'object':{'sha':SOURCE}}),\
             patch.object(auto,'current',side_effect=[initial,peer,peer,dict(auto.target(peer,'web',SOURCE,DIGEST,2))]),\
             patch.object(auto.runner,'check'),patch.object(auto,'send',side_effect=send),\
             patch.object(auto.runner,'wait',side_effect=[auto.runner.CommandFailed('Failed'),None]):
            result=auto.deploy('web',SOURCE,DIGEST,self.path)
        self.assertEqual(result['status'],'success')
        self.assertEqual(sent[1][0]['be'],peer['be'])
        self.assertEqual(sent[1][1]['releaseId'],'peer-be')
        self.assertEqual(sent[1][0]['secretVersions']['allowedEmails'],2)

    def test_application_failure_without_competing_commit_is_not_retried(self):
        with patch.object(auto,'idle'),patch.object(auto,'github',return_value={'object':{'sha':SOURCE}}),\
             patch.object(auto,'current',return_value=before()),patch.object(auto.runner,'check'),\
             patch.object(auto,'send',return_value='command') as send,\
             patch.object(auto.runner,'wait',side_effect=auto.runner.CommandFailed('Failed')):
            with self.assertRaises(auto.ReleaseError):auto.deploy('web',SOURCE,DIGEST,self.path)
            self.assertEqual(send.call_count,1)

    def test_unknown_completion_timeout_is_not_retried(self):
        with patch.object(auto,'idle'),patch.object(auto,'github',return_value={'object':{'sha':SOURCE}}),\
             patch.object(auto,'current',return_value=before()),patch.object(auto.runner,'check'),\
             patch.object(auto,'send',return_value='command') as send,\
             patch.object(auto.runner,'wait',side_effect=auto.ReleaseError('Timeout; command may still run')):
            with self.assertRaises(auto.ReleaseError):auto.deploy('web',SOURCE,DIGEST,self.path)
            self.assertEqual(send.call_count,1)

    def test_wrong_repository_cannot_deploy_another_component(self):
        with patch.object(auto,'send') as send:
            with self.assertRaises(auto.ReleaseError):auto.deploy('be',SOURCE,DIGEST,self.path)
            send.assert_not_called()

    def test_already_current_rerun_does_not_restart_services(self):
        initial=before();initial['web']={'sourceSha':SOURCE,'digest':DIGEST}
        with patch.object(auto,'idle'),patch.object(auto,'github',return_value={'object':{'sha':SOURCE}}),\
             patch.object(auto,'current',return_value=initial),patch.object(auto,'send') as send:
            self.assertEqual(auto.deploy('web',SOURCE,DIGEST,self.path)['status'],'already-current')
            send.assert_not_called()

if __name__=='__main__':unittest.main()
