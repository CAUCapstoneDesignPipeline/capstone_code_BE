#!/opt/capstone/tools/bin/python
"""Fixed operations and secret-free status records in an existing log group.

Native Run Command export requires DescribeLogGroups/CreateLogGroup, which the
existing workload boundary does not allow. Use only CreateLogStream/PutLogEvents;
do not relax the boundary or publish raw command output to CloudWatch.
"""
import argparse
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path
import boto3

parser = argparse.ArgumentParser()
parser.add_argument('operation', choices=['install', 'bootstrap-db', 'verify', 'tls', 'deploy', 'prepare-pipeline', 'configure-release'])
args = parser.parse_args()
commands = {
    'prepare-pipeline': ['/opt/capstone/tools/bin/python', '/opt/capstone/host/prepare-pipeline.py'],
    'configure-release': ['/opt/capstone/tools/bin/python', '/opt/capstone/host/configure-release.py'],
    'bootstrap-db': ['/opt/capstone/tools/bin/python', '/opt/capstone/host/bootstrap-db.py'],
    'verify': ['/opt/capstone/tools/bin/python', '/opt/capstone/host/verify.py'],
    'tls': ['/bin/bash', '/opt/capstone/host/tls.sh'],
    'deploy': ['/bin/bash', '/opt/capstone/host/deploy.sh'],
}
record = {'operation': args.operation}
if args.operation == 'install':
    code = 0 if Path('/etc/capstone/bootstrap-complete').exists() else 1
    print('Fixed host installation status recorded.')
else:
    result = subprocess.run(commands[args.operation], capture_output=True, text=True)
    code = result.returncode
    # These fixed scripts suppress credentials and SQL errors in their own output.
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if args.operation in ['verify', 'prepare-pipeline'] and code == 0:
        checks = json.loads(result.stdout)
        assert all(isinstance(value, bool) for value in checks.values())
        record['checks'] = checks
record['exitCode'] = code
try:
    logs = boto3.client('logs', region_name='ap-northeast-2')
    stream = 'capstone-prod/' + args.operation + '/' + str(uuid.uuid4())
    logs.create_log_stream(logGroupName='/capstone/prod/ssm', logStreamName=stream)
    logs.put_log_events(logGroupName='/capstone/prod/ssm', logStreamName=stream,
                        logEvents=[{'timestamp': int(time.time()*1000), 'message': json.dumps(record, sort_keys=True)}])
except Exception as error:
    print('Operation status logging failed: ' + type(error).__name__ + ' (details suppressed)', file=sys.stderr)
    sys.exit(code or 1)
sys.exit(code)
