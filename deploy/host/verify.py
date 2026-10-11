#!/opt/capstone/tools/bin/python
"""Read-only host checks. Only status and non-secret infrastructure data leave host."""
import json
import os
import subprocess
import sys
from pathlib import Path
import boto3

def main():
    config = json.loads(Path('/etc/capstone/infrastructure.json').read_text())
    checks = {}
    for service in ['amazon-ssm-agent', 'docker', 'nginx', 'amazon-cloudwatch-agent', 'capstone-metrics.timer']:
        checks[service] = subprocess.run(['systemctl', 'is-active', '--quiet', service]).returncode == 0
    checks['bootstrap'] = Path('/etc/capstone/bootstrap-complete').exists()
    checks['container_metadata_blocked'] = subprocess.run(['iptables', '-C', 'DOCKER-USER', '-d', '169.254.169.254/32', '-j', 'REJECT'], capture_output=True).returncode == 0
    checks['compose'] = subprocess.run(['docker', 'compose', 'version'], capture_output=True).returncode == 0
    ssm = boto3.client('ssm', region_name='ap-northeast-2')
    try:
        ssm.get_parameter(Name='/capstone/prod/bootstrap/rds-master-password', WithDecryption=True)
        checks['master_access_denied'] = False
    except ssm.exceptions.ClientError as error:
        checks['master_access_denied'] = error.response['Error']['Code'] in ['AccessDeniedException', 'AccessDenied']
    app = ssm.get_parameter(Name='/capstone/prod/be/db-password', WithDecryption=True)['Parameter']['Value']
    env = dict(os.environ, PGHOST=config['dbEndpoint'], PGPORT='5432', PGDATABASE='capstone',
               PGUSER='capstone_app', PGPASSWORD=app, PGSSLMODE='verify-full', PGSSLROOTCERT='/etc/capstone/rds-ca.pem', PGCONNECT_TIMEOUT='10')
    query = "SELECT ssl AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication) FROM pg_stat_ssl JOIN pg_roles ON rolname=current_user WHERE pid=pg_backend_pid();"
    def connect(overrides):
        result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=query, text=True, capture_output=True, env=dict(env, **overrides))
        return result.returncode == 0 and result.stdout.strip() == 't'
    checks['app_db_tls_and_least_privilege'] = connect({})
    checks['wrong_ca_rejected'] = not connect({'PGSSLROOTCERT':'/etc/ssl/certs/ca-bundle.crt'})
    # PGHOSTADDR selects the real server while PGHOST supplies a deliberately wrong TLS hostname.
    import socket
    checks['wrong_hostname_rejected'] = not connect({'PGHOST':'invalid.capstone.example', 'PGHOSTADDR':socket.gethostbyname(config['dbEndpoint'])})
    checks['application_not_deployed'] = not Path('/opt/capstone/current.json').exists() and not Path('/etc/capstone/current.json').exists()
    print(json.dumps(checks, sort_keys=True))
    if not all(checks.values()): sys.exit(1)

if __name__ == '__main__':
    try: main()
    except Exception as error:
        print('Host verification failed: ' + type(error).__name__ + ' (details suppressed)', file=sys.stderr)
        sys.exit(1)
