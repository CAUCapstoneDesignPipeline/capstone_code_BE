#!/bin/bash
set -euo pipefail
umask 077
exec /opt/capstone/tools/bin/python /opt/capstone/host/release.py
