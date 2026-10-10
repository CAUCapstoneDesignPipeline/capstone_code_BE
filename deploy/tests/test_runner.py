import contextlib
import importlib.util
import io
import json
import os
import unittest
from unittest.mock import patch
from pathlib import Path
import test_release
spec=importlib.util.spec_from_file_location('runner',Path(__file__).resolve().parents[1]/'pipeline/release-command.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class Result:
    def __init__(self,status='Success',code=0):
        self.returncode=0;self.stderr='';self.stdout=json.dumps({'Status':status,'ResponseCode':code,'StandardOutputContent':'sensitive','StandardErrorContent':'sensitive'})

class RunnerTests(unittest.TestCase):
    def test_default_execution_disabled(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(runner.ReleaseError):runner.check(test_release.manifest())
    def test_dev_ref_rejected_even_when_enabled(self):
        with patch.dict(os.environ,{'GITHUB_REF':'refs/heads/dev','PRODUCTION_DEPLOY_ENABLED':'true','SSM_DOCUMENT_VERSION':'3'},clear=True):
            with self.assertRaises(runner.ReleaseError):runner.check(test_release.manifest())
    def test_p6_document_rejected(self):
        with patch.dict(os.environ,{'GITHUB_REF':'refs/heads/main','PRODUCTION_DEPLOY_ENABLED':'true','SSM_DOCUMENT_VERSION':'2'},clear=True):
            with self.assertRaises(runner.ReleaseError):runner.check(test_release.manifest())
    def test_valid_later_document_version(self):
        with patch.dict(os.environ,{'GITHUB_REF':'refs/heads/main','PRODUCTION_DEPLOY_ENABLED':'true','SSM_DOCUMENT_VERSION':'10'},clear=True):runner.check(test_release.manifest())
    def test_ssm_failure_is_not_success(self):
        with patch.object(runner.subprocess,'run',return_value=Result('Failed',1)):
            with self.assertRaises(runner.ReleaseError):runner.wait('command')
    def test_success_status_with_nonzero_exit_fails(self):
        with patch.object(runner.subprocess,'run',return_value=Result('Success',1)):
            with self.assertRaises(runner.ReleaseError):runner.wait('command')
    def test_no_raw_ssm_output_is_printed(self):
        out=io.StringIO()
        with patch.object(runner.subprocess,'run',return_value=Result()),contextlib.redirect_stdout(out):runner.wait('command')
        self.assertNotIn('sensitive',out.getvalue())
    def test_timeout_never_sends_another_command(self):
        with patch.object(runner.subprocess,'run',return_value=Result('InProgress',-1)) as run,patch.object(runner.time,'monotonic',side_effect=[0,1,100]),patch.object(runner.time,'sleep'):
            with self.assertRaises(runner.ReleaseError):runner.wait('command',deadline_seconds=5)
            self.assertEqual(run.call_count,1)
            self.assertNotIn('send-command',run.call_args.args[0])

if __name__=='__main__':unittest.main()
