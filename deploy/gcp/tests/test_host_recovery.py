import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_engine import Fake, manifest
import host
from contract import ReleaseError

class HostRecoveryTest(unittest.TestCase):
    def test_boot_checks_current_schema_and_closes_after_public_failure(self):
        for failure in ['public','wrong-schema']:
            f=Fake()
            if failure=='public':f.fail='public'
            else:f.rows[-1]['version']='1'
            with tempfile.TemporaryDirectory() as directory,patch.object(host,'ROOT',Path(directory)/'state'),patch.object(host,'RUNTIME',Path(directory)/'runtime'),patch.object(host,'Host',return_value=f),patch.object(host.sys,'argv',['host','--resume']):
                with self.assertRaises(ReleaseError):host.main()
            self.assertTrue(f.closed)
            if failure=='wrong-schema':self.assertFalse(any(isinstance(e,tuple) and e[0]=='activate' for e in f.events))
    def test_inventory_names_are_unique_across_interrupted_attempts(self):
        names=[]
        def command(args,**kwargs):
            if args[:2]==['docker','create']:
                names.append(args[args.index('--name')+1])
            if args[:2]==['docker','cp']:raise ReleaseError('interrupted')
            return ''
        with patch.object(host,'command',side_effect=command):
            for _ in range(2):
                with self.assertRaises(ReleaseError):host.Host.check_image_migrations(None,manifest(),[])
        self.assertEqual(len(set(names)),2)
