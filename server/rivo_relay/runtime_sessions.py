"""Core session boundary, independent of a native connection or writer model.

History remains an adapter-owned compatibility projection during migration.
Only the adapter can confirm current interaction authority; this service never
reconstructs pending requests from history or retries an uncertain response.
"""
from __future__ import annotations

from typing import Protocol, cast

from .runtime_capabilities import CapabilityError
from .submission_dispatch import DeferredSubmission


class InteractionReceiptError(RuntimeError):
    # The native side may already have consumed the answer. Do not retry it.
    code = 'interaction_receipt_mismatch'
    definite = False


class SessionBackend(Protocol):
    def list_sessions(self, project_id: str, **options) -> list[dict]: ...
    def create_session(self, project_id: str, **options) -> dict: ...
    def get_session(self, session_id: str, **options) -> dict: ...
    def history(self, session_id: str, **options) -> dict: ...
    def send(self, session_id: str, text: str, **options) -> dict | DeferredSubmission: ...
    def active_tasks(self) -> dict: ...


class ExecutionControlBackend(Protocol):
    def interrupt(self, session_id: str, *, turn_id: str) -> dict: ...


class InteractionBackend(Protocol):
    def respond_interaction(self, session_id: str, generation: str,
                            interaction_id: str, revision: str, response: dict) -> dict: ...


class SessionService:
    def __init__(self, backend: SessionBackend, capabilities, queue):
        self._backend = backend
        self._capabilities = capabilities
        self._queue = queue

    def list(self, project_id, **options):
        return self._backend.list_sessions(project_id, **options)

    def create(self, project_id, **options):
        return self._backend.create_session(project_id, **options)

    def read(self, session_id, **options):
        # get_session captures state + interactions in the adapter's control cut.
        # Do not append a generation obtained from a later status/read call.
        snapshot = self._backend.get_session(session_id, **options)
        return {**snapshot, 'capabilities': self._capabilities(snapshot=snapshot),
                'queue': self._queue.list(session_id)}

    def history(self, session_id, **options):
        return self._backend.history(session_id, **options)

    def send(self, session_id, text, **options):
        return self._backend.send(session_id, text, **options)

    def interrupt(self, session_id, execution_id):
        # The existing HTTP turnId spelling is retained at the compatibility edge.
        return cast(ExecutionControlBackend, self._backend).interrupt(session_id, turn_id=execution_id)

    def activity(self):
        return self._backend.active_tasks()

    def respond(self, session_id, generation, interaction_id, revision, response):
        provider = getattr(self._backend, 'respond_interaction', None)
        if provider is None:
            raise CapabilityError('interaction.respond')
        receipt = provider(session_id, generation, interaction_id, revision, response)
        expected = {'threadId': session_id, 'runtimeGeneration': generation,
                    'interactionId': interaction_id, 'revision': revision}
        if not isinstance(receipt, dict) or any(receipt.get(key) != value for key, value in expected.items()):
            raise InteractionReceiptError('Interaction response identity was not confirmed')
        return receipt

    def respond_legacy(self, key, response):
        # Closed migration endpoint for old APPs. New clients send scoped identity.
        provider = getattr(self._backend, 'respond', None)
        if provider is None:
            raise CapabilityError('interaction.respond')
        return provider(key, response)
