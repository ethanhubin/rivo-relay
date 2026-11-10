"""Request-local observation of the actual turn/start boundary, not resume RPCs."""
from contextlib import contextmanager
from contextvars import ContextVar

_observer = ContextVar('submission_dispatch_observer', default=None)


@contextmanager
def observe_dispatch(submission_id, target, callback):
    token = _observer.set((submission_id, target, callback))
    try:
        yield
    finally:
        _observer.reset(token)


def dispatch_phase(submission_id, thread_id, phase, receipt=None):
    observer = _observer.get()
    if observer and observer[:2] == (submission_id, thread_id):
        observer[2](phase, receipt)


def creation_phase(target, phase, receipt=None):
    """Create/fork has no client message ID; use the request-local observer."""
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2](phase, receipt)


def local_commit(target, receipt):
    """Record a local mutation's receipt in the same SQLite transaction."""
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2]('committed', receipt)


def checkpoint_identity(target, receipt):
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2]('checkpoint', receipt)
