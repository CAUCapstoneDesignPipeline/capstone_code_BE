"""ADC metadata and GCS JSON API. HTTP errors never expose response bodies."""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request
from contract import ReleaseError, canonical, require


class Cloud:
    def __init__(self, bucket, token=None, opener=None):
        self.bucket = bucket
        self.token = token or self.metadata_token
        self.open = opener or urllib.request.urlopen

    @staticmethod
    def metadata_token():
        request = urllib.request.Request('http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token', headers={'Metadata-Flavor': 'Google'})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)['access_token']

    def request(self, url, *, data=None, method='GET'):
        headers = {'Authorization': 'Bearer ' + self.token(), 'Content-Type': 'application/json'}
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code in [404, 412]:
                return {'_httpStatus': error.code}
            raise ReleaseError('Cloud request rejected (details suppressed)') from None
        except Exception:
            raise ReleaseError('Cloud response unavailable; reconcile before retry') from None

    def object_url(self, name):
        return 'https://storage.googleapis.com/storage/v1/b/' + self.bucket + '/o/' + urllib.parse.quote(name, safe='')

    def read(self, name):
        meta = self.request(self.object_url(name))
        if meta.get('_httpStatus') == 404:
            return None, 0
        require('_httpStatus' not in meta, 'Object metadata unavailable')
        generation = int(meta['generation'])
        data = self.request(self.object_url(name) + '?alt=media&generation=' + str(generation))
        require('_httpStatus' not in data, 'Pinned object disappeared')
        return data, generation

    def put(self, name, value, generation):
        query = urllib.parse.urlencode({'uploadType': 'media', 'name': name, 'ifGenerationMatch': str(generation)})
        result = self.request('https://storage.googleapis.com/upload/storage/v1/b/' + self.bucket + '/o?' + query,
                              data=canonical(value).encode(), method='POST')
        require('_httpStatus' not in result, 'Object generation conflict')
        return int(result['generation'])

    def create_manifest(self, manifest):
        name = 'releases/' + manifest['releaseId'] + '.json'
        existing, generation = self.read(name)
        if existing is not None:
            require(existing == manifest, 'Release ID reused with different content')
            return generation
        return self.put(name, manifest, 0)

    def secret(self, resource):
        result = self.request('https://secretmanager.googleapis.com/v1/' + resource + ':access')
        require(result.get('name') == resource, 'Secret resource mismatch')
        return base64.b64decode(result['payload']['data'], validate=True).decode()
