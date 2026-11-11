"""Codex's implemented operations; native control remains authoritative."""
from ..runtime_capabilities import declare, session_restrictions, history_usable

SUPPORTED = frozenset({
    'project.create', 'project.browse', 'project.git', 'session.create', 'session.send',
    'session.interrupt', 'session.continue', 'session.steer', 'session.fork', 'session.pin',
    'session.archive', 'session.delete', 'session.settings', 'session.models', 'session.goal',
    'session.queue', 'session.ownership', 'session.review', 'session.prediction', 'session.plan',
    'files.read', 'files.upload', 'files.browse', 'attachments.images', 'workspace.manage',
    'interaction.respond', 'todo.dispatch', 'sites.read',
})


def capabilities(*, connected, snapshot=None):
    unavailable = session_restrictions(snapshot)
    if snapshot:
        control = snapshot.get('control') or {}
        if control.get('state') == 'released' and history_usable(snapshot):
            for name in ('session.send', 'session.continue'):
                unavailable.pop(name, None)
        # Native questions can remain answerable through the bound desktop
        # connection while that external writer owns execution. The request's
        # generation/key is still validated by Interactions.respond.
        if (history_usable(snapshot)
                and (snapshot.get('canAnswerQuestions') is True
                     or any('requestUserInput' in str(request.get('method', ''))
                            for request in snapshot.get('requests', [])))):
            unavailable.pop('interaction.respond', None)
        if snapshot.get('goalSupported') is False:
            unavailable['session.goal'] = 'native_feature_unavailable'
        runtime = snapshot.get('nativeRuntime') or {}
        # A failed cached host check must not hide an external writer's fresh
        # takeover preview. The preview still verifies its host/process scope;
        # this only advertises rechecking, never permission to terminate it.
        recheck_external = (control.get('state') == 'external' and runtime.get('connected') is True
                            and runtime.get('runtime') != 'desktop' and runtime.get('desktopShared') is not True)
        if control and not control.get('action') and not recheck_external:
            unavailable['session.ownership'] = 'ownership_unavailable'
    return declare(SUPPORTED, connected=connected, unavailable=unavailable)
