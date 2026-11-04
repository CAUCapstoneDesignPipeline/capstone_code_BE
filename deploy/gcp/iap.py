"""Connect through IAP to the independently verified app VM with a short-lived OS Login key."""
from contextlib import contextmanager
from pathlib import Path
import json
import subprocess
import tempfile

HOST = 'compute.5694175525603277900'
PUBLIC_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHrl52XcairjiVvsoXQ/DhwFve5RKufN2UgLq+TdiWCg'
DEPLOYER = 'sa_100105792096612852694'


@contextmanager
def connection():
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp) / 'key'
        known = Path(tmp) / 'known_hosts'
        known.write_text(HOST + ' ' + PUBLIC_KEY + '\n')
        subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(key)],check=True)
        pub = key.with_suffix('.pub')
        registration = ['gcloud','compute','os-login','ssh-keys']
        options = ['--project=capsnote','--quiet','--format=json']
        try:
            subprocess.run(registration+['add','--key-file='+str(pub),'--ttl=30m',*options],
                           stdout=subprocess.DEVNULL, check=True, timeout=60)
            yield ['ssh','-F','/dev/null','-T','-i',str(key),'-o','IdentitiesOnly=yes','-o','BatchMode=yes',
                   '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(known),
                   '-o','GlobalKnownHostsFile=/dev/null','-o','HostKeyAlgorithms=ssh-ed25519',
                   '-o','ConnectTimeout=15','-o','ProxyCommand=gcloud compute start-iap-tunnel capsnote-prod-app 22 --listen-on-stdin --project=capsnote --zone=asia-northeast3-a --verbosity=error --quiet',
                   DEPLOYER+'@'+HOST]
        finally:
            rows=json.loads(subprocess.check_output(registration+['list',*options],timeout=60))
            for row in rows:
                entry=row['value']
                if entry['key'].split()[:2] == pub.read_text().split()[:2]:
                    subprocess.run(registration+['remove','--key='+entry['fingerprint'],*options],
                                   stdout=subprocess.DEVNULL,check=True,timeout=60)
