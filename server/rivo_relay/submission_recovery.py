"""Offline, evidence-reviewed recovery. Never retries or infers failure from age."""
from contextlib import contextmanager, ExitStack, closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

from .codex import native_history_support as locks
from .database import Database
from .submissions import SubmissionStore, SubmissionConflict, _merge_receipt


def fingerprint(record):
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def inspect(workspace, submission_id):
    path = Path(workspace) / 'data/relay.sqlite3'
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        row = db.execute('SELECT * FROM submissions WHERE id=?', (submission_id,)).fetchone()
        if row is None:
            raise ValueError('Submission not found')
        record = dict(row)
    return {'record': record, 'fingerprint': fingerprint(record)}


@contextmanager
def stopped(workspace):
    with ExitStack() as stack:
        held = []
        try:
            for relative in ('auto-update/agent.lock', 'data/serve.lock'):
                path = Path(workspace) / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                handle = stack.enter_context(path.open('a'))
                locks.flock(handle, locks.LOCK_EX | locks.LOCK_NB)
                held.append(handle)
            yield
        finally:
            for handle in reversed(held):
                locks.flock(handle, locks.LOCK_UN)


def recover(workspace, submission_id, evidence, *, apply=False):
    """Operator attests the meaning of evidence; the tool verifies scope and freshness.

    Evidence is not an automatic proof of non-execution. Legacy negative findings
    require explicit human review of dispatch/process logs, not an empty history.
    """
    workspace = Path(workspace)
    with stopped(workspace):
        before = inspect(workspace, submission_id)
        row = before['record']
        if evidence.get('expectedFingerprint') != before['fingerprint']:
            raise SubmissionConflict('Submission changed; inspect and review evidence again')
        if row['status'] != 'unknown' or row['operation'] not in {'thread.turns', 'thread.continue'}:
            raise SubmissionConflict('Only unknown turn submissions can be recovered here')
        decision = evidence.get('decision')
        if decision not in {'not_dispatched', 'accepted'}:
            raise ValueError('Specify not_dispatched or accepted')
        if not isinstance(evidence.get('reviewedBy'), str) or not evidence['reviewedBy'].strip():
            raise ValueError('An evidence reviewer is required')
        if not isinstance(evidence.get('reason'), str) or not evidence['reason'].strip():
            raise ValueError('Explain the evidence, including request correlation')
        files = evidence.get('files')
        if not isinstance(files, list) or not 1 <= len(files) <= 8:
            raise ValueError('Provide one to eight reviewed evidence files')
        copied = []
        for source in files:
            path = Path(source['path'])
            if path.stat().st_size > 16 * 1024 * 1024:
                raise ValueError('Use a bounded, redacted evidence excerpt')
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != source.get('sha256'):
                raise ValueError('Evidence file changed')
            copied.append(data)
        receipt = evidence.get('receipt')
        if decision == 'not_dispatched':
            if evidence.get('dispatchNotSentVerified') is not True:
                raise ValueError('Verify no turn dispatch; absence from history is insufficient')
            if row.get('dispatch_phase') not in (None, 'preparing') or row['receipt_json']:
                raise SubmissionConflict('Recorded dispatch/receipt contradicts not_dispatched')
            if receipt is not None:
                raise ValueError('Not-dispatched recovery cannot have a receipt')
        else:
            if (not isinstance(receipt, dict) or set(receipt) != {'threadId', 'turnId'}
                    or receipt['threadId'] != json.loads(row['target_json'])
                    or not isinstance(receipt['turnId'], str) or not receipt['turnId']
                    or len(receipt['turnId']) > 2048):
                raise ValueError('Provide the exact native thread/turn receipt')
            _merge_receipt(json.loads(row['receipt_json']) if row['receipt_json'] else None, receipt)
        result = {'submissionId': submission_id, 'decision': decision, 'before': before,
                  'evidence': evidence, 'applied': False}
        if not apply:
            return result
        artifact = workspace / 'artifacts' / ('submission-recovery-' + uuid.uuid4().hex)
        artifact.mkdir(parents=True, mode=0o700)
        # Backup before opening Database (which may add compatible columns).
        with closing(sqlite3.connect((workspace / 'data/relay.sqlite3').resolve().as_uri() + '?mode=ro', uri=True)) as source:
            backup = artifact / 'relay-before.sqlite3'
            fd = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600); os.close(fd)
            with closing(sqlite3.connect(backup)) as target:
                source.backup(target)
        for i, data in enumerate(copied):
            (artifact / f'evidence-{i}.txt').write_bytes(data)
        (artifact / 'review.json').write_text(json.dumps(result, indent=2))
        db = Database(workspace / 'data/relay.sqlite3')
        try:
            store = SubmissionStore(db)
            with db.transaction() as connection:
                if decision == 'accepted':
                    after = store.mark_accepted(submission_id, receipt)
                else:
                    after = store.mark_failed(submission_id, 'reviewed_turn_not_dispatched')
                result.update(applied=True, after=after, recoveredAt=time.time(), backup=str(backup))
                connection.execute('UPDATE submissions SET recovery_json=? WHERE id=?',
                                   (json.dumps(result), submission_id))
            (artifact / 'result.json').write_text(json.dumps(result, indent=2))
        finally:
            db.close()
        return result
