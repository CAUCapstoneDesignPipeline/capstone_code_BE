#!/usr/bin/env python3
"""CloudFormation review/execution helper. No deletes/replacements without explicit review."""
import argparse
import json
from pathlib import Path
import boto3

parser=argparse.ArgumentParser()
parser.add_argument('action',choices=['create','inspect','execute','status'])
parser.add_argument('stack',choices=['bootstrap','runtime'])
parser.add_argument('--name',required=True)
parser.add_argument('--update',action='store_true')
parser.add_argument('--network-only',action='store_true',help='Create the runtime network before importing the retained EIP')
parser.add_argument('--db-bootstrap',choices=['true','false'],default='false')
args=parser.parse_args()
assert not args.network_only or (args.stack=='runtime' and args.action=='create' and not args.update)
s=boto3.Session(profile_name='capstone-deploy',region_name='ap-northeast-2')
assert s.client('sts').get_caller_identity()['Account']=='378040395204'
assert s.client('freetier').get_account_plan_state()['accountPlanType']=='FREE'
cf=s.client('cloudformation');stack='capstone-'+args.stack
if args.action=='create':
    params=[]
    if args.stack=='bootstrap':
        params=[{'ParameterKey':'EnableDbBootstrap','ParameterValue':args.db_bootstrap}]
        if args.update:
            params.append({'ParameterKey':'NotificationEmail','UsePreviousValue':True})
        else:
            email=s.client('budgets').describe_subscribers_for_notification(AccountId='378040395204',BudgetName='capstone-monthly',Notification={'NotificationType':'ACTUAL','ComparisonOperator':'GREATER_THAN','Threshold':80})['Subscribers'][0]['Address']
            params.append({'ParameterKey':'NotificationEmail','ParameterValue':email})
    if args.stack=='runtime' and not args.network_only:
        version=next(p['Version'] for page in s.client('ssm').get_paginator('describe_parameters').paginate() for p in page['Parameters'] if p['Name']=='/capstone/prod/bootstrap/rds-master-password')
        params=[{'ParameterKey':'MasterPasswordVersion','ParameterValue':str(version)}]
    result=cf.create_change_set(StackName=stack,ChangeSetName=args.name,ChangeSetType='UPDATE' if args.update else 'CREATE',
        TemplateBody=(Path(__file__).parent/('runtime-network.json' if args.network_only else args.stack+'.json')).read_text(),RoleARN='arn:aws:iam::378040395204:role/CapstoneCloudFormationExecutionRole',
        Capabilities=['CAPABILITY_NAMED_IAM'],Parameters=params,Tags=[{'Key':'Project','Value':'capstone'},{'Key':'Environment','Value':'prod'}])
    print(json.dumps({'StackId':result['StackId'],'Id':result['Id']}))
elif args.action in ['inspect','execute']:
    change=cf.describe_change_set(StackName=stack,ChangeSetName=args.name)
    summary={'Status':change['Status'],'ExecutionStatus':change['ExecutionStatus'],'Reason':change.get('StatusReason'),
        'Changes':[{'Action':x['ResourceChange']['Action'],'LogicalId':x['ResourceChange']['LogicalResourceId'],'Type':x['ResourceChange']['ResourceType'],'Replacement':x['ResourceChange'].get('Replacement')} for x in change.get('Changes',[])]}
    print(json.dumps(summary,indent=2))
    if args.action=='execute':
        assert change['Status']=='CREATE_COMPLETE' and change['ExecutionStatus']=='AVAILABLE'
        previous=cf.get_template(StackName=stack)['TemplateBody'] if any(x['Replacement']=='Conditional' for x in summary['Changes']) else None
        proposed=cf.get_template(StackName=stack,ChangeSetName=args.name)['TemplateBody'] if previous else None
        for item in summary['Changes']:
            # The temporary master-read policy is the sole removal authorized by P6.
            assert item['Action']!='Remove' or item['LogicalId']=='HostBootstrapPolicy'
            if item['Replacement']=='Conditional' and item['Type']=='AWS::SSM::Document' and item['Action']=='Modify':
                before=previous['Resources'][item['LogicalId']]['Properties']
                after=proposed['Resources'][item['LogicalId']]['Properties']
                assert before['UpdateMethod']==after['UpdateMethod']=='NewVersion'
                assert {k:v for k,v in before.items() if k!='Content'}=={k:v for k,v in after.items() if k!='Content'}
                assert isinstance(after['Name'],str) and after['Name'].startswith('capstone-')
                # AWS documents that NewVersion keeps the named document and
                # changes its default version. Only Content is permitted here.
            else:
                assert item['Replacement'] not in ['True','Conditional']
        if any(item['Action']=='Import' for item in summary['Changes']):
            cf.execute_change_set(StackName=stack,ChangeSetName=args.name)
        else:
            cf.execute_change_set(StackName=stack,ChangeSetName=args.name,DisableRollback=True)
        print('Execution requested; inspect stack status until terminal state.')
else:
    data=cf.describe_stacks(StackName=stack)['Stacks'][0]
    print(json.dumps({'Status':data['StackStatus'],'TerminationProtection':data.get('EnableTerminationProtection'),'Outputs':data.get('Outputs',[])},indent=2))
