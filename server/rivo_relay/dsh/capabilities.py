"""Only operations implemented by the existing-runtime DSH adapter."""
from ..runtime_capabilities import declare, session_restrictions

SUPPORTED = frozenset({'session.create', 'session.send', 'session.interrupt', 'interaction.respond'})


def capabilities(*, connected, snapshot=None):
    return declare(SUPPORTED, connected=connected, unavailable=session_restrictions(snapshot))
