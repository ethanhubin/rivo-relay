"""Small, credential-free operational events shared by node and gateway."""
import datetime
import json
import sys
import uuid


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
