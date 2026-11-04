import base64
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit, unquote
from test_storage import Reply
from test_engine import manifest
from contract import ReleaseError
from host import release_scope
from storage import Cloud
import iap


class RehearsalBoundaryTest(unittest.TestCase):
    def test_rehearsal_cannot_select_prod_and_restore_must_be_verified(self):
        prod = {'releaseBucket':'capsnote-169516793433-releases','databasePurpose':'restored-cutover',
                'restoreVerified':True, 'sqlConnection':'capsnote:asia-northeast3:capsnote-prod-pg16'}
        self.assertEqual(release_scope(prod),'')
        scratch = dict(prod, databasePurpose='isolated-rehearsal', restoreVerified=False,
                       sqlConnection='capsnote:asia-northeast3:capsnote-rehearsal-fixture')
        prefix=release_scope(scratch)
        self.assertEqual(prefix, 'rehearsals/capsnote-rehearsal-fixture/')
        for c in (dict(prod,restoreVerified=False),dict(scratch,sqlConnection=prod['sqlConnection']),
                  dict(prod,sqlConnection=scratch['sqlConnection']),dict(scratch,sqlConnection=scratch['sqlConnection']+'/../')):
            with self.assertRaises(ReleaseError):release_scope(c)
        names=[]
        def open(req,timeout):
            if req.method=='POST':
                names.append(parse_qs(urlsplit(req.full_url).query)['name'][0])
                return Reply({'generation':'1'})
            names.append(unquote(urlsplit(req.full_url).path.split('/o/')[-1]))
            return Reply({'_httpStatus':404})
        store=Cloud(prod['releaseBucket'],token=lambda:'fixture',opener=open,prefix=prefix)
        store.read('state.json');store.put('state.json',{},0);store.create_manifest(manifest())
        self.assertEqual(names,[prefix+'state.json']*2 + [prefix+'releases/next.json']*2)

    def test_secret_accepts_project_number_but_not_other_version_or_project(self):
        ref=manifest()['secretVersions']['dbPassword']
        for name,ok in [(ref.replace('capsnote/','169516793433/'),True),(ref+'0',False),
                        (ref.replace('capsnote/','123456/'),False)]:
            store=Cloud('b',token=lambda:'x',opener=lambda *a,**k:Reply(
                {'name':name,'payload':{'data':base64.b64encode(b'fixture').decode()}}))
            if ok:self.assertEqual(store.secret(ref),'fixture')
            else:
                with self.assertRaises(ReleaseError):store.secret(ref)

    def test_ssh_failure_revokes_only_its_own_key(self):
        calls=[]; entry={}
        def run(args,**kw):
            calls.append(args)
            if args[0]=='ssh-keygen':
                Path(args[-1]+'.pub').write_text('ssh-ed25519 fixture label')
            elif 'add' in args:
                self.assertIn('--ttl=30m',args)
                entry.update(key='ssh-ed25519 fixture label',fingerprint='own')
        def keys(*a,**kw):return json.dumps([{'value':entry},{'value':{'key':'ssh-ed25519 other','fingerprint':'other'}}])
        with patch.object(iap.subprocess,'run',side_effect=run),patch.object(iap.subprocess,'check_output',side_effect=keys):
            with self.assertRaisesRegex(RuntimeError,'disconnect'):
                with iap.connection() as cmd:
                    self.assertIn('StrictHostKeyChecking=yes',cmd)
                    path=Path(next(a.split('=',1)[1] for a in cmd if a.startswith('UserKnownHostsFile=')))
                    self.assertEqual(path.read_text().strip(),iap.HOST+' '+iap.PUBLIC_KEY)
                    raise RuntimeError('disconnect')
            self.assertFalse(path.exists())
            self.assertEqual([x for c in calls for x in c if x.startswith('--key=')],['--key=own'])
