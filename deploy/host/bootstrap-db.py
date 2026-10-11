#!/opt/capstone/tools/bin/python
"""One-time, fixed DB bootstrap. Never emit credentials or SQL errors."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path
import boto3

def main():
    config = json.loads(Path('/etc/capstone/infrastructure.json').read_text())
    ssm = boto3.client('ssm', region_name='ap-northeast-2')
    def secret(name):
        return ssm.get_parameter(Name=name, WithDecryption=True)['Parameter']['Value']
    master = secret('/capstone/prod/bootstrap/rds-master-password')
    app = secret('/capstone/prod/be/db-password')
    if not re.fullmatch(r'[A-Za-z0-9_-]{48,128}', app):
        raise ValueError('unexpected password format')
    env = dict(os.environ, PGHOST=config['dbEndpoint'], PGPORT='5432', PGDATABASE='capstone',
               PGUSER='capstone_admin', PGPASSWORD=master, PGSSLMODE='verify-full',
               PGSSLROOTCERT='/etc/capstone/rds-ca.pem', PGCONNECT_TIMEOUT='15')
    def sql(query, values=False):
        result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                                input=query, text=True, capture_output=True, env=env)
        if result.returncode:
            raise RuntimeError('DB operation failed (output suppressed)')
        return result.stdout.strip() if values else None
    exists = sql("SELECT 1 FROM pg_roles WHERE rolname='capstone_app';", True)
    if not exists:
        sql("CREATE ROLE capstone_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + app + "';")
        sql('ALTER DATABASE capstone OWNER TO capstone_app;')
    env.update(PGUSER='capstone_app', PGPASSWORD=app)
    result = sql("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid();", True)
    if result != 't':
        raise RuntimeError('TLS verification failed')
    sql("SELECT 1 WHERE NOT (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication FROM pg_roles WHERE rolname=current_user);", True)
    Path('/etc/capstone/db-bootstrap-complete').touch(mode=0o600)
    print('DB bootstrap complete: application role connected using verify-full TLS.')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('DB bootstrap failed: ' + type(error).__name__ + ' (details suppressed)', file=sys.stderr)
        sys.exit(1)
