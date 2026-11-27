"""Request-local observation of native dispatch and its eventual receipt."""
from contextlib import contextmanager
from contextvars import ContextVar

_observer = ContextVar('submission_dispatch_observer', default=None)


class DeferredSubmission:
    """The native request is in flight and its completion owns the receipt."""


@contextmanager
def observe_dispatch(submission_id, target, callback, *, deferred=None):
    token = _observer.set((submission_id, target, callback, deferred))
    try:
        yield
    finally:
        _observer.reset(token)


def defer_dispatch(submission_id, thread_id):
    """Capture completion callbacks before sending a long-running native request."""
    observer = _observer.get()
    if not observer or observer[:2] != (submission_id, thread_id) or observer[3] is None:
        raise RuntimeError('Deferred dispatch requires a matching input submission')
    return observer[3]


def dispatch_phase(submission_id, thread_id, phase, receipt=None):
    observer = _observer.get()
    if observer and observer[:2] == (submission_id, thread_id):
        observer[2](phase, receipt)


def creation_phase(target, phase, receipt=None):
    """Create/fork has no client message ID; use the request-local observer."""
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2](phase, receipt)


def creation_intent(target, session_id, project_id):
    """Persist a planned identity before create; it is not native acceptance."""
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2]('intent', {'kind': 'session-create', 'sessionId': session_id, 'projectId': project_id})


def local_commit(target, receipt):
    """Record a local mutation's receipt in the same SQLite transaction."""
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2]('committed', receipt)


def checkpoint_identity(target, receipt):
    observer = _observer.get()
    if observer and observer[1] == target:
        observer[2]('checkpoint', receipt)
