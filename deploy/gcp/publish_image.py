#!/usr/bin/env python3
"""Fail-closed BE image publication helpers. Credentials stay in memory."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPOSITORY = 'CAUCapstoneDesignPipeline/capstone_code_BE'
REPOSITORY_ID = '1397266620'
OWNER_ID = '324880339'
SHA = r'[0-9a-f]{40}'
DIGEST = r'sha256:[0-9a-f]{64}'


def require(pattern, value, message):
    if not re.fullmatch(pattern, value):
        raise ValueError(message)
    return value


def configuration(env):
    if (env.get('GITHUB_REPOSITORY') != REPOSITORY
            or env.get('GITHUB_REPOSITORY_ID') != REPOSITORY_ID
            or env.get('GITHUB_REPOSITORY_OWNER_ID') != OWNER_ID
            or env.get('GITHUB_REF') != 'refs/heads/main'
            or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or env.get('GITHUB_WORKFLOW_REF') != REPOSITORY + '/.github/workflows/gcp-publish-image.yml@refs/heads/main'
            or env.get('GCP_IMAGE_PUBLISH_ENABLED') != 'true'):
        raise ValueError('Publication requires the enabled canonical main workflow_dispatch')
    sha = require(SHA, env.get('GITHUB_SHA', ''), 'Invalid source SHA')
    project = require(r'[a-z][a-z0-9-]{4,28}[a-z0-9]', env.get('GCP_PROJECT_ID', ''), 'Invalid project')
    if env.get('GCP_REGION') != 'asia-northeast3' or env.get('AR_REPOSITORY') != 'capstone-be':
        raise ValueError('Only Seoul capstone-be publication is supported')
    number = require(r'[1-9][0-9]+', env.get('GCP_PROJECT_NUMBER', ''), 'Invalid project number')
    require(r'projects/' + number + r'/locations/global/workloadIdentityPools/[a-z0-9-]+/providers/[a-z0-9-]+',
            env.get('GCP_WIF_PROVIDER', ''), 'Invalid WIF provider')
    require(r'[a-z][a-z0-9-]+@' + re.escape(project) + r'\.iam\.gserviceaccount\.com',
            env.get('GCP_IMAGE_SERVICE_ACCOUNT', ''), 'Invalid publisher account')
    image = f'asia-northeast3-docker.pkg.dev/{project}/capstone-be/be'
    return {'image': image, 'source_sha': sha, 'tag': f'{image}:sha-{sha}', 'project': project}


def lookup_tag(config, token, opener=urllib.request.urlopen):
    name = (f"projects/{config['project']}/locations/asia-northeast3/"
            f"repositories/capstone-be/packages/be/tags/sha-{config['source_sha']}")
    request = urllib.request.Request('https://artifactregistry.googleapis.com/v1/' + name,
                                     headers={'Authorization': 'Bearer ' + token})
    try:
        with opener(request, timeout=30) as response:
            value = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise ValueError('Artifact Registry lookup failed; publication stopped') from None
    except (OSError, ValueError):
        raise ValueError('Artifact Registry lookup failed; publication stopped') from None
    prefix = name.split('/tags/')[0] + '/versions/'
    if value.get('name') != name or not value.get('version', '').startswith(prefix):
        raise ValueError('Registry tag response does not match requested image')
    return require(DIGEST, value['version'][len(prefix):], 'Invalid registry digest')


def metadata(config, digest, inspection):
    require(DIGEST, digest, 'Invalid digest')
    image = inspection[0]
    labels = image['Config'].get('Labels') or {}
    if (labels.get('org.opencontainers.image.revision') != config['source_sha']
            or labels.get('org.opencontainers.image.source') != 'https://github.com/' + REPOSITORY
            or image.get('Architecture') != 'amd64' or image.get('Os') != 'linux'
            or image['Config'].get('User', '').split(':')[0] in ('', 'root', '0')):
        raise ValueError('Published image does not match verified source/platform/user')
    return {'sourceSha': config['source_sha'], 'image': config['image'] + '@' + digest,
            'digest': digest, 'platform': 'linux/amd64', 'runtimeVerified': True,
            'aiEnabled': False, 'releaseProtocol': 4}


def output(values):
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as handle:
        for key, value in values.items():
            handle.write(f'{key}={value}\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['preflight', 'lookup', 'metadata'])
    args = parser.parse_args()
    config = configuration(os.environ)
    if args.operation == 'preflight':
        output(config)
    elif args.operation == 'lookup':
        token = subprocess.run(['gcloud', 'auth', 'print-access-token'], check=True,
                               capture_output=True, text=True, timeout=30).stdout.strip()
        digest = lookup_tag(config, token)
        output({'exists': str(digest is not None).lower(), 'digest': digest or ''})
    else:
        digest = os.environ.get('IMAGE_DIGEST', '')
        inspection = json.loads(subprocess.run(['docker', 'image', 'inspect', config['image'] + '@' + digest],
                                               check=True, capture_output=True, text=True, timeout=30).stdout)
        value = metadata(config, digest, inspection)
        Path('image-metadata.json').write_text(json.dumps(value, sort_keys=True) + '\n')
        output({'digest': digest})


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, subprocess.SubprocessError, OSError):
        raise SystemExit('GCP image validation failed; details suppressed') from None
