#!/usr/bin/env python3
"""Write only sanitized app readiness and TLS expiry metrics, never response bodies."""
import json
from pathlib import Path
import socket
import ssl
import time
import urllib.error
import urllib.request
from host import ROOT, atomic


def sample(opener=urllib.request.urlopen, clock=time.time):
    start=time.monotonic()
    state='degraded';expiring=None
    try:
        with opener('http://127.0.0.1:18080/actuator/health',timeout=5) as response:
            database_ready=response.status==200 and json.load(response).get('status')=='UP'
    except Exception:
        database_ready=False
    try:
        context=ssl.create_default_context()
        with socket.create_connection(('127.0.0.1',443),timeout=5) as connection:
            with context.wrap_socket(connection,server_hostname='capsnote.art') as tls:
                expiring=ssl.cert_time_to_seconds(tls.getpeercert()['notAfter'])-clock()<30*86400
                tls.sendall(b'GET /api/capabilities HTTP/1.1\r\nHost: capsnote.art\r\nConnection: close\r\n\r\n')
                first=tls.recv(1024).split(b'\r\n')[0]
                if b' 503 ' in first:state='maintenance'
                elif b' 200 ' in first and database_ready:state='ready'
    except Exception:
        state='degraded'
    return {'component':'capstone-app','state':state,'tlsExpiring':expiring,
            'latencyMs':round((time.monotonic()-start)*1000),'timestamp':int(clock())}


if __name__=='__main__':
    atomic(Path('/var/log/capstone-gcp/health.json'),json.dumps(sample(),sort_keys=True)+'\n',0o640)
