#!/usr/bin/env python3
"""Review a saved Terraform JSON plan without credentials or cloud writes.

This is a prerequisite for approval, never an approval token or apply wrapper.
Do not publish plan JSON: it can contain sensitive Terraform values.
"""
import argparse
import json
import re
from pathlib import Path


class InvalidPlan(ValueError):
    pass


def check(plan, backend, *, bootstrap=False):
    errors = []
    expected_prefix = 'gcp/bootstrap' if bootstrap else 'gcp/production'
    if backend != {'bucket': 'capsnote-169516793433-tfstate', 'prefix': expected_prefix}:
        errors.append('backend bucket/prefix differs from the reviewed root')
    changes = plan.get('resource_changes', [])
    if not changes:
        errors.append('plan has no resource changes to review')
    for change in changes:
        actions = change['change']['actions']
        kind = change['type']
        after = change['change'].get('after') or {}
        if 'delete' in actions:
            errors.append('resource deletion or replacement needs a separate recovery review')
        if kind in {'google_project', 'google_project_billing_info', 'google_sql_user', 'google_secret_manager_secret_version'}:
            errors.append('existing project/billing or secret payload must not be managed here')
        if kind == 'google_compute_instance':
            image = after.get('boot_disk', [{}])[0].get('initialize_params', [{}])[0].get('image', '')
            if not re.fullmatch(r'projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v[0-9]+', image):
                errors.append('VM exact Ubuntu image is unresolved')
        if kind in {'google_iam_workload_identity_pool', 'google_iam_workload_identity_pool_provider'} and after.get('disabled') is not True:
            errors.append('initial provisioning must keep WIF disabled')
        if kind == 'google_monitoring_alert_policy' and not after.get('enabled'):
            errors.append('reviewed alert recipient required before provisioning')
    if errors:
        raise InvalidPlan('; '.join(sorted(set(errors))))
    return {'status': 'ready-for-human-review', 'resources': len(changes), 'approval': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plan', type=Path)
    parser.add_argument('backend_json', type=Path, help='non-secret bucket/prefix JSON matching actual init configuration')
    parser.add_argument('--bootstrap', action='store_true')
    args = parser.parse_args()
    try:
        result = check(json.loads(args.plan.read_text()), json.loads(args.backend_json.read_text()), bootstrap=args.bootstrap)
    except (ValueError, KeyError, TypeError):
        raise SystemExit('Plan rejected: inspect guarded fields locally; values suppressed') from None
    print(json.dumps(result))


if __name__ == '__main__':
    main()
