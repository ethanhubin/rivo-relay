"""Small, credential-free operational events shared by node and gateway."""
import datetime
import json
import sys
import uuid


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
    record = {"time": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "event": name, **fields}
    try:
        print(json.dumps(record, separators=(",", ":")), file=sys.stderr, flush=True)
    except OSError:
        pass  # Logging failures must not break a request.
