"""Small, credential-free operational events shared by node and gateway."""
import datetime
import json
import sys
import uuid
from contextlib import contextmanager
from contextvars import ContextVar

_request_trace = ContextVar('request_trace', default=None)

# Only application-defined categories; never log arbitrary upstream error text/codes.
_ERROR_CODES = frozenset('''read_deadline native_timeout native_unavailable native_rejected
native_history_unavailable native_response_too_large native_protocol native_method_unsupported node_updating
workspace_busy workspace_changed workspace_dirty thread_busy writer_unavailable
writer_still_held handoff_changed handoff_host_changed handoff_host_mismatch
handoff_host_unverified native_host_unverified interaction_expired interaction_changed
history_changed project_trust_unconfirmed not_managed_worktree invalid_worktree_ref
git_operation_failed not_git_repository invalid_project review_fingerprint_unavailable
unauthorized invalid_request forbidden'''.split())


def error_code(value):
    return value if isinstance(value, str) and value in _ERROR_CODES else 'other'


@contextmanager
def request_trace(value):
    token = _request_trace.set(request_id(value))
    try:
        yield
    finally:
        _request_trace.reset(token)


def route_kind(path):
    """Allowlisted categories only; never include identifiers or query values."""
    parts = path.split('?', 1)[0].strip('/').split('/')
    if parts == ['health']:
        return 'health'
    if len(parts) >= 2 and parts[0] == 'v1' and parts[1] in {
        'threads', 'projects', 'events', 'files', 'media', 'app-updates',
        'download-links', 'todos', 'submissions', 'models', 'usage', 'speech', 'pair',
    }:
        return parts[1]
    return 'other'


def request_id(value=None):
    # Only accept our opaque hex identifier; never echo arbitrary header values.
    if isinstance(value, str) and len(value) == 32 and all(c in "0123456789abcdef" for c in value):
        return value
    return uuid.uuid4().hex


def event(name, **fields):
    if _request_trace.get() is not None:
        fields.setdefault('request_id', _request_trace.get())
    record = {"time": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "event": name, **fields}
    try:
        print(json.dumps(record, separators=(",", ":")), file=sys.stderr, flush=True)
    except OSError:
        pass  # Logging failures must not break a request.
