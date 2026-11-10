import base64
import contextlib
import fcntl
import hashlib
import importlib.util
import io
import json
import lzma
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
import test_release

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('host_release_maintenance',ROOT/'deploy/host/release.py')
release=importlib.util.module_from_spec(spec)
with patch.dict(sys.modules,{'boto3':MagicMock()}):spec.loader.exec_module(release)
spec=importlib.util.spec_from_file_location('cfn_build',ROOT/'infra/cloudformation/build.py')
build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
GCP_IP='34.64.150.238'
REAL_PREFLIGHT=release.Host.preflight


class Runner:
    """Fake fixed host commands: Nginx, Compose, docker ps and psql."""
    def __init__(self):
        self.be=True;self.calls=[]
    def __call__(self,args,env=None,input=None):
        self.calls.append(list(args))
        if args[:2]==['docker','compose']:
            if 'stop' in args and args[-1]=='be':self.be=False
            if 'up' in args and args[-1]=='be':self.be=True
            return ''
        if args[:2]==['docker','ps']:return 'c0ffee\n' if self.be else ''
        if args[0]=='psql':
            if 'pg_stat_activity' in args[-1]:return ('10' if self.be else '0')+'|1\n'
            if 'to_regclass' in args[-1]:return 't\n'
            return json.dumps([{'installed_rank':1,'version':'2','checksum':1,'success':True}])
        return ''
    def used(self,*prefix):
        return [c for c in self.calls if c[:len(prefix)]==list(prefix)]


def local_status(host,path):
    # Stand-in for the loopback HTTPS request: answer from the written Nginx route.
    return {'maintenance':503,'live':200}.get(host.nginx_mode(),0)


def smoke_public(host,m):
    if host.nginx_mode()!='live':raise release.ReleaseError('External HTTPS readiness timeout')


