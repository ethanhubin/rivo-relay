"""Sanitized update diagnostics and live connector readiness."""
import json
import time
from pathlib import Path
from .config import save_private

AGENT_REVISION = 2


def write_ready(path, token, connected):
    save_private(Path(path), {'token': token, 'connected': bool(connected), 'updatedAt': time.time()})


def is_ready(path, token):
    try:
        value = json.loads(Path(path).read_text())
        return (value.get('token') == token and value.get('connected') is True
                and 0 <= time.time() - value.get('updatedAt', 0) < 15)
    except (OSError, ValueError, TypeError):
        return False


def public_status(workspace):
    try:
        value = json.loads((Path(workspace) / 'auto-update/status.json').read_text())
        allowed = ('agentRevision', 'phase', 'activeCode', 'latestCode', 'reason',
                   'attempts', 'retryAt', 'updatedAt')
        result = {k: value[k] for k in allowed if k in value}
        result['stale'] = time.time() - value.get('updatedAt', 0) > 180
        return result
    except (OSError, ValueError, TypeError):
        return {'phase': 'unknown', 'reason': 'agent_status_unavailable', 'stale': True}
