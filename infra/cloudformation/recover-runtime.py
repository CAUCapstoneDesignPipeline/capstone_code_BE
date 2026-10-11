#!/usr/bin/env python3
"""Approved, exact failed-stack metadata cleanup; keep the retained EIP.

This deliberately requires an explicit flag and the actual minimal parent-VPC
policy to have been applied by the existing IAM administrator. It grants no IAM
permissions itself and refuses to remove any live runtime resource.
"""
import argparse
import json
from pathlib import Path
import boto3

parser=argparse.ArgumentParser()
parser.add_argument('--approved-failed-stack-cleanup',action='store_true',required=True)
args=parser.parse_args()
s=boto3.Session(profile_name='capstone-deploy',region_name='ap-northeast-2')
identity=s.client('sts').get_caller_identity()
assert identity['Account']=='378040395204' and identity['Arn']=='arn:aws:iam::378040395204:user/capstone-operator'
assert s.client('freetier').get_account_plan_state()['accountPlanType']=='FREE'
iam=s.client('iam');arn='arn:aws:iam::378040395204:policy/CapstoneCloudFormationCompute'
version=iam.get_policy(PolicyArn=arn)['Policy']['DefaultVersionId']
actual=iam.get_policy_version(PolicyArn=arn,VersionId=version)['PolicyVersion']['Document']
base=Path(__file__).resolve().parents[3]/'aws-permission-handoff'
expected=json.loads((base/'proposed-2026-10-08'/'CapstoneCloudFormationCompute.json').read_text())
assert actual==expected, 'The approved minimum IAM change has not been applied exactly; stop before mutation.'
cf=s.client('cloudformation')
stack='arn:aws:cloudformation:ap-northeast-2:378040395204:stack/capstone-runtime/bd9f68f0-c310-11f1-845f-06a61d9cacd9'
data=cf.describe_stacks(StackName=stack)['Stacks'][0]
assert data['StackStatus']=='ROLLBACK_COMPLETE'
resources=cf.list_stack_resources(StackName=stack)['StackResourceSummaries']
for r in resources:
    assert r['ResourceStatus']=='DELETE_COMPLETE' or (r['LogicalResourceId']=='Address' and r['ResourceStatus']=='DELETE_SKIPPED' and r['PhysicalResourceId']=='15.165.127.143')
ec2=s.client('ec2');addr=ec2.describe_addresses(AllocationIds=['eipalloc-0db6cef5d6346fa2f'])['Addresses'][0]
assert addr['PublicIp']=='15.165.127.143' and not addr.get('AssociationId')
assert not ec2.describe_instances(Filters=[{'Name':'tag:Project','Values':['capstone']}])['Reservations']
assert not s.client('rds').describe_db_instances()['DBInstances']
decision=iam.simulate_principal_policy(PolicySourceArn=identity['Arn'],ActionNames=['cloudformation:DeleteStack'],ResourceArns=[stack],
    ContextEntries=[{'ContextKeyName':'aws:RequestedRegion','ContextKeyValues':['ap-northeast-2'],'ContextKeyType':'string'},
                    {'ContextKeyName':'aws:CurrentTime','ContextKeyValues':[__import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat()],'ContextKeyType':'date'}])['EvaluationResults'][0]['EvalDecision']
assert decision=='allowed', 'Exact failed-stack cleanup permission is not present.'
cf.update_termination_protection(StackName=stack,EnableTerminationProtection=False)
cf.delete_stack(StackName=stack,RoleARN='arn:aws:iam::378040395204:role/CapstoneCloudFormationExecutionRole')
print('Deletion requested for the approved failed stack metadata only; retained EIP preserved. Wait for DELETE_COMPLETE before import.')
