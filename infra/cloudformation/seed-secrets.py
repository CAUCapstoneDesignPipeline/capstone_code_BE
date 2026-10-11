#!/usr/bin/env python3
"""Generate missing Standard SecureStrings in memory. Never overwrite or print values."""
import argparse
import secrets
import sys
import boto3

parser=argparse.ArgumentParser()
parser.add_argument('--profile',default='capstone-deploy')
args=parser.parse_args()
session=boto3.Session(profile_name=args.profile,region_name='ap-northeast-2')
if session.client('sts').get_caller_identity()['Account']!='378040395204':
    sys.exit('Unexpected account')
ssm=session.client('ssm')
exports=session.client('cloudformation').list_exports()['Exports']
key=next(x['Value'] for x in exports if x['Name']=='capstone-bootstrap-SecretsKeyArn')
existing={x['Name'] for page in ssm.get_paginator('describe_parameters').paginate() for x in page['Parameters']}
for name in ['/capstone/prod/bootstrap/rds-master-password','/capstone/prod/be/db-password','/capstone/prod/be/jwt-secret']:
    if name in existing:
        print(name+': retained existing value')
        continue
    value=secrets.token_urlsafe(48)
    result=ssm.put_parameter(Name=name,Type='SecureString',Tier='Standard',KeyId=key,Value=value,Overwrite=False,
        Tags=[{'Key':'Project','Value':'capstone'},{'Key':'Environment','Value':'prod'}])
    print(name+': created version '+str(result['Version'])+' (value suppressed)')
