#!/usr/bin/env python3
"""Validate caller, submit durable operation over IAP, and observe bounded status."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from contract import checksum, match, require, validate_request, ID, SHA, DIGEST

OWNER = '324880339'
REPOSITORIES = {'CAUCapstoneDesignPipeline/capstone_code_BE': ('1397266620','be'),
                'CAUCapstoneDesignPipeline/capstone_code_FE': ('1397258129','web')}


def prepare(env, root):
    repo=env.get('GITHUB_REPOSITORY')
    require(repo in REPOSITORIES, 'Unapproved caller')
    number, component=REPOSITORIES[repo]
    require(env.get('GITHUB_REPOSITORY_ID') == number and env.get('GITHUB_REPOSITORY_OWNER_ID') == OWNER, 'Wrong numeric identity')
    require(env.get('GITHUB_REF') == 'refs/heads/main' and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Manual main only')
    require(env.get('GITHUB_WORKFLOW_REF') == repo+'/.github/workflows/gcp-deploy.yml@refs/heads/main', 'Wrong caller workflow')
    require(env.get('GCP_DEPLOY_ENABLED') == 'true', 'Deployment disabled')
    require(env.get('COMPONENT') == component, 'Caller may only select its own component')
    require(match(SHA,env.get('DEPLOY_TOOL_SHA')) and match(SHA,env.get('SOURCE_SHA')), 'Immutable source/tool required')
    actual=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    require(actual == env['DEPLOY_TOOL_SHA'], 'Tool checkout mismatch')
    path=env.get('MANIFEST_PATH','')
    require(match(r'deploy/gcp/releases/'+ID+r'\.json',path), 'Reviewed manifest path required')
    require(match(SHA, env.get('MANIFEST_SHA')), 'Reviewed manifest source SHA required')
    release_root=root/'reviewed-release'
    manifest_sha=subprocess.check_output(['git','-C',str(release_root),'rev-parse','HEAD'],text=True).strip()
    require(manifest_sha == env['MANIFEST_SHA'], 'Manifest checkout mismatch')
    resolved=(release_root/path).resolve()
    require(release_root.resolve() in resolved.parents and not (release_root/path).is_symlink(), 'Manifest path escapes checkout')
    manifest=json.loads(resolved.read_text())
    require(manifest[component]['sourceSha'] == env['SOURCE_SHA'], 'Source differs from reviewed manifest')
    require(match(DIGEST,env.get('IMAGE_DIGEST')) and manifest[component]['image'].endswith('@'+env['IMAGE_DIGEST']), 'Digest differs from reviewed manifest')
    request={'operationId':env.get('OPERATION_ID'),'operation':env.get('OPERATION'),
             'expectedRelease':env.get('EXPECTED_RELEASE'),'manifest':manifest,'manifestHash':checksum(manifest)}
    validate_request(request)
    require(env.get('GCP_PROJECT_ID') == 'capsnote' and env.get('GCP_ZONE') in ['asia-northeast3-a','asia-northeast3-b','asia-northeast3-c'] and env.get('GCP_VM_NAME') == 'capsnote-prod-app', 'Wrong deployment target')
    return request


def ssh(env, command, data=None):
    args=['gcloud','compute','ssh',env['GCP_VM_NAME'],'--project',env['GCP_PROJECT_ID'],
          '--zone',env['GCP_ZONE'],'--tunnel-through-iap','--quiet','--command',command]
    result=subprocess.run(args,input=data,capture_output=True,text=True,timeout=90)
    require(result.returncode == 0,'IAP command failed; operation may still be running')
    return json.loads(result.stdout)


def main():
    request=prepare(os.environ,Path.cwd())
    if sys.argv[1:] == ['validate']:
        print('Caller, immutable tools and reviewed manifest validated')
        return
    require(not sys.argv[1:], 'Unsupported command')
    reply=ssh(os.environ,'sudo /usr/bin/python3 /opt/capstone-gcp/submit.py',json.dumps(request))
    require(reply['operationId'] == request['operationId'],'Unexpected operation')
    # Only operation IDs matching the strict grammar enter this fixed command.
    for attempt in range(120):
        reply=ssh(os.environ,'sudo /usr/bin/python3 /opt/capstone-gcp/status.py '+request['operationId'])
        if reply['status'] == 'done':
            with open(os.environ['GITHUB_OUTPUT'],'a') as output:
                output.write('operation_id='+request['operationId']+'\nrelease_id='+request['manifest']['releaseId']+'\n')
            return
        require(reply['status'] not in ['failed','rolled-back'],'Host failed; inspect/reconcile durable journal')
        time.sleep(10)
    raise RuntimeError('Observation timed out; host continues; retry identical operation')


if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('GCP deployment did not complete; inspect operation status; details suppressed') from None
