#!/usr/bin/env python3
"""Execute only the separately approved retry of a DB that never existed.

This is not a general replacement override. The failed capstone-prod DB must be
absent in RDS, the approved one-day backup template must match, and no other
resource may be removed or replaced. Restore stack-policy.json at terminal state.
"""
import argparse
import json
from pathlib import Path
import boto3
from botocore.exceptions import ClientError

parser = argparse.ArgumentParser()
parser.add_argument('--name', required=True)
parser.add_argument('--approved-uncreated-database-retry', action='store_true', required=True)
args = parser.parse_args()
session = boto3.Session(profile_name='capstone-deploy', region_name='ap-northeast-2')
assert session.client('sts').get_caller_identity()['Account'] == '378040395204'
assert session.client('freetier').get_account_plan_state()['accountPlanType'] == 'FREE'
cf = session.client('cloudformation')
assert cf.describe_stacks(StackName='capstone-runtime')['Stacks'][0]['StackStatus'] == 'UPDATE_FAILED'
resources = cf.list_stack_resources(StackName='capstone-runtime')['StackResourceSummaries']
failed = next(r for r in resources if r['LogicalResourceId'] == 'Database')
assert failed['ResourceStatus'] == 'CREATE_FAILED' and failed['PhysicalResourceId'] == 'capstone-prod'
try:
    session.client('rds').describe_db_instances(DBInstanceIdentifier='capstone-prod')
except ClientError as error:
    assert error.response['Error']['Code'] == 'DBInstanceNotFound'
else:
    raise AssertionError('A real database exists; replacement requires a different review.')
change = cf.describe_change_set(StackName='capstone-runtime', ChangeSetName=args.name)
assert change['Status'] == 'CREATE_COMPLETE' and change['ExecutionStatus'] == 'AVAILABLE'
expected = json.loads((Path(__file__).parent / 'runtime.json').read_text())
actual = cf.get_template(StackName='capstone-runtime', ChangeSetName=args.name)['TemplateBody']
assert actual == expected and actual['Resources']['Database']['Properties']['BackupRetentionPeriod'] == 1
for item in change['Changes']:
    resource = item['ResourceChange']
    assert resource['Action'] != 'Remove'
    if resource.get('Replacement') in ['True', 'Conditional']:
        assert resource['LogicalResourceId'] == 'Database' and resource['Replacement'] == 'True'
baseline = json.loads((Path(__file__).parent / 'stack-policy.json').read_text())
assert json.loads(cf.get_stack_policy(StackName='capstone-runtime')['StackPolicyBody']) == baseline
temporary = json.loads(json.dumps(baseline))
temporary['Statement'][1]['Condition']['StringEquals']['ResourceType'].remove('AWS::RDS::DBInstance')
cf.set_stack_policy(StackName='capstone-runtime', StackPolicyBody=json.dumps(temporary))
try:
    cf.execute_change_set(StackName='capstone-runtime', ChangeSetName=args.name, DisableRollback=True)
except Exception:
    cf.set_stack_policy(StackName='capstone-runtime', StackPolicyBody=json.dumps(baseline))
    raise
print('Approved retry requested for absent capstone-prod only; restore stack-policy.json at terminal state.')
