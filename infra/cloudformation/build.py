#!/usr/bin/env python3
"""Generate reviewable templates and embed only tracked, non-secret host files."""
import base64
import gzip
import hashlib
import io
import json
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOST = HERE.parents[1] / 'deploy' / 'host'
ACCOUNT = '378040395204'
REGION = 'ap-northeast-2'
IAM = f'arn:aws:iam::{ACCOUNT}'
ARN = f'arn:aws:{{service}}:{REGION}:{ACCOUNT}:{{resource}}'
TAGS = [{'Key':'Project','Value':'capstone'}, {'Key':'Environment','Value':'prod'}]
BOUNDARY = IAM + ':policy/CapstoneWorkloadBoundary'
def ref(x): return {'Ref':x}
def get(x,y): return {'Fn::GetAtt':[x,y]}
def sub(x): return {'Fn::Sub':x}
def imported(x): return {'Fn::ImportValue':'capstone-bootstrap-'+x}
def statement(actions,resources,condition=None):
    s={'Effect':'Allow','Action':actions,'Resource':resources}
    if condition:s['Condition']=condition
    return s
def policy(stmts): return {'Version':'2012-10-17','Statement':stmts}
def role(name,trust,stmts):
    return {'Type':'AWS::IAM::Role','Properties':{'RoleName':name,'Path':'/capstone/',
        'PermissionsBoundary':BOUNDARY,'AssumeRolePolicyDocument':trust,'Tags':TAGS,
        'Policies':[{'PolicyName':'capstone-runtime','PolicyDocument':policy(stmts)}]}}
def resource(t,props,**extra):return {'Type':t,'Properties':props,**extra}
def document(name,command,parameters=None):
    content={'schemaVersion':'2.2','description':'Fixed CAPSTONE root-owned host operation',
        'mainSteps':[{'action':'aws:runShellScript','name':'run','inputs':{'timeoutSeconds':'900','runCommand':[command]}}]}
    if parameters:content['parameters']=parameters
    return resource('AWS::SSM::Document',{'Name':name,'DocumentType':'Command','DocumentFormat':'JSON',
        'UpdateMethod':'NewVersion','Content':content,'Tags':TAGS})

