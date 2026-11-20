"""Closed decoders for receipts persisted before the neutral contract.

New adapters emit neutral receipts and do not register product branches here.
"""


def decode_legacy_input(receipt, submission_id):
    from .codex.receipts import decode_legacy as codex_legacy
    from .dsh.receipts import decode_legacy as dsh_legacy
    for decode in (dsh_legacy, codex_legacy):
        result = decode(receipt, submission_id)
        if result is not None:
            return result
    return None
