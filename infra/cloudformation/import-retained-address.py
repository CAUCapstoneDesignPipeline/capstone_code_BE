#!/usr/bin/env python3
"""Prepare an IMPORT Change Set after the runtime network CREATE completes.

Run only after approved cleanup and network initialization. This script
does not delete a stack, release an address, or execute the proposed import.
"""
import argparse
import json
from pathlib import Path
import boto3

parser=argparse.ArgumentParser()
parser.add_argument('--name',required=True)
args=parser.parse_args()
s=boto3.Session(profile_name='capstone-deploy',region_name='ap-northeast-2')
assert s.client('sts').get_caller_identity()['Account']=='378040395204'
assert s.client('freetier').get_account_plan_state()['accountPlanType']=='FREE'
cf=s.client('cloudformation')
stack=cf.describe_stacks(StackName='capstone-runtime')['Stacks'][0]
assert stack['StackStatus']=='CREATE_COMPLETE'
assert stack['RoleARN']=='arn:aws:iam::378040395204:role/CapstoneCloudFormationExecutionRole'
assert cf.get_template(StackName='capstone-runtime')['TemplateBody']==json.loads((Path(__file__).parent/'runtime-network.json').read_text())
ec2=s.client('ec2')
address=ec2.describe_addresses(AllocationIds=['eipalloc-0db6cef5d6346fa2f'])['Addresses'][0]
assert address['PublicIp']=='15.165.127.143'
assert not address.get('AssociationId')
assert any(t['Key']=='Project' and t['Value']=='capstone' for t in address['Tags'])
result=s.client('cloudformation').create_change_set(StackName='capstone-runtime',ChangeSetName=args.name,ChangeSetType='IMPORT',
    TemplateBody=(Path(__file__).parent/'runtime-address-import.json').read_text(),RoleARN='arn:aws:iam::378040395204:role/CapstoneCloudFormationExecutionRole',
    ResourcesToImport=[{'ResourceType':'AWS::EC2::EIP','LogicalResourceId':'Address','ResourceIdentifier':{'AllocationId':address['AllocationId'],'PublicIp':address['PublicIp']}}],
    Tags=[{'Key':'Project','Value':'capstone'},{'Key':'Environment','Value':'prod'}])
print(json.dumps({'StackId':result['StackId'],'Id':result['Id']}))
