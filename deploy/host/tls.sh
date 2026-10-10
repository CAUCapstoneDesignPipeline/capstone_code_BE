#!/bin/bash
set -euo pipefail
umask 077
if test -f /etc/capstone/current.json || test -f /etc/capstone/release-pending.json; then
  echo 'Initial TLS setup cannot overwrite an application or unresolved release.' >&2
  exit 78
fi
/opt/capstone/tools/bin/certbot certonly --webroot -w /var/www/capstone -d capsnote.art --non-interactive --agree-tos --register-unsafely-without-email
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/nginx/conf.d/capstone.conf')
p.write_text('''server {
 listen 80 default_server;
 server_name capsnote.art;
 access_log off;
 location ^~ /.well-known/acme-challenge/ { root /var/www/capstone; }
 location / { return 301 https://capsnote.art$request_uri; }
}
server {
 listen 443 ssl;
 server_name capsnote.art;
 ssl_certificate /etc/letsencrypt/live/capsnote.art/fullchain.pem;
 ssl_certificate_key /etc/letsencrypt/live/capsnote.art/privkey.pem;
 ssl_protocols TLSv1.2 TLSv1.3;
 access_log off;
 error_log /var/log/capstone/nginx-error.log warn;
 location = / { default_type text/plain; return 200 "CAPSNOTE infrastructure ready (api-only). API and Google login are not deployed yet.\\n"; }
 location / { return 503; }
}
''')
PY
nginx -t
systemctl reload nginx
cat > /etc/systemd/system/capstone-cert-renew.service <<'UNIT'
[Service]
Type=oneshot
ExecStart=/opt/capstone/tools/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
UNIT
cat > /etc/systemd/system/capstone-cert-renew.timer <<'UNIT'
[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now capstone-cert-renew.timer
/opt/capstone/tools/bin/certbot renew --dry-run