def bootstrap():
    repositories=[f'arn:aws:ecr:{REGION}:{ACCOUNT}:repository/capstone/{x}' for x in ['be','web']]
    host_read=[statement(['ssm:GetParameter','ssm:GetParameters'],f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/be/*'),
        statement('kms:Decrypt',get('SecretsKey','Arn'),{'StringEquals':{'kms:ViaService':f'ssm.{REGION}.amazonaws.com'},'StringLike':{'kms:EncryptionContext:PARAMETER_ARN':f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/be/*'}}),
        dict(statement('ssm:PutParameter',[f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/deploy/{n}' for n in ['current','previous']],{'StringEquals':{'aws:RequestedRegion':REGION}}),Sid='PublishNonSecretReleaseMetadata'),
        statement('ecr:GetAuthorizationToken','*'),statement(['ecr:BatchGetImage','ecr:GetDownloadUrlForLayer','ecr:BatchCheckLayerAvailability'],repositories),
        statement(['logs:CreateLogStream','logs:PutLogEvents','logs:DescribeLogStreams'],f'arn:aws:logs:{REGION}:{ACCOUNT}:log-group:/capstone/*'),
        statement('cloudwatch:PutMetricData','*',{'StringEquals':{'cloudwatch:namespace':'capstone'}})]
    r={}
    r['SecretsKey']=resource('AWS::KMS::Key',{'Description':'CAPSTONE regional Parameter Store secrets','EnableKeyRotation':True,'MultiRegion':False,'Tags':TAGS,
        'KeyPolicy':policy([dict(statement('kms:*','*'),Principal={'AWS':IAM+':root'})])},DeletionPolicy='Retain',UpdateReplacePolicy='Retain')
    r['SecretsAlias']=resource('AWS::KMS::Alias',{'AliasName':'alias/capstone/prod','TargetKeyId':ref('SecretsKey')})
    for x in ['be','web']:
        r[x.title()+'Repository']=resource('AWS::ECR::Repository',{'RepositoryName':'capstone/'+x,'ImageTagMutability':'IMMUTABLE',
            'EncryptionConfiguration':{'EncryptionType':'AES256'},'ImageScanningConfiguration':{'ScanOnPush':True},'Tags':TAGS},DeletionPolicy='Retain',UpdateReplacePolicy='Retain')
    for x in ['host','be','ssm']:
        r[x.title()+'Logs']=resource('AWS::Logs::LogGroup',{'LogGroupName':'/capstone/prod/'+x,'RetentionInDays':14,'Tags':TAGS},DeletionPolicy='Retain',UpdateReplacePolicy='Retain')
    trust=policy([{'Effect':'Allow','Principal':{'Service':'ec2.amazonaws.com'},'Action':'sts:AssumeRole'}])
    r['HostRole']=role('capstone-prod-host',trust,host_read)
    # AmazonSSMManagedInstanceCore includes GetParameter(s) on *. Keep an explicit
    # master-path deny outside the short bootstrap window; the boundary is unchanged.
    r['HostRole']['Properties']['Policies'][0]['PolicyDocument']['Statement'].append({'Fn::If':['AllowDbBootstrap',ref('AWS::NoValue'),
        {'Effect':'Deny','Action':['ssm:GetParameter','ssm:GetParameters'],'Resource':f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/bootstrap/rds-master-password'}]})
    r['HostRole']['Properties']['ManagedPolicyArns']=['arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore']
    r['HostBootstrapPolicy']=resource('AWS::IAM::Policy',{'PolicyName':'capstone-temporary-db-bootstrap','Roles':[ref('HostRole')],
        'PolicyDocument':policy([statement('ssm:GetParameter',f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/bootstrap/rds-master-password'),
            statement('kms:Decrypt',get('SecretsKey','Arn'),{'StringEquals':{'kms:ViaService':f'ssm.{REGION}.amazonaws.com','kms:EncryptionContext:PARAMETER_ARN':f'arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/capstone/prod/bootstrap/rds-master-password'}})])},Condition='AllowDbBootstrap')
    r['HostProfile']=resource('AWS::IAM::InstanceProfile',{'InstanceProfileName':'capstone-prod-host','Path':'/capstone/','Roles':[ref('HostRole')]})
    r['GitHubProvider']=resource('AWS::IAM::OIDCProvider',{'Url':'https://token.actions.githubusercontent.com','ClientIdList':['sts.amazonaws.com'],'Tags':TAGS})
    prefix='repo:CAUCapstoneDesignPipeline@324880339/'
    subjects={'BePublisher':prefix+'capstone_code_BE@1397266620:environment:image-publish',
              'WebPublisher':prefix+'capstone_code_FE@1397258129:environment:image-publish',
              'DeployRole':prefix+'capstone_code_BE@1397266620:environment:production'}
    for n,subject in subjects.items():
        oidc=policy([{'Effect':'Allow','Principal':{'Federated':ref('GitHubProvider')},'Action':'sts:AssumeRoleWithWebIdentity',
            'Condition':{'StringEquals':{'token.actions.githubusercontent.com:aud':'sts.amazonaws.com','token.actions.githubusercontent.com:sub':subject}}}])
        if n!='DeployRole':
            repo=repositories[0 if n=='BePublisher' else 1]
            stmts=[statement('ecr:GetAuthorizationToken','*'),statement(['ecr:BatchCheckLayerAvailability','ecr:InitiateLayerUpload','ecr:UploadLayerPart','ecr:CompleteLayerUpload','ecr:PutImage','ecr:DescribeImages'],repo)]
        else:
            stmts=[statement('ssm:SendCommand',f'arn:aws:ssm:{REGION}:{ACCOUNT}:document/capstone-deploy'),
                statement('ssm:SendCommand',f'arn:aws:ec2:{REGION}:{ACCOUNT}:instance/*',{'StringEquals':{'ssm:resourceTag/Project':'capstone'}}),
                statement(['ssm:GetCommandInvocation','ssm:ListCommandInvocations','ssm:ListCommands'],'*'),
                statement(['ecr:DescribeImages','ecr:BatchGetImage'],repositories)]
        r[n]=role('capstone-'+{'BePublisher':'be-publisher','WebPublisher':'web-publisher','DeployRole':'prod-deploy'}[n],oidc,stmts)
    r['OperationsTopic']=resource('AWS::SNS::Topic',{'TopicName':'capstone-operations','Tags':TAGS})
    r['OperationsTopicPolicy']=resource('AWS::SNS::TopicPolicy',{'Topics':[ref('OperationsTopic')],
        'PolicyDocument':policy([{'Effect':'Allow','Principal':{'Service':'cloudwatch.amazonaws.com'},'Action':'sns:Publish','Resource':ref('OperationsTopic'),
            'Condition':{'StringEquals':{'aws:SourceAccount':ACCOUNT},'ArnLike':{'aws:SourceArn':f'arn:aws:cloudwatch:{REGION}:{ACCOUNT}:alarm:capstone-*'}}}])})
    r['OperationsEmail']=resource('AWS::SNS::Subscription',{'Protocol':'email','Endpoint':ref('NotificationEmail'),'TopicArn':ref('OperationsTopic')},Condition='HasNotificationEmail')
    r['DeployDocument']=document('capstone-deploy','/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py deploy',{
        'ReleaseId':{'type':'String','allowedPattern':'^[a-z0-9][a-z0-9-]{0,63}$','interpolationType':'ENV_VAR'},
        'BeDigest':{'type':'String','allowedPattern':'^sha256:[a-f0-9]{64}$','interpolationType':'ENV_VAR'},
        'WebDigest':{'type':'String','allowedPattern':'^sha256:[a-f0-9]{64}$','interpolationType':'ENV_VAR'},
        'ManifestSha':{'type':'String','allowedPattern':'^[a-f0-9]{64}$','interpolationType':'ENV_VAR'},
        'ExpectedRelease':{'type':'String','allowedPattern':'^(none|[a-z0-9][a-z0-9-]{0,63})$','interpolationType':'ENV_VAR'},
        'ManifestJson':{'type':'String','allowedPattern':r'^\{[^\r\n]+\}$','minChars':3,'maxChars':6002,'interpolationType':'ENV_VAR'},
        'Operation':{'type':'String','allowedValues':['deploy','rollback'],'interpolationType':'ENV_VAR'}})
    r['DbBootstrapDocument']=document('capstone-bootstrap-db','/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py bootstrap-db')
    r['VerifyDocument']=document('capstone-verify-infra','/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py verify')
    r['TlsDocument']=document('capstone-tls','/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py tls')
    # Inline a fixed verifier so inspection never reinstalls host files or reads secrets.
    verifier=base64.b64encode(gzip.compress((HERE/'verify-tls-host.py').read_bytes(),mtime=0)).decode()
    r['TlsVerifyDocument']=document('capstone-verify-tls',"/opt/capstone/tools/bin/python - <<'CAPSTONE_TLS_VERIFY'\nimport base64,gzip\nexec(compile(gzip.decompress(base64.b64decode('"+verifier+"')).decode(),'capstone-verify-tls','exec'))\nCAPSTONE_TLS_VERIFY")
    # Install only this repository's fixed files on a new project host. No user
    # arguments or generic shell access; also supports a failed initial install.
    install_command="set -eu\numask 077\ntest ! -f /etc/capstone/current.json\ntest ! -f /etc/capstone/release-pending.json\npython3 - <<'PY'\nimport base64,gzip,io,tarfile\ndata=base64.b64decode('"+payload()+"')\nwith tarfile.open(fileobj=io.BytesIO(gzip.decompress(data))) as archive: archive.extractall('/opt/capstone')\nPY\nflock -n /run/capstone-install.lock /bin/bash /opt/capstone/host/install.sh\n/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py install"
    # Store the reviewed archive once, shared by initial installation and pipeline preparation.
    before,after=install_command.split(payload())
    archive={'Fn::FindInMap':['HostAssets','Content','Archive']}
    r['InstallHostDocument']=document('capstone-install-host',{'Fn::Join':['',[before,archive,after]]})
    prepare_before=before.replace('set -eu\numask 077\n','set -eu\numask 077\ntest ! -f /etc/capstone/current.json\ntest ! -f /etc/capstone/release-pending.json\n')
    prepare_after=after.split('flock -n /run/capstone-install.lock')[0]+'/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py prepare-pipeline'
    r['PreparePipelineDocument']=document('capstone-prepare-pipeline',{'Fn::Join':['',[prepare_before,archive,prepare_after]]})
    r['ConfigureReleaseDocument']=document('capstone-configure-release','/opt/capstone/tools/bin/python /opt/capstone/host/ssm-operation.py configure-release',{
        'ConfigurationOperation':{'type':'String','allowedValues':['initial-config','backup-evidence'],'interpolationType':'ENV_VAR'},
        'ConfigurationJson':{'type':'String','allowedPattern':r'^\{[^\r\n]+\}$','minChars':3,'maxChars':3502,'interpolationType':'ENV_VAR'}})
    outputs={n:{'Value':v,'Export':{'Name':'capstone-bootstrap-'+n}} for n,v in {
        'SecretsKeyArn':get('SecretsKey','Arn'),'HostProfile':ref('HostProfile'),'TopicArn':ref('OperationsTopic'),
        'BeRepository':get('BeRepository','RepositoryUri'),'WebRepository':get('WebRepository','RepositoryUri'),
        'BePublisherArn':get('BePublisher','Arn'),'WebPublisherArn':get('WebPublisher','Arn'),'DeployRoleArn':get('DeployRole','Arn')}.items()}
    return {'AWSTemplateFormatVersion':'2010-09-09','Description':'CAPSTONE P6 bootstrap: scoped identities, secrets key, ECR, logs, fixed SSM documents',
        'Mappings':{'HostAssets':{'Content':{'Archive':payload()}}},
        'Parameters':{'EnableDbBootstrap':{'Type':'String','Default':'false','AllowedValues':['true','false']},'NotificationEmail':{'Type':'String','Default':'','NoEcho':True}},
        'Conditions':{'AllowDbBootstrap':{'Fn::Equals':[ref('EnableDbBootstrap'),'true']},'HasNotificationEmail':{'Fn::Not':[{'Fn::Equals':[ref('NotificationEmail'),'']}]}},'Resources':r,'Outputs':outputs}

def payload(bootstrap_only=False):
    buf=io.BytesIO()
    hashes={}
    with tarfile.open(fileobj=buf,mode='w') as archive:
        for p in sorted(HOST.iterdir()):
            if bootstrap_only and p.name in ['release.py','manifest.py','configure-release.py','prepare-pipeline.py','production-compose.json','production-config.example.json']:
                continue
            if p.is_file() and p.suffix in ['.sh','.py','.json','.txt','.lock','.service','.timer']:
                data=p.read_bytes(); info=tarfile.TarInfo('host/'+p.name);info.size=len(data);info.mtime=0;info.mode=0o700 if p.suffix in ['.py','.sh'] else 0o600
                archive.addfile(info,io.BytesIO(data))
                hashes[p.name]=hashlib.sha256(data).hexdigest()
        if not bootstrap_only:
            data=json.dumps(hashes,sort_keys=True).encode()
            info=tarfile.TarInfo('host/pipeline-files.json');info.size=len(data);info.mtime=0;info.mode=0o600
            archive.addfile(info,io.BytesIO(data))
    return base64.b64encode(gzip.compress(buf.getvalue(),mtime=0)).decode()

def runtime():
    r={}
    r['Vpc']=resource('AWS::EC2::VPC',{'CidrBlock':'10.42.0.0/16','EnableDnsSupport':True,'EnableDnsHostnames':True,'Tags':TAGS})
    for n,cidr,az in [('AppSubnet','10.42.1.0/24','ap-northeast-2a'),('DbSubnetA','10.42.11.0/24','ap-northeast-2a'),('DbSubnetC','10.42.12.0/24','ap-northeast-2c')]:
        r[n]=resource('AWS::EC2::Subnet',{'VpcId':ref('Vpc'),'CidrBlock':cidr,'AvailabilityZone':az,'MapPublicIpOnLaunch':False,'Tags':TAGS})
    r['InternetGateway']=resource('AWS::EC2::InternetGateway',{'Tags':TAGS})
    r['GatewayAttachment']=resource('AWS::EC2::VPCGatewayAttachment',{'VpcId':ref('Vpc'),'InternetGatewayId':ref('InternetGateway')})
    for n in ['PublicRoutes','PrivateRoutes']:
        r[n]=resource('AWS::EC2::RouteTable',{'VpcId':ref('Vpc'),'Tags':TAGS})
    r['PublicDefaultRoute']=resource('AWS::EC2::Route',{'RouteTableId':ref('PublicRoutes'),'DestinationCidrBlock':'0.0.0.0/0','GatewayId':ref('InternetGateway')},DependsOn='GatewayAttachment')
    for n,table in [('AppSubnet','PublicRoutes'),('DbSubnetA','PrivateRoutes'),('DbSubnetC','PrivateRoutes')]:
        r[n+'Routes']=resource('AWS::EC2::SubnetRouteTableAssociation',{'SubnetId':ref(n),'RouteTableId':ref(table)})
    r['AppSecurityGroup']=resource('AWS::EC2::SecurityGroup',{'GroupDescription':'Only public HTTP/HTTPS; DB and HTTPS egress','VpcId':ref('Vpc'),'Tags':TAGS,
        'SecurityGroupIngress':[{'IpProtocol':'tcp','FromPort':p,'ToPort':p,'CidrIp':'0.0.0.0/0'} for p in [80,443]],
        'SecurityGroupEgress':[{'IpProtocol':'tcp','FromPort':443,'ToPort':443,'CidrIp':'0.0.0.0/0'}]})
    r['DbSecurityGroup']=resource('AWS::EC2::SecurityGroup',{'GroupDescription':'PostgreSQL only from app SG','VpcId':ref('Vpc'),'Tags':TAGS,
        'SecurityGroupIngress':[{'IpProtocol':'tcp','FromPort':5432,'ToPort':5432,'SourceSecurityGroupId':ref('AppSecurityGroup')}],
        'SecurityGroupEgress':[{'IpProtocol':'tcp','FromPort':1,'ToPort':1,'CidrIp':'127.0.0.1/32'}]})
    r['AppDbEgress']=resource('AWS::EC2::SecurityGroupEgress',{'GroupId':ref('AppSecurityGroup'),'IpProtocol':'tcp','FromPort':5432,'ToPort':5432,'DestinationSecurityGroupId':ref('DbSecurityGroup')})
    r['DbSubnetGroup']=resource('AWS::RDS::DBSubnetGroup',{'DBSubnetGroupName':'capstone-prod','DBSubnetGroupDescription':'Two private AZ subnets','SubnetIds':[ref('DbSubnetA'),ref('DbSubnetC')],'Tags':TAGS})
    r['DbParameters']=resource('AWS::RDS::DBParameterGroup',{'DBParameterGroupName':'capstone-postgres16','Description':'CAPSTONE TLS and confidential SQL logging','Family':'postgres16',
        'Parameters':{'rds.force_ssl':'1','log_statement':'none','log_min_error_statement':'panic'},'Tags':TAGS})
    r['Database']=resource('AWS::RDS::DBInstance',{'DBInstanceIdentifier':'capstone-prod','DBName':'capstone','Engine':'postgres','EngineVersion':'16.15','DBInstanceClass':'db.t3.micro',
        'AllocatedStorage':'20','StorageType':'gp3','StorageEncrypted':True,'MultiAZ':False,'AvailabilityZone':'ap-northeast-2a','PubliclyAccessible':False,
        'DBSubnetGroupName':ref('DbSubnetGroup'),'DBParameterGroupName':ref('DbParameters'),'VPCSecurityGroups':[ref('DbSecurityGroup')],
        'MasterUsername':'capstone_admin','MasterUserPassword':sub('{{resolve:ssm-secure:/capstone/prod/bootstrap/rds-master-password:${MasterPasswordVersion}}}'),
        # The real Free Account rejected 7 days before creating any database.
        # Keep daily automated backup/PITR enabled with a one-day window.
        'BackupRetentionPeriod':1,'DeletionProtection':True,'CopyTagsToSnapshot':True,'AutoMinorVersionUpgrade':True,'EnableCloudwatchLogsExports':['postgresql'],
        'MonitoringInterval':0,'EnablePerformanceInsights':False,'EngineLifecycleSupport':'open-source-rds-extended-support-disabled','Tags':TAGS},DeletionPolicy='Snapshot',UpdateReplacePolicy='Snapshot')
    r['Database']['Metadata']={'cfn-lint':{'config':{'ignore_checks':['E3691']}}}
    # E3691 uses an older static engine list. Seoul DescribeDBEngineVersions and
    # DescribeOrderableDBInstanceOptions both confirm 16.15 + db.t3.micro + gp3.
    r['RdsLogs']=resource('AWS::Logs::LogGroup',{'LogGroupName':'/aws/rds/instance/capstone-prod/postgresql','RetentionInDays':14})
    # RDS creates its native log group. The delegated policy cannot manage /aws/rds logs;
    # omit exports rather than widening the execution role's /capstone/* scope.
    del r['RdsLogs'];r['Database']['Properties'].pop('EnableCloudwatchLogsExports')
    r['Address']=resource('AWS::EC2::EIP',{'Domain':'vpc','Tags':TAGS},DeletionPolicy='Retain',UpdateReplacePolicy='Retain')
    header='#!/bin/bash\nset -euo pipefail\numask 077\nmkdir -p /opt/capstone /etc/capstone\n'
    unpack="python3 - <<'PY'\nimport base64,gzip,io,tarfile\ndata=base64.b64decode('"+payload(bootstrap_only=True)+"')\nwith tarfile.open(fileobj=io.BytesIO(gzip.decompress(data))) as a: a.extractall('/opt/capstone')\nPY\n"
    userdata={'Fn::Base64':{'Fn::Join':['',[header,unpack,"cat > /etc/capstone/infrastructure.json <<'JSON'\n{\"dbEndpoint\":\"",get('Database','Endpoint.Address'),"\",\"appDomain\":\"capsnote.art\"}\nJSON\n/bin/bash /opt/capstone/host/install.sh\n"]]}}
    r['Host']=resource('AWS::EC2::Instance',{'ImageId':'ami-0870825cefcaafcc8','InstanceType':'t3.small','IamInstanceProfile':imported('HostProfile'),
        'SubnetId':ref('AppSubnet'),'SecurityGroupIds':[ref('AppSecurityGroup')],'MetadataOptions':{'HttpTokens':'required','HttpPutResponseHopLimit':1,'HttpEndpoint':'enabled'},
        'CreditSpecification':{'CPUCredits':'standard'},'Monitoring':False,'EbsOptimized':True,'BlockDeviceMappings':[{'DeviceName':'/dev/xvda','Ebs':{'VolumeType':'gp3','VolumeSize':30,'Encrypted':True,'DeleteOnTermination':False}}],
        'Tags':TAGS,'PropagateTagsToVolumeOnCreation':True,'UserData':userdata},DeletionPolicy='Retain',UpdateReplacePolicy='Retain',DependsOn='PublicDefaultRoute')
    r['AddressAssociation']=resource('AWS::EC2::EIPAssociation',{'InstanceId':ref('Host'),'AllocationId':get('Address','AllocationId')})
    alarms=[('InstanceStatus','AWS/EC2','StatusCheckFailed','Maximum',1,'GreaterThanOrEqualToThreshold',[{'Name':'InstanceId','Value':ref('Host')}],2),
        ('DbStorage','AWS/RDS','FreeStorageSpace','Minimum',4294967296,'LessThanThreshold',[{'Name':'DBInstanceIdentifier','Value':ref('Database')}],2),
        ('DbCpu','AWS/RDS','CPUUtilization','Average',80,'GreaterThanThreshold',[{'Name':'DBInstanceIdentifier','Value':ref('Database')}],3),
        ('DbCpuCredits','AWS/RDS','CPUSurplusCreditsCharged','Sum',0,'GreaterThanThreshold',[{'Name':'DBInstanceIdentifier','Value':ref('Database')}],1),
        ('Memory','capstone','MemoryUsedPercent','Maximum',75,'GreaterThanThreshold',[{'Name':'Host','Value':'capstone-prod'}],3),
        ('Disk','capstone','DiskUsedPercent','Maximum',80,'GreaterThanThreshold',[{'Name':'Host','Value':'capstone-prod'}],2),
        ('HostReady','capstone','HostReady','Minimum',1,'LessThanThreshold',[{'Name':'Host','Value':'capstone-prod'}],3),
        ('Certificate','capstone','CertificateExpiring','Maximum',1,'GreaterThanOrEqualToThreshold',[{'Name':'Host','Value':'capstone-prod'}],1)]
    for n,namespace,metric,stat,threshold,comparison,dimensions,periods in alarms:
        r[n+'Alarm']=resource('AWS::CloudWatch::Alarm',{'AlarmName':'capstone-'+n.lower(),'Namespace':namespace,'MetricName':metric,'Statistic':stat,'Period':300,'EvaluationPeriods':periods,
            'Threshold':threshold,'ComparisonOperator':comparison,'Dimensions':dimensions,'TreatMissingData':'notBreaching' if n=='Certificate' else 'breaching',
            'AlarmActions':[imported('TopicArn')],'OKActions':[imported('TopicArn')],'Tags':TAGS})
    return {'AWSTemplateFormatVersion':'2010-09-09','Description':'CAPSTONE P6 Seoul runtime: single amd64 host, EIP, private PostgreSQL 16; no AI/NAT/ALB',
        'Parameters':{'MasterPasswordVersion':{'Type':'Number','Default':1,'MinValue':1}},'Resources':r,
        'Outputs':{n:{'Value':v} for n,v in {'VpcId':ref('Vpc'),'InstanceId':ref('Host'),'ElasticIp':ref('Address'),'RdsEndpoint':get('Database','Endpoint.Address'),
            'AppSecurityGroupId':ref('AppSecurityGroup'),'DbSecurityGroupId':ref('DbSecurityGroup')}.items()}}

if __name__=='__main__':
    live_runtime=runtime()
    user_parts=live_runtime['Resources']['Host']['Properties']['UserData']['Fn::Base64']['Fn::Join'][1]
    assert sum(len((p if isinstance(p,str) else 'x'*255).encode()) for p in user_parts) <= 16384, 'UserData limit exceeded'
    network={'AWSTemplateFormatVersion':'2010-09-09','Description':'CAPSTONE runtime network initialization before retained EIP import',
        'Resources':{k:v for k,v in live_runtime['Resources'].items() if v['Type'].startswith('AWS::EC2::') and k not in ['Address','AddressAssociation','Host']}}
    address_import={'AWSTemplateFormatVersion':'2010-09-09','Description':'Import retained P6 EIP only, before adding runtime infrastructure',
        'Resources':dict(network['Resources'],Address=live_runtime['Resources']['Address'])}
    for name,template in [('bootstrap',bootstrap()),('runtime',live_runtime),('runtime-network',network),('runtime-address-import',address_import)]:
        text=json.dumps(template,indent=2,ensure_ascii=False)+'\n'
        if len(text.encode()) > 51200:
            text=json.dumps(template,indent=1,ensure_ascii=False)+'\n'
        assert len(text.encode()) <= 51200, 'CloudFormation inline template limit exceeded'
        (HERE/(name+'.json')).write_text(text)
        print(name,len(text.encode()),'bytes',len(template['Resources']),'resources')
