#!/bin/bash
# Execute only on an approved new Ubuntu rehearsal host, after installation review.
set -euo pipefail
umask 077
test "$(id -u)" = 0
. /etc/os-release
test "$ID:$VERSION_ID:$(uname -m)" = 'ubuntu:24.04:x86_64'
test -f /etc/capstone-gcp/installation-approved
test ! -e /var/lib/capstone-gcp/operations
test -f /etc/letsencrypt/live/capsnote.art/fullchain.pem
test -f /etc/letsencrypt/live/capsnote.art/privkey.pem
# Existing network ranges must be reviewed before allocating the container subnet.
test -f /etc/capstone-gcp/network-reviewed
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 nginx postgresql-client-16 python3 iptables certbot
install -d -m 0700 /var/lib/capstone-gcp/requests /var/lib/capstone-gcp/operations /etc/capstone-gcp/configurations
install -d -m 0755 /run/capstone-gcp /var/www/capstone/.well-known/acme-challenge
systemctl enable --now docker
install -m 0644 /opt/capstone-gcp/operation.service /etc/systemd/system/capstone-gcp-operation@.service
install -m 0644 /opt/capstone-gcp/resume.service /etc/systemd/system/capstone-gcp-resume.service
install -m 0644 /opt/capstone-gcp/boundary.service /etc/systemd/system/capstone-gcp-boundary.service
install -m 0644 /opt/capstone-gcp/nginx-maintenance.conf /etc/nginx/conf.d/capstone-gcp.conf
# Do not silently delete an existing site's configuration.
test ! -e /etc/nginx/sites-enabled/default || { echo 'Remove package default site only after review'; exit 1; }
nginx -t
systemctl daemon-reload
systemctl enable --now capstone-gcp-boundary.service nginx
systemctl enable capstone-gcp-resume.service
# Ops Agent installation is separately pinned/reviewed by the operator; never curl|bash.
test -x /opt/google-cloud-ops-agent/bin/google_cloud_ops_agent_engine
install -m 0644 /opt/capstone-gcp/ops-agent.yaml /etc/google-cloud-ops-agent/config.yaml
systemctl restart google-cloud-ops-agent
# DNS-01/manual issuance is not automatic renewal acceptance. Rehearsal must run dry-run.
systemctl enable --now certbot.timer
