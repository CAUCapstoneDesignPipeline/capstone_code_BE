import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('guard', Path(__file__).parents[1] / 'plan_guard.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PlanGuardTest(unittest.TestCase):
    backend = {'bucket': 'capsnote-169516793433-tfstate', 'prefix': 'gcp/production'}

    def plan(self, kind, after, actions=None):
        return {'resource_changes': [{'type': kind, 'change': {'actions': actions or ['create'], 'after': after}}]}

    def test_review_is_not_approval(self):
        result = guard.check(self.plan('google_iam_workload_identity_pool', {'disabled': True}), self.backend)
        self.assertFalse(result['approval'])

    def test_rejects_wrong_root_family_secret_delete_and_enabled_wif(self):
        cases = [self.plan('google_compute_instance', {'boot_disk': [{'initialize_params': [{'image': 'family/ubuntu'}]}]}),
                 self.plan('google_secret_manager_secret_version', {}),
                 self.plan('google_sql_user', {}),
                 self.plan('google_storage_bucket', {}, ['delete', 'create']),
                 self.plan('google_iam_workload_identity_pool', {'disabled': False}),
                 self.plan('google_monitoring_alert_policy', {'enabled': False})]
        for plan in cases:
            with self.subTest(kind=plan['resource_changes'][0]['type']), self.assertRaises(guard.InvalidPlan):
                guard.check(plan, self.backend)
        with self.assertRaises(guard.InvalidPlan):
            guard.check(cases[0], dict(self.backend, prefix='gcp/bootstrap'))
