"""Small backend-neutral input receipt contract and immutable identity merging.

Receipts attest acceptance, never execution completion. Legacy identity-only
records remain readable and are not rewritten merely because they were read.
"""
from __future__ import annotations
from dataclasses import dataclass


class ReceiptConflict(ValueError):
    pass


_IDENTITIES = {
    alias: name for name in ('thread', 'turn', 'item', 'recoveredItem', 'request',
                            'project', 'runtime', 'connection', 'todo', 'submission', 'execution', 'interaction')
    for alias in (name + 'Id', name + '_id')
}
_KINDS = {'input-accepted', 'execution-started', 'mutation-applied'}
_SESSION_INPUTS = {'thread.turns', 'thread.continue', 'thread.answers', 'thread.queue.steer'}


def identity(receipt, name):
    return receipt.get(name + 'Id', receipt.get(name + '_id'))


def merge_receipts(existing, incoming):
    """Merge only bounded identities, preserving every recorded identity."""
    if incoming is not None and (not isinstance(incoming, dict) or not incoming):
        raise ValueError('A receipt with identity fields is required')
    merged = dict(existing or {})
    identities = {}
    for key, value in [*merged.items(), *(incoming or {}).items()]:
        field = _IDENTITIES.get(key, 'metadata:' + key if key in ('kind', 'runtime', 'acceptance', 'runtimeGeneration', 'revision') else None)
        if field is None or not isinstance(value, str) or not value or len(value) > 2048:
            raise ValueError('Receipts must contain only bounded nonempty identity fields')
        if field in identities and identities[field] != value:
            raise ReceiptConflict('A recorded receipt identity cannot be replaced')
        identities[field] = value
    merged.update(incoming or {})
    if 'kind' in merged:
        if merged['kind'] not in _KINDS:
            raise ValueError('Unknown receipt kind')
        if not merged.get('submissionId'):
            raise ValueError('Neutral receipts require submissionId')
        if merged['kind'] in ('input-accepted', 'execution-started') and not merged.get('threadId'):
            raise ValueError('Input receipts require threadId')
        if merged['kind'] == 'execution-started' and not merged.get('executionId'):
            raise ValueError('Execution receipts require a native executionId')
        if merged['kind'] == 'mutation-applied' and not any(
                key in _IDENTITIES and _IDENTITIES[key] != 'submission' for key in merged):
            raise ValueError('Mutation receipts require an affected resource identity')
        if merged['kind'] == 'input-accepted' and (identity(merged, 'turn') or identity(merged, 'execution')):
            raise ValueError('Input acceptance does not prove an execution identity')
        if identity(merged, 'turn') and identity(merged, 'execution') != identity(merged, 'turn'):
            raise ReceiptConflict('Legacy turnId must match executionId')
    return merged or None


@dataclass(frozen=True)
class InputReceipt:
    kind: str
    submission_id: str
    thread_id: str
    execution_id: str | None = None


def decode_input_receipt(receipt, submission_id):
    receipt = merge_receipts(None, receipt)
    if not receipt:
        return None
    if identity(receipt, 'submission') not in (None, submission_id):
        raise ReceiptConflict('Receipt belongs to another submission')
    if 'kind' not in receipt:
        # This is a closed compatibility registry, not a runtime dispatch hook.
        from .receipt_compat import decode_legacy_input
        return decode_legacy_input(receipt, submission_id)
    if receipt['submissionId'] != submission_id:
        raise ReceiptConflict('Receipt belongs to another submission')
    if receipt['kind'] == 'mutation-applied':
        return None
    return InputReceipt(receipt['kind'], submission_id, receipt['threadId'], receipt.get('executionId'))


def validate_receipt(receipt, submission_id, operation, target, *, accepted=False):
    """Validate correlation without knowing any backend's protocol or history."""
    if not receipt:
        return None
    decoded = decode_input_receipt(receipt, submission_id)
    if operation == 'interaction.respond' and accepted:
        if (receipt.get('kind') != 'mutation-applied' or receipt.get('threadId') != target
                or not all(receipt.get(key) for key in ('submissionId', 'interactionId', 'runtimeGeneration', 'revision'))):
            raise ReceiptConflict('Interaction receipt does not match the submitted response')
    if decoded:
        if decoded.kind == 'input-accepted' and operation != 'thread.turns':
            raise ReceiptConflict('This operation requires execution acceptance')
        if operation in _SESSION_INPUTS and decoded.thread_id != target:
            raise ReceiptConflict('Receipt belongs to another session')
    elif accepted and operation in _SESSION_INPUTS:
        raise ReceiptConflict('Input acceptance requires correlated backend evidence')
    return decoded


def dispatch_receipt_matches(receipt, submission_id, operation, target):
    decoded = validate_receipt(receipt, submission_id, operation, target, accepted=True)
    if operation in ('thread.create', 'thread.fork'):
        return bool(receipt and identity(receipt, 'thread'))
    return decoded is not None


def input_accepted(submission_id, thread_id, **legacy):
    receipt = {'kind': 'input-accepted', 'submissionId': submission_id, 'threadId': thread_id}
    return merge_receipts(receipt, legacy or None)


def execution_started(submission_id, thread_id, execution_id, **legacy):
    receipt = {'kind': 'execution-started', 'submissionId': submission_id,
               'threadId': thread_id, 'executionId': execution_id}
    return merge_receipts(receipt, legacy or None)
