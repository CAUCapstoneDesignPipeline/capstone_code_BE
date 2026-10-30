import json,pathlib,subprocess,sys,tempfile,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'pipeline'))
from smoke_cleanup import cleanup,LABEL
P='capstone-image-test-123456abcdef'
class Fake:
 def __init__(self,mode):self.mode=mode;self.remaining=True;self.removals=[]
 def __call__(self,args,**kw):
  a=args[1:]
  if self.mode=='timeout':raise subprocess.TimeoutExpired(args,30)
  if a[:2]==['container','inspect']:
   labels=None if self.mode=='null' else {LABEL:'foreign' if self.mode=='foreign' else P}
   return subprocess.CompletedProcess(args,0,json.dumps([{'Config':{'Labels':labels}}]),'')
  if a[0]=='rm':
   self.removals.append(a[-1])
   if self.mode=='rmfail':return subprocess.CompletedProcess(args,1,'','failed')
   self.remaining=False
  output=(P+'-be\n') if a[0]=='ps' and self.remaining else ''
  return subprocess.CompletedProcess(args,0,output,'')
class CleanupTests(unittest.TestCase):
 def test_success_confirmed(self):
  with tempfile.TemporaryDirectory() as d:
   f=Fake('ok');p=pathlib.Path(d)/'run.json';cleanup(p,P,[P+'-be'],False,run=f)
   self.assertEqual(json.loads(p.read_text())['status'],'cleanup-complete');self.assertEqual(f.removals,[P+'-be'])
 def test_removal_failure_rejects_success(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'run.json'
   with self.assertRaisesRegex(RuntimeError,'preserve the run ledger'):cleanup(p,P,[P+'-be'],False,run=Fake('rmfail'))
   self.assertEqual(json.loads(p.read_text())['status'],'cleanup-failed')
 def test_foreign_label_never_removed(self):
  with tempfile.TemporaryDirectory() as d:
   f=Fake('foreign')
   with self.assertRaises(RuntimeError):cleanup(pathlib.Path(d)/'run.json',P,[P+'-be'],False,run=f)
   self.assertEqual(f.removals,[])
 def test_null_labels_preserve_foreign_object_and_record_failure(self):
  with tempfile.TemporaryDirectory() as d:
   f=Fake('null');p=pathlib.Path(d)/'run.json'
   with self.assertRaises(RuntimeError):cleanup(p,P,[P+'-be'],False,run=f)
   self.assertEqual(f.removals,[])
   self.assertEqual(json.loads(p.read_text())['status'],'cleanup-failed')
 def test_created_network_is_cleaned_when_create_response_was_lost(self):
  calls=[]
  def run(args,**kw):
   calls.append(args)
   body=json.dumps([{'Labels':{LABEL:P}}]) if args[1:3]==['network','inspect'] else ''
   return subprocess.CompletedProcess(args,0,body,'')
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'run.json';cleanup(p,P,[],True,run=run)
   self.assertIn(['docker','network','rm',P],calls)
   self.assertEqual(json.loads(p.read_text())['status'],'cleanup-complete')
 def test_daemon_timeout_is_failure(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'run.json'
   with self.assertRaises(RuntimeError):cleanup(p,P,[P+'-be'],False,run=Fake('timeout'))
   self.assertEqual(json.loads(p.read_text())['status'],'cleanup-failed')
if __name__=='__main__':unittest.main()
