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
