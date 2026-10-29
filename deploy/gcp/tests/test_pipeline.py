import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from test_engine import manifest
from contract import ReleaseError
import pipeline
import publish_image

class PipelineTest(unittest.TestCase):
    def test_valid_be_fe_and_rejected_identity_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=root/'deploy/gcp/releases/next.json';p.parent.mkdir(parents=True);p.write_text(json.dumps(manifest()))
            for repo,(number,component) in pipeline.REPOSITORIES.items():
                env={'GITHUB_REPOSITORY':repo,'GITHUB_REPOSITORY_ID':number,'GITHUB_REPOSITORY_OWNER_ID':pipeline.OWNER,
                     'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch',
                     'GITHUB_WORKFLOW_REF':repo+'/.github/workflows/gcp-deploy.yml@refs/heads/main',
                     'GCP_DEPLOY_ENABLED':'true','COMPONENT':component,'DEPLOY_TOOL_SHA':'a'*40,
                     'SOURCE_SHA':manifest()[component]['sourceSha'],'IMAGE_DIGEST':manifest()[component]['image'].split('@')[1],
                     'MANIFEST_PATH':'deploy/gcp/releases/next.json','OPERATION_ID':'op1','OPERATION':'deploy',
                     'EXPECTED_RELEASE':'old','GCP_PROJECT_ID':'capsnote','GCP_ZONE':'asia-northeast3-a','GCP_VM_NAME':'capsnote-prod-app'}
                with patch.object(pipeline.subprocess,'check_output',return_value='a'*40+'\n'):
                    self.assertEqual(pipeline.prepare(env,root)['operationId'],'op1')
                    for key,value in [('GITHUB_REPOSITORY_ID','1'),('GITHUB_REPOSITORY_OWNER_ID','2'),('GITHUB_REF','refs/pull/1/merge'),
                                      ('GITHUB_WORKFLOW_REF',repo+'/.github/workflows/unreviewed.yml@refs/heads/main'),
                                      ('GITHUB_EVENT_NAME','push'),('GCP_DEPLOY_ENABLED','false'),('DEPLOY_TOOL_SHA','b'*40),
                                      ('MANIFEST_PATH','../secret.json'),('COMPONENT','ai'),('SOURCE_SHA','c'*40 if component=='be' else 'a'*40)]:
                        with self.subTest(repo=repo,key=key),self.assertRaises(ReleaseError):pipeline.prepare(dict(env,**{key:value}),root)
    def test_publisher_identity_and_registry(self):
        env={'GITHUB_REPOSITORY':publish_image.REPOSITORY,'GITHUB_REPOSITORY_ID':publish_image.REPOSITORY_ID,
             'GITHUB_REPOSITORY_OWNER_ID':publish_image.OWNER_ID,'GITHUB_REF':'refs/heads/main',
             'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_WORKFLOW_REF':publish_image.REPOSITORY+'/.github/workflows/gcp-publish-image.yml@refs/heads/main',
             'GCP_IMAGE_PUBLISH_ENABLED':'true','GITHUB_SHA':'a'*40,'GCP_PROJECT_ID':'capsnote','GCP_PROJECT_NUMBER':'169516793433',
             'GCP_REGION':'asia-northeast3','AR_REPOSITORY':'capstone-be','GCP_WIF_PROVIDER':'projects/169516793433/locations/global/workloadIdentityPools/capsnote-github/providers/github',
             'GCP_IMAGE_SERVICE_ACCOUNT':'capsnote-prod-publish-be@capsnote.iam.gserviceaccount.com'}
        self.assertTrue(publish_image.configuration(env)['image'].endswith('/capstone-be/be'))
        for key,value in [('GITHUB_REF','refs/heads/develop'),('AR_REPOSITORY','capstone-web'),('GITHUB_EVENT_NAME','pull_request'),('GITHUB_REPOSITORY_ID','1397258129')]:
            with self.assertRaises(ValueError):publish_image.configuration(dict(env,**{key:value}))
