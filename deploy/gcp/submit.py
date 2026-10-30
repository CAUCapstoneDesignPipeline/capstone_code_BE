#!/usr/bin/env python3
"""Root-only submit of validated JSON on stdin; systemd owns operation lifetime."""
import json
import os
import subprocess
import sys
from contract import canonical, checksum, require, validate_request
from host import ROOT, atomic, trusted


def submit(request):
    require(os.geteuid() == 0, 'Root required')
    validate_request(request)
    operation = request['operationId']
    path = ROOT / 'requests' / (operation + '.json')
    # Serialize submission separately from execution; never overwrite accepted input.
    import fcntl
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (ROOT / 'submit.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if path.exists():
            require(checksum(trusted(path)) == checksum(request), 'Operation payload conflict')
        else:
            atomic(path, canonical(request))
        result = subprocess.run(['systemctl', 'start', '--no-block', 'capstone-gcp-operation@' + operation + '.service'], capture_output=True)
        require(result.returncode == 0, 'Unable to enqueue operation')
    return {'operationId': operation, 'status': 'submitted'}


if __name__ == '__main__':
    try:
        payload = sys.stdin.buffer.read(32769)
        require(len(payload) <= 32768, 'Oversized request')
        print(json.dumps(submit(json.loads(payload))))
    except Exception:
        raise SystemExit('Operation submission rejected (details suppressed)') from None
