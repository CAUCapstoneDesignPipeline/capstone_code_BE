"""Serial host transaction with durable intent and a single-object GCS commit."""
from contract import ReleaseError, checksum, require, validate_request, version


def run(platform, request):
    validate_request(request)
    operation = request['operationId']
    prior = platform.journal(operation)
    if prior:
        require(prior['requestHash'] == checksum(request), 'Operation ID reused')
        require(prior['phase'] != 'rolled-back', 'Operation already rolled back; use a new operation')
        if prior['phase'] == 'done':
            return 'done'
        return recover(platform, prior)
    require(not platform.pending(), 'Previous operation requires recovery')
    state, generation = platform.cloud.read('state.json')
    state = state or {'current': None, 'previous': None}
    current = state['current']
    target = request['manifest']
    require((current['releaseId'] if current else 'none') == request['expectedRelease'], 'Stale expected release')
    require(not current or current['releaseId'] != target['releaseId'], 'Already current under a different operation')
    platform.prepare_database(current or target)
    before = platform.history(current or target)
    if request['operation'] == 'rollback':
        require(target == state['previous'], 'Rollback must use previous manifest')
        require(version(before) in target['flyway']['rollbackCompatibleWith'], 'Incompatible rollback')
    else:
        require(version(before) == target['flyway']['before'], 'Unexpected migration history')
    platform.preflight(target, before)
    platform.cloud.create_manifest(target)
    journal = {'operationId': operation, 'requestHash': checksum(request), 'request': request,
               'phase': 'prepared', 'before': before, 'oldState': state, 'generation': generation}
    platform.save(journal)
    try:
        platform.maintenance(True)
        journal['phase'] = 'switching'
        platform.save(journal)
        platform.activate(target)
        after = platform.history(target)
        expected = version(before) if request['operation'] == 'rollback' else target['flyway']['after']
        require(version(after) == expected, 'Unexpected migrated schema')
        require(after[:len(before)] == before, 'Existing Flyway history changed')
        platform.smoke(target)
        journal['phase'] = 'committing'
        platform.save(journal)
        platform.cloud.put('state.json', {'current': target, 'previous': current}, generation)
        journal['phase'] = 'committed'
        platform.save(journal)
        platform.maintenance(False)
        platform.smoke_public(target)
        journal['phase'] = 'done'
        platform.save(journal)
        return 'done'
    except Exception:
        # Unknown write outcome must be reconciled; never blindly overwrite current.
        platform.maintenance(True)
        raise ReleaseError('Operation incomplete; durable recovery required') from None


def recover(platform, journal):
    try:
        return _recover(platform, journal)
    except Exception:
        platform.maintenance(True)
        raise


def _recover(platform, journal):
    target = journal['request']['manifest']
    old = journal['oldState']['current']
    platform.maintenance(True)
    state, generation = platform.cloud.read('state.json')
    state = state or {'current': None, 'previous': None}
    if state == {'current': target, 'previous': old}:
        platform.prepare_database(target)
        history = platform.history(target)
        expected = version(journal['before']) if journal['request']['operation'] == 'rollback' else target['flyway']['after']
        require(version(history) == expected and history[:len(journal['before'])] == journal['before'], 'Committed schema diverged')
        platform.preflight(target, history, resume=True)
        platform.activate(target)
        platform.smoke(target)
        platform.maintenance(False)
        platform.smoke_public(target)
        journal['phase'] = 'done'
        platform.save(journal)
        return 'done'
    require(state == journal['oldState'] and generation == journal['generation'], 'External current changed; operator reconciliation required')
    platform.prepare_database(old or target)
    history = platform.history(old or target)
    require(old is not None and history == journal['before'] and version(history) in old['flyway']['rollbackCompatibleWith'],
            'Schema or initial release cannot be automatically restored')
    platform.preflight(old, history, resume=True)
    platform.activate(old)
    platform.smoke(old)
    platform.maintenance(False)
    platform.smoke_public(old)
    journal['phase'] = 'rolled-back'
    platform.save(journal)
    return 'rolled-back'
