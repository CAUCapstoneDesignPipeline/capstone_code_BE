#!/bin/bash
set -euo pipefail
umask 077
test ! -f /etc/capstone/current.json
test ! -f /etc/capstone/release-pending.json
dnf install -y docker nginx postgresql16 python3.11 python3.11-pip amazon-cloudwatch-agent iptables
systemctl enable --now amazon-ssm-agent docker
install -d -m 0755 /usr/local/lib/docker/cli-plugins /var/www/capstone/.well-known/acme-challenge /var/log/capstone
install -d -m 0700 /run/capstone /etc/capstone /opt/capstone/releases
curl --fail --silent --show-error --location --retry 5 https://github.com/docker/compose/releases/download/v5.6.0/docker-compose-linux-x86_64 -o /usr/local/lib/docker/cli-plugins/docker-compose
echo '40343e21ca777173e69cff5dbafeb37c6f81f3b0d57d9e597f036e95eb63e76a  /usr/local/lib/docker/cli-plugins/docker-compose' | sha256sum -c -
chmod 0755 /usr/local/lib/docker/cli-plugins/docker-compose
python3.11 -m venv /opt/capstone/tools
install -d -m 0700 /opt/capstone/wheels
/opt/capstone/tools/bin/pip --disable-pip-version-check download --only-binary=:all: --require-hashes --python-version 311 --implementation cp --abi cp311 --platform manylinux_2_28_x86_64 --platform manylinux2014_x86_64 --dest /opt/capstone/wheels -r /opt/capstone/host/requirements.lock
/opt/capstone/tools/bin/pip --disable-pip-version-check install --no-index --find-links /opt/capstone/wheels --require-hashes -r /opt/capstone/host/requirements.lock
curl --fail --silent --show-error --retry 5 https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem -o /etc/capstone/rds-ca.pem
chmod 0644 /etc/capstone/rds-ca.pem
cat > /etc/nginx/conf.d/capstone.conf <<'NGINX'
server {
    listen 80 default_server;
    server_name capsnote.art;
    access_log off;
    error_log /var/log/capstone/nginx-error.log warn;
    location ^~ /.well-known/acme-challenge/ { root /var/www/capstone; }
    location = / { default_type text/plain; return 200 'CAPSNOTE infrastructure ready (api-only). API and Google login are not deployed yet.\n'; }
    location / { return 503; }
}
NGINX
# Remove only the AMI package's default listener; this is a new project host.
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/nginx/nginx.conf')
p.write_text('user nginx;\nworker_processes auto;\nerror_log /var/log/capstone/nginx-error.log warn;\npid /run/nginx.pid;\nevents { worker_connections 1024; }\nhttp { include /etc/nginx/mime.types; default_type application/octet-stream; access_log off; server_tokens off; include /etc/nginx/conf.d/*.conf; }\n')
PY
nginx -t
systemctl enable --now nginx
cat > /etc/systemd/system/capstone-container-boundary.service <<'UNIT'
[Unit]
Description=Block container access to instance metadata
After=docker.service
Requires=docker.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/bash -c 'iptables -C DOCKER-USER -d 169.254.169.254/32 -j REJECT 2>/dev/null || iptables -I DOCKER-USER -d 169.254.169.254/32 -j REJECT'
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now capstone-container-boundary
cat > /etc/logrotate.d/capstone <<'ROTATE'
/var/log/capstone/*.log {
 daily
 rotate 7
 size 10M
 missingok
 notifempty
 compress
 copytruncate
}
ROTATE
install -m 0644 /opt/capstone/host/metrics.service /etc/systemd/system/capstone-metrics.service
install -m 0644 /opt/capstone/host/metrics.timer /etc/systemd/system/capstone-metrics.timer
systemctl daemon-reload
systemctl enable --now capstone-metrics.timer
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/capstone/host/cloudwatch.json -s
install -m 0644 /opt/capstone/host/resume.service /etc/systemd/system/capstone-release-resume.service
systemctl daemon-reload
systemctl enable capstone-release-resume.service
touch /etc/capstone/bootstrap-complete
echo 'Host installation complete; API deployment pending.'
