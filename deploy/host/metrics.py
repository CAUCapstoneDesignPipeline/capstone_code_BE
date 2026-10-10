#!/opt/capstone/tools/bin/python
import json
import shutil
import subprocess
from pathlib import Path
import boto3

config = json.loads(Path('/etc/capstone/infrastructure.json').read_text())
memory = {}
for line in Path('/proc/meminfo').read_text().splitlines():
    key, value = line.split(':', 1)
    memory[key] = int(value.strip().split()[0])
disk = shutil.disk_usage('/')
metrics = {'MemoryUsedPercent':100*(1-memory['MemAvailable']/memory['MemTotal']),
           'DiskUsedPercent':100*disk.used/disk.total,
           'HostReady':int(Path('/etc/capstone/bootstrap-complete').exists())}
cert = Path('/etc/letsencrypt/live/capsnote.art/cert.pem')
if cert.exists():
    result = subprocess.run(['openssl','x509','-in',str(cert),'-checkend','1209600','-noout'],capture_output=True)
    metrics['CertificateExpiring'] = int(result.returncode != 0)
boto3.client('cloudwatch', region_name='ap-northeast-2').put_metric_data(
    Namespace='capstone', MetricData=[{'MetricName':k,'Value':v,'Dimensions':[{'Name':'Host','Value':'capstone-prod'}]} for k,v in metrics.items()])
