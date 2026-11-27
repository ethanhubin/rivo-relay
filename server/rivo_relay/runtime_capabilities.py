"""Versioned operation declarations shared by presentation and route admission.

Declarations never grant native authority: adapters still validate ownership,
approval lifetime and execution identity at their actual mutation boundary.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

OPERATIONS = frozenset({
    'project.create', 'project.browse', 'project.git',
    'session.create', 'session.send', 'session.interrupt', 'session.continue',
    'session.steer', 'session.fork', 'session.pin', 'session.archive',
    'session.delete', 'session.settings', 'session.models', 'session.goal',
    'session.queue', 'session.ownership', 'session.review', 'session.prediction',
    'session.plan', 'files.read', 'files.upload', 'files.browse',
    'attachments.images', 'media.read', 'session.service_tier', 'session.permissions',
    'workspace.manage', 'interaction.respond', 'interaction.respond_while_input_pending',
    'todo.dispatch', 'sites.read', 'usage.reset',
})


class CapabilityProvider(Protocol):
    def runtime_capabilities(self, *, snapshot: dict | None = None,
                             connected: bool | None = None) -> dict: ...


@dataclass(frozen=True)
class Capability:
    supported: bool
    available: bool
    reason_code: str | None = None

    def wire(self):
        return {'supported': self.supported, 'available': self.available,
                **({'reasonCode': self.reason_code} if self.reason_code else {})}


def declare(supported, *, connected=True, unavailable=None):
    """Unknown/new operations are closed until an adapter explicitly implements them."""
    unavailable = unavailable or {}
    return {'schemaVersion': 1, 'operations': {
        name: Capability(name in supported, name in supported and connected and name not in unavailable,
                         'runtime_unsupported' if name not in supported else
                         'runtime_disconnected' if not connected else unavailable.get(name)).wire()
        for name in sorted(OPERATIONS)}}


def session_restrictions(snapshot):
    if not snapshot:
        return {}
    control = snapshot.get('control') or {}
    # A released Codex writer can be reacquired by sending; its adapter supplies
    # that exception. This common projection otherwise fails closed for writes.
    blocked = snapshot.get('readOnly') or control.get('canOperate') is False
    stale = not history_usable(snapshot)
    guarded = {'session.send', 'session.interrupt', 'session.continue', 'session.steer',
               'session.settings', 'session.goal', 'session.review', 'session.prediction',
               'session.plan', 'session.service_tier', 'session.permissions', 'interaction.respond',
               'interaction.respond_while_input_pending'}
    reason = 'session_stale' if stale else 'session_read_only' if blocked else None
    return {name: reason for name in guarded} if reason else {}


def history_usable(snapshot):
    sync = snapshot.get('nativeSync') or {}
    # Live control and pending approvals can be current while the journal is
    # converging on a verified persisted prefix. Never infer lost authority just
    # because live output has not reached its final durable history cut yet.
    return sync.get('state', 'ready') == 'ready' or (
        sync.get('state') == 'converging' and sync.get('historyReady') is True)


class CapabilityError(Exception):
    status = http_status = 409
    definite = True

    def __init__(self, operation, *, supported=False, reason=None):
        self.code = 'runtime_unavailable' if supported else 'runtime_unsupported'
        self.operation = operation
        self.reason = reason
        super().__init__('此运行时暂不支持该操作' if not supported else '当前无法执行此操作，请同步后重试')


def require(declaration, operation):
    if declaration.get('schemaVersion') != 1:
        raise CapabilityError(operation)
    descriptor = (declaration.get('operations') or {}).get(operation) or {}
    supported = descriptor.get('supported') is True
    if not supported or descriptor.get('available') is not True:
        raise CapabilityError(operation, supported=supported, reason=descriptor.get('reasonCode'))


def submission_requirements(operation, payload):
    mapping = {
        'project.add': 'project.create', 'thread.create': 'session.create',
        'thread.turns': 'session.send', 'thread.continue': 'session.continue',
        'thread.interrupt': 'session.interrupt', 'thread.steer': 'session.steer',
        'thread.fork': 'session.fork', 'thread.pin': 'session.pin',
        'thread.archive': 'session.archive', 'thread.delete': 'session.delete',
        'thread.settings': 'session.settings', 'thread.goal': 'session.goal',
        'thread.ownership': 'session.ownership', 'thread.review': 'session.review',
        'thread.answers': 'interaction.respond', 'request.respond': 'interaction.respond',
        'interaction.respond': 'interaction.respond', 'usage.reset': 'usage.reset',
        'workspace.cleanup': 'workspace.manage', 'todo.dispatch': 'todo.dispatch',
    }
    result = set()
    if operation in mapping:
        result.add(mapping[operation])
    if operation in {'thread.queue.enqueue', 'thread.queue.reorder', 'thread.queue.edit',
                     'thread.queue.remove', 'thread.queue.steer', 'thread.queue.recover', 'thread.queue.dismiss'}:
        result.add('session.queue')
        if operation == 'thread.queue.steer':
            result.add('session.steer')
    if not result:
        raise CapabilityError(operation)
    if operation == 'todo.dispatch':
        result.add('session.send')
        if not payload.get('threadId'):
            result.add('session.create')
        if payload.get('uploadedAttachments') or (payload.get('details') or {}).get('attachments'):
            result.add('files.upload')
    if operation in {'thread.turns', 'thread.steer', 'thread.queue.enqueue', 'thread.create', 'thread.settings'}:
        if any(payload.get(key) is not None for key in ('model', 'effort')):
            result.add('session.models')
        if payload.get('serviceTier') is not None:
            result.add('session.service_tier')
        if any(payload.get(key) is not None for key in ('sandbox', 'approvalPolicy')):
            result.add('session.permissions')
        if payload.get('mode') == 'plan':
            result.add('session.plan')
        if payload.get('requireGoalSupport'):
            result.add('session.goal')
        for item in payload.get('input') or []:
            if isinstance(item, dict):
                if item.get('type') in ('image', 'localImage'):
                    result.add('attachments.images')
                elif item.get('type') != 'text':
                    result.add('files.upload')
    if payload.get('worktreeRef'):
        result.add('workspace.manage')
    return result


def read_requirement(method, path):
    if len(path) >= 3 and path[:2] == ['v1', 'media'] and path[2] != 'capabilities':
        return 'media.read'
    if method == 'GET':
        exact = {
            ('v1', 'project-directories'): 'project.browse',
            ('v1', 'directories'): 'files.browse', ('v1', 'files'): 'files.read',
            ('v1', 'files', 'download'): 'files.read', ('v1', 'sites'): 'sites.read',
        }
        if tuple(path) in exact:
            return exact[tuple(path)]
        if len(path) == 4 and path[:2] == ['v1', 'projects'] and path[3] == 'git':
            return 'project.git'
        if len(path) == 4 and path[:2] == ['v1', 'threads']:
            return {'ownership': 'session.ownership', 'fork-context': 'session.fork',
                    'diff': 'session.review', 'review-context': 'session.review',
                    'workspace': 'workspace.manage'}.get(path[3])
    if method == 'POST':
        if path == ['v1', 'download-links']:
            return 'files.read'
        if path == ['v1', 'todos', 'attachments'] or (
                len(path) == 4 and path[:2] in (['v1', 'threads'], ['v1', 'projects']) and path[3] == 'attachments'):
            return 'files.upload'
        if len(path) == 4 and path[:2] == ['v1', 'threads'] and path[3] == 'prediction':
            return 'session.prediction'
    return None
