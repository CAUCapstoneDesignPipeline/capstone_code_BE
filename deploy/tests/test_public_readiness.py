import importlib.util
import io
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import MagicMock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'host'))

spec=importlib.util.spec_from_file_location('host_release',Path(__file__).resolve().parents[1]/'host/release.py')
host_release=importlib.util.module_from_spec(spec)
with patch.dict(sys.modules,{'boto3':MagicMock()}):spec.loader.exec_module(host_release)

class PublicReadinessTests(unittest.TestCase):
    def test_nginx_maintenance_during_reload_then_ready(self):
        responses=[urllib.error.HTTPError('https://capsnote.art',503,'maintenance',{},None),io.BytesIO(b'{"status":"UP"}'),io.BytesIO(b'api-only')]
        for response in responses[1:]:response.status=200
        with patch.object(host_release.urllib.request,'urlopen',side_effect=responses),patch.object(host_release.time,'sleep'):
            host_release.Host.smoke_public(object.__new__(host_release.Host),{})

    def test_persistent_maintenance_fails_at_deadline(self):
        error=urllib.error.HTTPError('https://capsnote.art',503,'maintenance',{},None)
        with patch.object(host_release.urllib.request,'urlopen',side_effect=error),patch.object(host_release.time,'monotonic',side_effect=[0,31]):
            with self.assertRaises(host_release.ReleaseError):host_release.Host.smoke_public(object.__new__(host_release.Host),{})
