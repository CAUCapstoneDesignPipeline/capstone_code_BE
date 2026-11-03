import io
import json
import unittest
from unittest.mock import patch
import urllib.error
from test_engine import manifest
from storage import Cloud
from contract import ReleaseError

class Reply(io.BytesIO):
    def __init__(self,value):super().__init__(json.dumps(value).encode())

class StorageTest(unittest.TestCase):
    def test_read_pins_generation_and_write_is_cas(self):
        requests=[]
        def open(req,timeout):
            requests.append(req)
            if req.method=='POST':return Reply({'generation':'43'})
            if 'alt=media' in req.full_url:return Reply({'current':None})
            return Reply({'generation':'42'})
        cloud=Cloud('bucket',token=lambda:'synthetic',opener=open)
        self.assertEqual(cloud.read('state.json'),({'current':None},42))
        self.assertIn('generation=42',requests[1].full_url)
        cloud.put('state.json',{},42)
        self.assertIn('ifGenerationMatch=42',requests[-1].full_url)
    def test_404_only_is_absence_and_412_is_conflict(self):
        def fail(code):
            def open(req,timeout):raise urllib.error.HTTPError(req.full_url,code,'suppressed',{},None)
            return open
        self.assertEqual(Cloud('b',token=lambda:'x',opener=fail(404)).read('state.json'),(None,0))
        for code in [401,403,429,500]:
            with self.assertRaises(ReleaseError):Cloud('b',token=lambda:'x',opener=fail(code)).read('state.json')
        with self.assertRaises(ReleaseError):Cloud('b',token=lambda:'x',opener=fail(412)).put('state.json',{},1)
    def test_create_only_uses_generation_zero(self):
        requests=[]
        def open(req,timeout):
            requests.append(req)
            if req.method=='GET':raise urllib.error.HTTPError(req.full_url,404,'',{},None)
            return Reply({'generation':'1'})
        Cloud('b',token=lambda:'x',opener=open).create_manifest(manifest())
        self.assertIn('ifGenerationMatch=0',requests[-1].full_url)
