"""Record smoke-test-owned object names before creation and fail closed on cleanup errors."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

LABEL='art.capsnote.image-smoke.run'

def record(path, prefix, containers, network, status, errors=None):
    if not re.fullmatch(r'capstone-image-test-[0-9a-f]{12}',prefix):
        raise ValueError('Invalid run prefix')
    if any(not n.startswith(prefix+'-') for n in containers):
        raise ValueError('Unowned container intent')
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    value={'prefix':prefix,'containers':list(containers),'network':prefix if network else None,'label':LABEL,'status':status,'errors':errors or []}
    fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.ledger-')
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:
            json.dump(value,f,sort_keys=True);f.flush();os.fsync(f.fileno())
        os.replace(tmp,path)
        fd=os.open(path.parent,os.O_RDONLY)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)

def cleanup(path,prefix,containers,network,run=subprocess.run):
    errors=[]
    def call(args):return run(['docker',*args],capture_output=True,text=True,timeout=30)
    objects=[('container',n) for n in reversed(containers)]+([('network',prefix)] if network else [])
    for kind,name in objects:
        try:
            current=call([kind,'inspect',name])
            if current.returncode:
                if 'No such' in current.stderr or 'not found' in current.stderr:continue
                raise RuntimeError('Inspection unavailable')
            obj=json.loads(current.stdout)[0]
            labels=(obj.get('Config',{}).get('Labels') if kind=='container' else obj.get('Labels')) or {}
            if labels.get(LABEL)!=prefix:raise RuntimeError('Ownership label mismatch')
            args=['rm','-f','-v',name] if kind=='container' else ['network','rm',name]
            if call(args).returncode:raise RuntimeError('Removal failed')
        except (OSError,ValueError,KeyError,IndexError,RuntimeError,subprocess.SubprocessError) as e:
            errors.append({'kind':kind,'name':name,'errorType':type(e).__name__})
    for args in [['ps','-a'],['network','ls']]:
        try:
            left=call([*args,'--filter','label='+LABEL+'='+prefix,'--format','{{.Name}}' if args[0]=='network' else '{{.Names}}'])
            if left.returncode or left.stdout.strip():errors.append({'check':args[0],'reason':'Could not confirm zero owned objects'})
        except (OSError,subprocess.SubprocessError) as e:errors.append({'check':args[0],'errorType':type(e).__name__})
    record(path,prefix,containers,network,'cleanup-failed' if errors else 'cleanup-complete',errors)
    if errors:raise RuntimeError('Image smoke cleanup failed; preserve the run ledger')
