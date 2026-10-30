#!/bin/bash
set -euo pipefail
# Only the dedicated proxy can obtain runtime ADC. App and web remain blocked.
iptables -C DOCKER-USER -s 172.30.36.0/24 -d 169.254.169.254/32 -j REJECT 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.30.36.0/24 -d 169.254.169.254/32 -j REJECT
iptables -C DOCKER-USER -s 172.30.36.2/32 -d 169.254.169.254/32 -j ACCEPT 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.30.36.2/32 -d 169.254.169.254/32 -j ACCEPT
# Bridge has IPv6 disabled; fail if that assumption changes.
test "$(docker network inspect capstone-gcp_runtime --format '{{.EnableIPv6}}' 2>/dev/null || echo false)" = false