class MaintenanceTests(unittest.TestCase):
    def setUp(self):
        tmp=Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.state=tmp/'etc';self.state.mkdir()
        (self.state/'pipeline-version').write_text('4\n')
        (self.state/'production.json').write_text(json.dumps({'configRevision':'p8-1','allowedMode':'api-only','googleEnabled':False,'googleClientId':''}))
        (self.state/'infrastructure.json').write_text(json.dumps({'dbEndpoint':'db.internal','appDomain':'capsnote.art'}))
        (self.state/'db-bootstrap-complete').touch()
        self.manifest=test_release.manifest('old')
        (self.state/'current.json').write_text(json.dumps(self.manifest))
        self.runner=Runner();self.events=[]
        ssm=release.boto3.client.return_value
        ssm.get_parameter.return_value={'Parameter':{'Value':'SECRET-VALUE','Version':1}}
        for target,value in [('STATE',self.state),('BASE',tmp/'releases'),('RUNTIME',tmp/'run'),('NGINX',tmp/'capstone.conf'),
                             ('LOCK',tmp/'release.lock'),('run',self.runner),('private_json',lambda p:json.loads(p.read_text()))]:
            self.enterContext(patch.object(release,target,value))
        self.enterContext(patch.object(release.os,'geteuid',return_value=0))
        self.enterContext(patch.object(release.Host,'local_status',local_status))
        self.enterContext(patch.object(release.Host,'smoke_public',smoke_public))
        self.enterContext(patch.object(release.Host,'wait_be',lambda host:self.events.append('wait-be')))
        self.enterContext(patch.object(release.Host,'smoke',lambda host,m:self.events.append('smoke')))
        self.enterContext(patch.object(release.Host,'preflight',side_effect=lambda m,is_resume=False:self.events.append(('preflight',is_resume))))
        self.enterContext(patch.object(release.time,'sleep'))
        # Live service before the cutover window.
        host=object.__new__(release.Host);host.maintenance(False);self.runner.calls.clear()

    def invoke(self,*argv,env=None):
        out=io.StringIO()
        with patch.object(sys,'argv',['release.py',*argv]),patch.dict(os.environ,env or {}),contextlib.redirect_stdout(out):
            try:release.main();code=0
            except SystemExit as exit:code=exit.code
            except Exception:code='error'
        return code,out.getvalue()

    def held(self):return (self.state/release.HOLD).exists()
    def mode(self):return release.Host.nginx_mode(None)

    def test_on_closes_nginx_stops_be_and_confirms_503(self):
        code,out=self.invoke('--maintenance','on')
        self.assertEqual(code,0)
        summary=json.loads(out)
        self.assertEqual((summary['hold'],summary['nginx'],summary['localStatus'],summary['beRunning'],summary['dbAppSessions']),(True,'maintenance',503,False,0))
        self.assertIn('add_header Retry-After 30 always; return 503;',release.NGINX.read_text())
        self.assertTrue(self.held() and not self.runner.be)
        self.assertEqual(self.runner.used('nginx','-t'),[['nginx','-t']])
        stop=[c for c in self.runner.used('docker','compose') if 'stop' in c]
        self.assertEqual(stop[0][-4:],['stop','-t','30','be'])
        self.assertLess(self.runner.calls.index(['systemctl','reload','nginx']),self.runner.calls.index(stop[0]))
        self.assertFalse([c for c in self.runner.calls if 'web' in c])  # web stays; it only serves behind host Nginx

    def test_on_and_status_do_not_depend_on_dns(self):
        with patch.object(release.socket,'gethostbyname',side_effect=AssertionError('DNS used')) as dns:
            self.assertEqual(self.invoke('--maintenance','on')[0],0)
            code,out=self.invoke('--maintenance','status')
        self.assertEqual(code,0);self.assertTrue(json.loads(out)['hold'])
        dns.assert_not_called()

    def test_on_is_idempotent_and_keeps_first_hold_time(self):
        self.invoke('--maintenance','on');first=(self.state/release.HOLD).read_text()
        self.assertEqual(self.invoke('--maintenance','on')[0],0)
        self.assertEqual((self.state/release.HOLD).read_text(),first)

    def test_on_follows_release_lock(self):
        with open(release.LOCK,'a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(self.invoke('--maintenance','on')[0],'error')
        self.assertFalse(self.held());self.assertEqual(self.mode(),'live');self.assertEqual(self.runner.calls,[])

    def test_on_closes_beside_unresolved_journal_but_off_refuses(self):
        (self.state/'release-pending.json').write_text('{"phase":"switch"}')
        self.assertEqual(self.invoke('--maintenance','on')[0],0)
        self.assertEqual(self.invoke('--maintenance','off')[0],'error')
        self.assertTrue(self.held());self.assertEqual(self.mode(),'maintenance');self.assertFalse(self.runner.be)

    def test_status_is_one_secret_free_json_line_without_changes(self):
        code,out=self.invoke('--maintenance','status')
        self.assertEqual(code,0);self.assertEqual(out.count('\n'),1)
        summary=json.loads(out)
        self.assertEqual(set(summary),{'hold','holdSince','nginx','localStatus','beRunning','releaseId','pendingRelease','dbAppSessions','dbOtherRoleSessions'})
        self.assertEqual((summary['hold'],summary['nginx'],summary['beRunning'],summary['releaseId'],summary['dbAppSessions'],summary['dbOtherRoleSessions']),(False,'live',True,'old',10,1))
        for value in ['SECRET-VALUE','sha256:','digest','be.properties']:self.assertNotIn(value,out)
        self.assertFalse(self.runner.used('nginx')+self.runner.used('systemctl')+self.runner.used('docker','compose'))

    def test_status_reports_unknown_sessions_when_db_unreachable(self):
        def failing(args,env=None,input=None):
            if args[0]=='psql':raise release.ReleaseError('Fixed host command failed (output suppressed)')
            return self.runner(args,env,input)
        with patch.object(release,'run',failing):code,out=self.invoke('--maintenance','status')
        self.assertEqual(code,0);self.assertIsNone(json.loads(out)['dbAppSessions'])

    def test_invalid_arguments_are_rejected_before_any_change(self):
        for argv in [['--maintenance'],['--maintenance','restart'],['--maintenance','ON'],['--maintenance','on','off'],
                     ['--maintenance','on; touch /tmp/x'],['--resume','--maintenance','on'],['--maintenance=on']]:
            self.assertEqual(self.invoke(*argv)[0],'error',argv)
        self.assertEqual(self.runner.calls,[]);self.assertFalse(self.held());self.assertEqual(self.mode(),'live')

    def test_off_refuses_when_dns_no_longer_points_to_aws(self):
        self.invoke('--maintenance','on')
        with patch.object(release.Host,'preflight',REAL_PREFLIGHT),patch.object(release.socket,'gethostbyname',return_value=GCP_IP),patch.object(release.shutil,'disk_usage',return_value=MagicMock(free=10*1024**3)):
            self.assertEqual(self.invoke('--maintenance','off')[0],'error')
        self.assertTrue(self.held());self.assertEqual(self.mode(),'maintenance');self.assertFalse(self.runner.be)
        self.assertFalse([c for c in self.runner.used('docker','compose') if 'up' in c])

    def test_off_check_failure_keeps_hold_and_stops_be_again(self):
        self.invoke('--maintenance','on')
        with patch.object(release.Host,'smoke',side_effect=release.ReleaseError('API health smoke failed')):
            self.assertEqual(self.invoke('--maintenance','off')[0],'error')
        self.assertTrue(self.held());self.assertEqual(self.mode(),'maintenance');self.assertFalse(self.runner.be)

    def test_off_reopens_only_after_resume_checks(self):
        self.invoke('--maintenance','on');self.runner.calls.clear()
        code,out=self.invoke('--maintenance','off')
        self.assertEqual(code,0)
        summary=json.loads(out)
        self.assertEqual((summary['hold'],summary['nginx'],summary['beRunning']),(False,'live',True))
        self.assertEqual(self.events,[('preflight',True),'wait-be','smoke'])
        self.assertTrue(self.runner.used('psql'))  # schema must still equal the committed release

    def test_off_without_hold_is_refused(self):
        self.assertEqual(self.invoke('--maintenance','off')[0],'error')
        self.assertEqual(self.runner.calls,[]);self.assertEqual(self.events,[])

    def test_reboot_resume_during_hold_keeps_writes_stopped(self):
        self.invoke('--maintenance','on');self.runner.calls.clear()
        for running in [False,True]:  # stopped BE stays stopped; an unexpected running BE is stopped
            self.runner.be=running
            self.assertEqual(self.invoke('--resume'),(0,'Operator maintenance hold kept after boot; BE not started.\n'))
            self.assertEqual(self.mode(),'maintenance');self.assertFalse(self.runner.be);self.assertTrue(self.held())
        self.assertFalse(self.runner.used('psql'))  # no DB session during hold
        self.assertFalse([c for c in self.runner.used('docker','compose') if 'up' in c])
        self.assertEqual(self.events,[])

    def test_reboot_resume_without_hold_still_opens(self):
        code,out=self.invoke('--resume')
        self.assertEqual((code,out),(0,'Current release resumed after boot.\n'))
        self.assertEqual(self.mode(),'live');self.assertEqual(self.events,[('preflight',True),'wait-be','smoke'])
        reloads=[i for i,c in enumerate(self.runner.calls) if c==['systemctl','reload','nginx']]
        up=[i for i,c in enumerate(self.runner.calls) if c[:2]==['docker','compose'] and 'up' in c]
        self.assertEqual(len(reloads),2);self.assertLess(reloads[0],up[0]);self.assertLess(up[-1],reloads[1])

    def deploy_env(self):
        m=test_release.manifest('next')
        return {'SSM_ManifestJson':json.dumps(m),'SSM_ManifestSha':release.checksum(m),'SSM_ReleaseId':'next','SSM_BeDigest':m['be']['digest'],
                'SSM_WebDigest':m['web']['digest'],'SSM_ExpectedRelease':'old','SSM_Operation':'deploy'}

    def test_deploy_or_rollback_during_hold_is_refused_before_any_change(self):
        self.invoke('--maintenance','on');self.runner.calls.clear()
        with patch.object(release,'transition') as transition:
            for operation in ['deploy','rollback']:
                self.assertEqual(self.invoke(env=dict(self.deploy_env(),SSM_Operation=operation))[0],'error')
        transition.assert_not_called();self.assertEqual(self.runner.calls,[]);self.assertEqual(self.mode(),'maintenance')

    def test_deploy_without_hold_runs_transition(self):
        with patch.object(release,'transition') as transition:
            self.assertEqual(self.invoke(env=self.deploy_env())[0],0)
        host,m,expected,operation=transition.call_args.args
        self.assertIsInstance(host,release.Host);self.assertEqual((m['releaseId'],expected,operation),('next','old','deploy'))

    def test_transition_cannot_open_during_hold(self):
        self.invoke('--maintenance','on')
        host=object.__new__(release.Host)
        with self.assertRaises(release.ReleaseError):host.maintenance(False)
        self.assertEqual(self.mode(),'maintenance')


class MaintenanceDocumentTests(unittest.TestCase):
    def setUp(self):
        self.template=build.bootstrap()

    def test_document_accepts_only_fixed_operations(self):
        doc=self.template['Resources']['MaintenanceDocument']['Properties']
        self.assertEqual((doc['Name'],doc['DocumentType'],doc['UpdateMethod']),('capstone-maintenance','Command','NewVersion'))
        self.assertEqual(doc['Content']['parameters'],{'MaintenanceOperation':{'type':'String','allowedValues':['on','off','status'],'interpolationType':'ENV_VAR'}})
        steps=doc['Content']['mainSteps']
        self.assertEqual(len(steps),1)
        self.assertEqual(steps[0]['inputs']['runCommand'],['/opt/capstone/tools/bin/python /opt/capstone/host/release.py --maintenance "$SSM_MaintenanceOperation"'])
        self.assertNotIn('{{',json.dumps(doc))

    def test_document_value_reaches_host_as_one_argument(self):
        command=self.template['Resources']['MaintenanceDocument']['Properties']['Content']['mainSteps'][0]['inputs']['runCommand'][0]
        probe=command.replace('/opt/capstone/tools/bin/python /opt/capstone/host/release.py',sys.executable+' -c "import json,sys;print(json.dumps(sys.argv[1:]))"')
        for value in ['on','on; touch injected',"$(touch injected)"]:
            out=subprocess.run(['bash','-c',probe],env=dict(os.environ,SSM_MaintenanceOperation=value),capture_output=True,text=True,check=True,cwd=tempfile.gettempdir()).stdout
            self.assertEqual(json.loads(out),['--maintenance',value])

    def test_deploy_role_cannot_send_maintenance_document(self):
        statements=self.template['Resources']['DeployRole']['Properties']['Policies'][0]['PolicyDocument']['Statement']
        documents=[s['Resource'] for s in statements if s['Action']=='ssm:SendCommand' and ':document/' in s['Resource']]
        self.assertEqual(documents,['arn:aws:ssm:ap-northeast-2:378040395204:document/capstone-deploy'])

    def test_generated_templates_match_committed_files(self):
        here=ROOT/'infra/cloudformation'
        self.assertEqual(json.loads((here/'bootstrap.json').read_text()),self.template)
        self.assertEqual(json.loads((here/'runtime.json').read_text()),build.runtime())
        self.assertLessEqual(len((here/'bootstrap.json').read_bytes()),51200)

    def test_shared_archive_contains_reviewed_host_files(self):
        archive=base64.b64decode(self.template['Mappings']['HostAssets']['Content']['Archive'])
        with tarfile.open(fileobj=io.BytesIO(lzma.decompress(archive))) as files:
            hashes=json.loads(files.extractfile('host/pipeline-files.json').read())
            self.assertEqual(files.extractfile('host/release.py').read(),(ROOT/'deploy/host/release.py').read_bytes())
        self.assertEqual(hashes['release.py'],hashlib.sha256((ROOT/'deploy/host/release.py').read_bytes()).hexdigest())
        self.assertEqual(set(build.PIPELINE_BASELINE),{'release.py'})
        self.assertNotEqual(build.PIPELINE_BASELINE['release.py'],hashes['release.py'])


if __name__=='__main__':unittest.main()
