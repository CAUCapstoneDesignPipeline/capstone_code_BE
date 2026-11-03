import unittest
from unittest.mock import patch
import observe

class ObserveTest(unittest.TestCase):
    def test_failure_has_no_exception_or_response_content(self):
        def fail(*args,**kwargs):raise ValueError('sensitive-response-must-not-escape')
        with patch.object(observe.socket,'create_connection',side_effect=fail):
            result=observe.sample(opener=fail,clock=lambda:1)
        self.assertEqual(result['state'],'degraded')
        self.assertEqual(set(result),{'component','state','tlsExpiring','latencyMs','timestamp'})
        self.assertNotIn('sensitive',str(result))
