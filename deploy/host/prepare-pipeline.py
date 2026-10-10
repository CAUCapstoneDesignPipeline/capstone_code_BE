#!/opt/capstone/tools/bin/python
"""Prepare root files on a host without an application; never deploy containers."""
from pathlib import Path
import os
import hashlib
import json
import stat
import subprocess
from manifest import require
from release import atomic,STATE

require(os.geteuid()==0 and not (STATE/'current.json').exists() and not (STATE/'release-pending.json').exists(), 'Initial pipeline preparation only')
host=Path('/opt/capstone/host')
def nginx_hashes():
    return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/etc/nginx').rglob('*') if p.is_file()}
nginx_before=nginx_hashes()
expected=json.loads((host/'pipeline-files.json').read_text())
checks={
    'archive_hashes': all(hashlib.sha256((host/name).read_bytes()).hexdigest()==digest for name,digest in expected.items()),
    'root_file_permissions': all((host/name).stat().st_uid==0 and stat.S_IMODE((host/name).stat().st_mode)==(0o700 if Path(name).suffix in ['.py','.sh'] else 0o600) for name in expected),
    'no_release_state': not any((STATE/name).exists() for name in ['current.json','previous.json','release-pending.json']),
}
require(all(checks.values()), 'Reviewed root archive verification failed')
subprocess.run(['install','-m','0644','/opt/capstone/host/resume.service','/etc/systemd/system/capstone-release-resume.service'],check=True)
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','capstone-release-resume.service'],check=True)
subprocess.run(['/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl','-a','fetch-config','-m','ec2','-c','file:/opt/capstone/host/cloudwatch.json','-s'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
atomic(STATE/'pipeline-version','3\n')
checks.update({
    'protocol_3': (STATE/'pipeline-version').read_text()=='3\n',
    'protocol_root_only': (STATE/'pipeline-version').stat().st_uid==0 and stat.S_IMODE((STATE/'pipeline-version').stat().st_mode)==0o600,
    'resume_unit_matches': Path('/etc/systemd/system/capstone-release-resume.service').read_bytes()==(host/'resume.service').read_bytes(),
    'resume_enabled': subprocess.run(['systemctl','is-enabled','--quiet','capstone-release-resume.service']).returncode==0,
    'resume_not_running': subprocess.run(['systemctl','is-active','--quiet','capstone-release-resume.service']).returncode!=0,
    'nginx_config_preserved': nginx_before==nginx_hashes(),
    'nginx_active': subprocess.run(['systemctl','is-active','--quiet','nginx']).returncode==0,
    'cloudwatch_active': subprocess.run(['systemctl','is-active','--quiet','amazon-cloudwatch-agent']).returncode==0,
    'no_production_config': not (STATE/'production.json').exists(),
    'no_app_containers': not subprocess.check_output(['docker','ps','-aq','--filter','name=capstone-be','--filter','name=capstone-web'],text=True).strip(),
})
print(json.dumps(checks,sort_keys=True))
require(all(checks.values()), 'Pipeline installation verification failed')
