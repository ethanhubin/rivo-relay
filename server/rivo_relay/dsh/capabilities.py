"""Only operations implemented by the existing-runtime DSH adapter."""
from ..runtime_capabilities import declare, session_restrictions
from .uploads import MAX_FILE_BYTES

SUPPORTED = frozenset({'session.create', 'session.send', 'session.interrupt', 'interaction.respond'})
OPTIONAL = frozenset({'media.read', 'session.models', 'session.settings', 'attachments.images', 'files.upload', 'session.plan'})


def capabilities(*, connected, snapshot=None, features=()):
    declaration = declare(SUPPORTED | (OPTIONAL & set(features)), connected=connected,
                          unavailable=session_restrictions(snapshot))
    declaration['limits'] = {'maxAttachmentBytes': MAX_FILE_BYTES}
    return declaration
