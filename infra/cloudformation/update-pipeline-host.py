"""Update only reviewed runner files under the release lock; no service/data mutation."""
import fcntl
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

def require(condition):
    if not condition: raise RuntimeError('Reviewed pipeline update guard failed')

def main():
    require(os.geteuid()==0)
    state=Path('/etc/capstone');host=Path('/opt/capstone/host')
    def private(path):
        info=path.lstat()
        require(stat.S_ISREG(info.st_mode) and info.st_uid==0 and not stat.S_IMODE(info.st_mode)&0o022)
        return path.read_bytes()
    def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)
    with open('/run/capstone-release.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        require(not (state/'release-pending.json').exists())
        current=private(state/'current.json');manifest=json.loads(current)
        require(manifest['releaseId']==os.environ['SSM_ExpectedRelease'])
        require(hashlib.sha256(canonical(manifest).encode()).hexdigest()==os.environ['SSM_ManifestSha'])
        require(private(state/'pipeline-version').strip()==b'4')
        old=json.loads(private(host/'pipeline-files.json'))
        new=json.loads(REVIEWED_FILES['pipeline-files.json'])
        changed=__BASELINE_HASHES__
        require(set(old)==set(new) and set(REVIEWED_FILES)==set(new)|{'pipeline-files.json'})
        for name,digest in new.items():
            require(Path(name).name==name)
            data=private(host/name);actual=hashlib.sha256(data).hexdigest()
            require(hashlib.sha256(REVIEWED_FILES[name]).hexdigest()==digest)
            require(actual in ([changed[name],digest] if name in changed else [digest]))
            require(old[name] in ([changed[name],digest] if name in changed else [digest]))
        # The old source remains recoverable without touching application state.
        backup=state/'pipeline-backups'/'google-notes-v1'
        backup.mkdir(parents=True,exist_ok=True,mode=0o700)
        def atomic(path,data,mode):
            tmp=path.with_suffix(path.suffix+'.tmp')
            fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,mode)
            with os.fdopen(fd,'wb') as stream:
                stream.write(data);stream.flush();os.fsync(stream.fileno())
            os.chmod(tmp,mode);os.replace(tmp,path)
            fd=os.open(path.parent,os.O_RDONLY)
            try:os.fsync(fd)
            finally:os.close(fd)
        for name in [*changed,'pipeline-files.json']:
            if not (backup/name).exists():atomic(backup/name,private(host/name),0o600)
        for name in changed:atomic(host/name,REVIEWED_FILES[name],0o700)
        atomic(host/'pipeline-files.json',REVIEWED_FILES['pipeline-files.json'],0o600)
        require(private(state/'current.json')==current and not (state/'release-pending.json').exists())
        require(all(hashlib.sha256(private(host/name)).hexdigest()==digest for name,digest in new.items()))
        print(json.dumps({'pipelineUpdated':True,'releaseId':manifest['releaseId'],'files':sorted(changed),'containersRecreated':False,'migrationsExecuted':False,'activeConfigurationPreserved':True},sort_keys=True))

try:main()
except Exception as error:
    print('Pipeline update failed: '+type(error).__name__+' (details suppressed)',file=sys.stderr)
    sys.exit(1)
