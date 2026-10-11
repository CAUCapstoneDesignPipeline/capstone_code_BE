#!/usr/bin/env python3
"""Read-only verification of actual P6 resources; never read secret values."""
import json
import sys
from pathlib import Path
import boto3

session = boto3.Session(profile_name='capstone-deploy', region_name='ap-northeast-2')
assert session.client('sts').get_caller_identity()['Account'] == '378040395204'
cf = session.client('cloudformation')
ec2 = session.client('ec2')
iam = session.client('iam')
checks = {'free_plan': session.client('freetier').get_account_plan_state()['accountPlanType'] == 'FREE'}
stacks = {n: cf.describe_stacks(StackName='capstone-'+n)['Stacks'][0] for n in ['bootstrap', 'runtime']}
checks['stacks_complete_and_protected'] = all(x['StackStatus'] in ['CREATE_COMPLETE', 'UPDATE_COMPLETE'] and x['EnableTerminationProtection'] for x in stacks.values())
checks['runtime_stateful_policy'] = json.loads(cf.get_stack_policy(StackName='capstone-runtime')['StackPolicyBody']) == json.loads((Path(__file__).parent/'stack-policy.json').read_text())
resources = {x['LogicalResourceId']: x['PhysicalResourceId'] for x in cf.list_stack_resources(StackName='capstone-runtime')['StackResourceSummaries']}
groups = {g['GroupId']: g for g in ec2.describe_security_groups(GroupIds=[resources['AppSecurityGroup'], resources['DbSecurityGroup']])['SecurityGroups']}
app, db = groups[resources['AppSecurityGroup']], groups[resources['DbSecurityGroup']]
checks['public_only_80_443'] = sorted((x['FromPort'], x['ToPort']) for x in app['IpPermissions']) == [(80,80), (443,443)] and all(x['IpProtocol']=='tcp' and x['IpRanges']==[{'CidrIp':'0.0.0.0/0'}] and not x.get('Ipv6Ranges') for x in app['IpPermissions'])
checks['db_only_app_5432'] = len(db['IpPermissions'])==1 and db['IpPermissions'][0]['IpProtocol']=='tcp' and db['IpPermissions'][0]['FromPort']==5432 and db['IpPermissions'][0]['ToPort']==5432 and len(db['IpPermissions'][0]['UserIdGroupPairs'])==1 and db['IpPermissions'][0]['UserIdGroupPairs'][0]['GroupId']==app['GroupId'] and not db['IpPermissions'][0]['IpRanges'] and not db['IpPermissions'][0].get('Ipv6Ranges')
subnets = ec2.describe_subnets(SubnetIds=[resources[n] for n in ['AppSubnet','DbSubnetA','DbSubnetC']])['Subnets']
checks['db_two_azs_and_no_auto_public_ip'] = len({x['AvailabilityZone'] for x in subnets if x['SubnetId']!=resources['AppSubnet']})==2 and all(not x['MapPublicIpOnLaunch'] for x in subnets)
tables = {t['RouteTableId']: t for t in ec2.describe_route_tables(Filters=[{'Name':'vpc-id','Values':[resources['Vpc']]}])['RouteTables']}
private, public = tables[resources['PrivateRoutes']], tables[resources['PublicRoutes']]
checks['private_routes_only_local'] = all(x.get('GatewayId')=='local' and x.get('DestinationCidrBlock')=='10.42.0.0/16' for x in private['Routes']) and {x['SubnetId'] for x in private['Associations'] if 'SubnetId' in x}=={resources['DbSubnetA'], resources['DbSubnetC']}
checks['public_route_igw'] = any(x.get('DestinationCidrBlock')=='0.0.0.0/0' and x.get('GatewayId')==resources['InternetGateway'] for x in public['Routes']) and {x['SubnetId'] for x in public['Associations'] if 'SubnetId' in x}=={resources['AppSubnet']}
host = ec2.describe_instances(InstanceIds=[resources['Host']])['Reservations'][0]['Instances'][0]
checks['host_running_amd64_fixed_ami'] = host['State']['Name']=='running' and host['Architecture']=='x86_64' and host['InstanceType']=='t3.small' and host['ImageId']=='ami-0870825cefcaafcc8'
checks['host_imdsv2_hop1'] = host['MetadataOptions']['HttpTokens']=='required' and host['MetadataOptions']['HttpPutResponseHopLimit']==1
checks['host_standard_cpu'] = ec2.describe_instance_credit_specifications(InstanceIds=[host['InstanceId']])['InstanceCreditSpecifications'][0]['CpuCredits']=='standard'
volumes = ec2.describe_volumes(VolumeIds=[x['Ebs']['VolumeId'] for x in host['BlockDeviceMappings']])['Volumes']
checks['ebs_encrypted_30_gp3_retained'] = len(volumes)==1 and volumes[0]['Encrypted'] and volumes[0]['VolumeType']=='gp3' and volumes[0]['Size']==30 and not host['BlockDeviceMappings'][0]['Ebs']['DeleteOnTermination']
address = ec2.describe_addresses(AllocationIds=['eipalloc-0db6cef5d6346fa2f'])['Addresses'][0]
checks['original_eip_attached'] = address['PublicIp']=='15.165.127.143' and address.get('InstanceId')==host['InstanceId']
rds = session.client('rds').describe_db_instances(DBInstanceIdentifier='capstone-prod')['DBInstances'][0]
checks['rds_private_encrypted_available'] = rds['DBInstanceStatus']=='available' and not rds['PubliclyAccessible'] and rds['StorageEncrypted'] and not rds['MultiAZ'] and rds['DeletionProtection'] and rds['BackupRetentionPeriod']==1 and rds['Engine']=='postgres' and rds['EngineVersion']=='16.15' and rds['DBInstanceClass']=='db.t3.micro' and rds['AllocatedStorage']==20 and rds['StorageType']=='gp3'
checks['rds_project_private_subnets'] = {x['SubnetIdentifier'] for x in rds['DBSubnetGroup']['Subnets']}=={resources['DbSubnetA'],resources['DbSubnetC']} and {x['VpcSecurityGroupId'] for x in rds['VpcSecurityGroups']}=={db['GroupId']}
parameters = [x for page in session.client('rds').get_paginator('describe_db_parameters').paginate(DBParameterGroupName='capstone-postgres16') for x in page['Parameters']]
checks['rds_ssl_required'] = any(x['ParameterName']=='rds.force_ssl' and x.get('ParameterValue')=='1' for x in parameters) and all(x['ParameterApplyStatus']=='in-sync' for x in rds['DBParameterGroups'])
node = session.client('ssm').describe_instance_information(Filters=[{'Key':'InstanceIds','Values':[host['InstanceId']]}])['InstanceInformationList']
checks['ssm_online'] = len(node)==1 and node[0]['PingStatus']=='Online'
boundary = 'arn:aws:iam::378040395204:policy/CapstoneWorkloadBoundary'
checks['all_workload_boundaries'] = all(iam.get_role(RoleName=n)['Role']['PermissionsBoundary']['PermissionsBoundaryArn']==boundary for n in ['capstone-prod-host','capstone-be-publisher','capstone-web-publisher','capstone-prod-deploy'])
inline = iam.get_role_policy(RoleName='capstone-prod-host',PolicyName='capstone-runtime')['PolicyDocument']
checks['master_policy_revoked_and_denied'] = iam.list_role_policies(RoleName='capstone-prod-host')['PolicyNames']==['capstone-runtime'] and any(x['Effect']=='Deny' and 'rds-master-password' in str(x['Resource']) for x in inline['Statement'])
logs = session.client('logs').describe_log_groups(logGroupNamePrefix='/capstone/prod/')['logGroups']
checks['logs_three_14days'] = {x['logGroupName'] for x in logs}=={'/capstone/prod/host','/capstone/prod/be','/capstone/prod/ssm'} and all(x.get('retentionInDays')==14 for x in logs)
alarms = session.client('cloudwatch').describe_alarms(AlarmNamePrefix='capstone-')['MetricAlarms']
checks['eight_alarms_sns'] = len(alarms)==8 and all(x['AlarmActions']==['arn:aws:sns:ap-northeast-2:378040395204:capstone-operations'] for x in alarms)
repositories = session.client('ecr').describe_repositories(repositoryNames=['capstone/be','capstone/web'])['repositories']
checks['ecr_two_immutable_encrypted'] = len(repositories)==2 and all(x['imageTagMutability']=='IMMUTABLE' and x['encryptionConfiguration']['encryptionType']=='AES256' for x in repositories)
key = session.client('kms').describe_key(KeyId='alias/capstone/prod')['KeyMetadata']
checks['regional_kms_enabled_rotating'] = key['KeyState']=='Enabled' and not key['MultiRegion'] and session.client('kms').get_key_rotation_status(KeyId=key['KeyId'])['KeyRotationEnabled']
names = ['/capstone/prod/bootstrap/rds-master-password','/capstone/prod/be/db-password','/capstone/prod/be/jwt-secret']
secrets = [x for page in session.client('ssm').get_paginator('describe_parameters').paginate() for x in page['Parameters'] if x['Name'] in names]
checks['secrets_standard_securestring_cmk'] = len(secrets)==3 and all(x['Type']=='SecureString' and x['Tier']=='Standard' and x['KeyId']==key['Arn'] for x in secrets)
print(json.dumps({'checks':checks, 'resources':{k:resources[k] for k in ['Vpc','Host','Address','Database']}, 'alarms':{x['AlarmName']:x['StateValue'] for x in alarms}}, indent=2))
sys.exit(0 if all(checks.values()) else 1)
