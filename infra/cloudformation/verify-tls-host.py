#!/opt/capstone/tools/bin/python
"""Fixed read-only TLS checks; emit booleans and never open private keys."""
import json
import stat
import subprocess
import sys
import time
import uuid
from pathlib import Path
import boto3

def command(*args):
    return subprocess.run(args, capture_output=True, text=True, timeout=20)

def main():
    checks = {}
    checks['nginx_active'] = command('systemctl', 'is-active', '--quiet', 'nginx').returncode == 0
    checks['nginx_config_valid'] = command('nginx', '-t').returncode == 0
    checks['renew_timer_active'] = command('systemctl', 'is-active', '--quiet', 'capstone-cert-renew.timer').returncode == 0
    checks['renew_timer_enabled'] = command('systemctl', 'is-enabled', '--quiet', 'capstone-cert-renew.timer').returncode == 0
    next_run = command('systemctl', 'show', 'capstone-cert-renew.timer', '-p', 'NextElapseUSecRealtime', '--value')
    checks['renew_next_run_scheduled'] = next_run.returncode == 0 and next_run.stdout.strip() not in ['', 'n/a', '0']
    unit = Path('/etc/systemd/system/capstone-cert-renew.service')
    timer = Path('/etc/systemd/system/capstone-cert-renew.timer')
    checks['renew_units_root_owned'] = all(p.is_file() and p.stat().st_uid == 0
                                          and not stat.S_IMODE(p.stat().st_mode) & 0o022 for p in [unit, timer])
    service = unit.read_text()
    schedule = timer.read_text()
    checks['renew_command_and_reload_hook'] = 'certbot renew --quiet --deploy-hook "systemctl reload nginx"' in service
    checks['renew_twice_daily_with_jitter'] = 'OnCalendar=*-*-* 03,15:00:00' in schedule and 'RandomizedDelaySec=3600' in schedule and 'Persistent=true' in schedule
    cert = '/etc/letsencrypt/live/capsnote.art/cert.pem'
    checks['certificate_over_14_days'] = command('openssl', 'x509', '-in', cert, '-checkend', '1209600', '-noout').returncode == 0
    checks['certificate_hostname'] = command('openssl', 'x509', '-in', cert, '-checkhost', 'capsnote.art', '-noout').returncode == 0
    key = Path('/etc/letsencrypt/live/capsnote.art/privkey.pem').resolve(strict=True).stat()
    checks['private_key_root_only'] = key.st_uid == 0 and not stat.S_IMODE(key.st_mode) & 0o077
    checks['application_not_deployed'] = not any(Path(p).exists() for p in ['/etc/capstone/current.json', '/opt/capstone/current.json', '/etc/capstone/release-pending.json'])
    result = {'operation': 'verify-tls', 'exitCode': 0 if all(checks.values()) else 1, 'checks': checks}
    logs = boto3.client('logs', region_name='ap-northeast-2')
    stream = 'capstone-prod/verify-tls/' + str(uuid.uuid4())
    logs.create_log_stream(logGroupName='/capstone/prod/ssm', logStreamName=stream)
    logs.put_log_events(logGroupName='/capstone/prod/ssm', logStreamName=stream,
                        logEvents=[{'timestamp': int(time.time() * 1000), 'message': json.dumps(result, sort_keys=True)}])
    print(json.dumps(checks, sort_keys=True))
    return result['exitCode']

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('TLS verification failed: ' + type(error).__name__ + ' (details suppressed)', file=sys.stderr)
        sys.exit(1)
