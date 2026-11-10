#!/bin/bash
set -euo pipefail
# Only the dedicated proxy can obtain runtime ADC. App and web remain blocked.
iptables -C DOCKER-USER -s 172.30.36.0/24 -d 169.254.169.254/32 -j REJECT 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.30.36.0/24 -d 169.254.169.254/32 -j REJECT
iptables -C DOCKER-USER -s 172.30.36.2/32 -d 169.254.169.254/32 -j ACCEPT 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.30.36.2/32 -d 169.254.169.254/32 -j ACCEPT
# Docker may print an empty array/newline as well as an error for an absent
# network. A fresh host legitimately has no Compose network yet. Do not
# treat an unreachable daemon, or IPv6 enabled on an existing bridge, as safe.
networks=$(docker network ls --format '{{.Name}}')
if grep -Fxq capstone-gcp_runtime <<<"$networks"; then
  test "$(docker network inspect capstone-gcp_runtime --format '{{.EnableIPv6}}')" = false
fi
