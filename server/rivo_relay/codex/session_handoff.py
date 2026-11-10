"""Explicit session ownership handoff, sharing CodexService state and gates.

This mixin owns no separate state: locks, preview tickets, live execution state,
observations and runtime collaborators are initialized by CodexService. Keeping
one owner preserves the ordering between handoff, queue dispatch and events.
"""
from __future__ import annotations

from collections import OrderedDict
from contextlib import nullcontext
import copy
import logging
import secrets
import time

from ..observability import event, request_id as new_request_id
from .connection import CodexError


class SessionHandoffMixin:
    def _handoff_connection(self, thread_id):
        connection = self._thread_connection(thread_id)
        status = connection.status()
        if status.get('runtime') == 'desktop' or status.get('desktopShared') is True:
            raise CodexError('共享 Desktop 会话请使用原生停止或释放；不能强制结束共享 Desktop 进程',
                             code='handoff_desktop_shared', http_status=409)
        probe = getattr(connection, 'native_host_info', None)
        if not callable(probe):
            raise CodexError('无法核对所选原生主机，不能执行进程级交接', code='handoff_host_unverified', http_status=409)
        generation = str(connection.generation)
        descriptor = probe()
        if generation != str(connection.generation) or str(descriptor.get('generation')) != generation:
            raise CodexError('原生运行时已变化，请重新预览交接', code='handoff_host_changed', http_status=409)
        self.writers.verify_native_host(descriptor, status.get('runtimeId'), getattr(connection, 'socket_path', None))
        return connection, descriptor

    def _verify_release_history(self, thread_id):
        with self._lock:
            materializing = thread_id in self._unmaterialized or thread_id in self._created or thread_id in self._materializing
        verify = getattr(self.connection, 'verify_release_history', None)
        if materializing and verify:
            try:
                verify(thread_id)
                with self._lock:
                    self._unmaterialized.discard(thread_id)
            except CodexError as error:
                raise CodexError('会话尚未形成可恢复的原生历史，暂不能关闭 writer；当前会话已保留，请在首条消息持久化后重试',
                                 code='thread_unmaterialized', http_status=409) from error

    def preview_handoff(self, thread_id, action, owner):
        self.start()
        with self._lock:
            joined = thread_id in self._joined
        connection = self._thread_connection(thread_id)
        status = connection.status()
        shared_desktop = status.get('runtime') == 'desktop' or status.get('desktopShared') is True
        # Unsubscribing a daemon connection does not relinquish its writer lock.
        # Only shared Desktop uses subscription release; local daemons must go
        # through verified process handoff with the complete impact preview.
        owned = getattr(self.connection, 'owns_writer', lambda _: False)(thread_id)
        if action == 'release' and owned:
            self._verify_release_history(thread_id)
        if action == 'resume' or (action == 'release' and (owned or (joined and shared_desktop))):
            ticket = secrets.token_urlsafe(32)
            with self._lock:
                self._subscription_releases[ticket] = {'threadId': thread_id, 'owner': owner, 'operation': action,
                    'generation': str(connection.generation), 'connection': connection,
                    'expires': time.monotonic() + 120}
                while len(self._subscription_releases) > 128:
                    self._subscription_releases.popitem(last=False)
            return {'ticket': ticket, 'threadId': thread_id, 'operation': action,
                    'releaseScope': 'session-writer' if owned else 'relay-subscription', 'requiresConfirmation': False,
                    'affectedThreadIds': [thread_id], 'affectedThreads': [{'id': thread_id, 'title': thread_id}],
                    'processIds': [], 'childProcessCount': 0, 'workspaceRebind': None}
        connection, descriptor = self._handoff_connection(thread_id)
        thread = self.connection.request("thread/read", {"threadId": thread_id, "includeTurns": False})['thread']
        rebind = self._workspace_rebind(thread, require_target=True) if action == 'takeover' else None
        preview = self.writers.preview(thread_id, owner, action)
        preview['workspaceRebind'] = rebind
        if action == 'release':
            preview['releaseScope'] = 'writer-process'
            preview['requiresConfirmation'] = True
        if rebind:
            preview['requiresConfirmation'] = True
        with self._lock:
            self._handoff_workspaces[preview['ticket']] = copy.deepcopy(rebind)
            self._handoff_hosts[preview['ticket']] = copy.deepcopy(descriptor)
            while len(self._handoff_workspaces) > 128:
                self._handoff_workspaces.popitem(last=False)
            while len(self._handoff_hosts) > 128:
                self._handoff_hosts.popitem(last=False)
        threads = []
        for affected in preview['affectedThreadIds']:
            try:
                thread = self.connection.request('thread/read', {'threadId': affected, 'includeTurns': False})['thread']
                title = thread.get('name') or thread.get('preview') or affected
            except CodexError:
                title = affected
            threads.append({'id': affected, 'title': str(title)[:160]})
        preview['affectedThreads'] = threads
        return preview

    def _release_subscription(self, thread_id, owner, ticket):
        with self._lock:
            preview = self._subscription_releases.pop(ticket, None)
        if preview is None:
            return None
        connection = self._thread_connection(thread_id)
        if (preview.get('operation', 'release') != 'release' or preview['threadId'] != thread_id or preview['owner'] != owner
                or preview['connection'] is not connection or preview['generation'] != str(connection.generation)
                or preview['expires'] < time.monotonic()):
            raise CodexError('释放订阅预览已失效，请重新操作', code='handoff_changed', http_status=409)
        if getattr(self.connection, 'owns_writer', lambda _: False)(thread_id):
            self._verify_release_history(thread_id)
            if self.observations:
                self.observations.put(thread_id, 'ownership', '', {'released': True, 'releaseState': 'pending'})
            self._observe(thread_id, 'ownership', '', {'released': True, 'releaseState': 'pending'})
            # Stop only this writer's active turn, then close its input/process.
            # Interruption rejection/timeout cannot suppress an explicit release.
            with self._lock:
                active = self._active.get(thread_id)
            if active:
                try:
                    connection.request('turn/interrupt', {'threadId': thread_id, 'turnId': active}, timeout=3)
                except CodexError:
                    pass
            try:
                self.connection.release_writer(thread_id, connection)
            except Exception as error:
                self._observe(thread_id, 'ownership', '', {'released': True, 'releaseState': 'failed', 'releaseError': str(error)})
                raise
            self.interactions.discard_thread(thread_id)
            with self._lock:
                self._joined.discard(thread_id)
                self._created.pop(thread_id, None)
                self._materializing.pop(thread_id, None)
                self._settings.pop(thread_id, None)
                self._active.pop(thread_id, None)
                self._live = OrderedDict((key, value) for key, value in self._live.items() if key[0] != thread_id)
                self._pending_terminal = {key for key in self._pending_terminal if key[0] != thread_id}
            self._observe(thread_id, 'ownership', '', {'released': True, 'releaseState': 'released'})
            self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
            return {'threadId': thread_id, 'released': True, 'releaseScope': 'session-writer', 'affectedThreadIds': [thread_id]}
        # Freeze automatic queue/resume before dispatch. An uncertain response
        # must keep this intent rather than reacquiring the writer automatically.
        self._observe(thread_id, 'ownership', '', {'released': True})
        try:
            self.connection.request('thread/unsubscribe', {'threadId': thread_id})
            if str(connection.generation) != preview['generation']:
                raise CodexError('原生连接已变化，释放结果待核对', code='handoff_changed', http_status=409, request_sent=True)
        except CodexError as error:
            if error.definite:
                self._forget_observation(thread_id, 'ownership')
            raise
        with self._lock:
            self._joined.discard(thread_id)
            self._created.pop(thread_id, None)
            self._materializing.pop(thread_id, None)
            self._settings.pop(thread_id, None)
        self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
        return {'threadId': thread_id, 'released': True, 'releaseScope': 'relay-subscription'}

    def _workspace_rebind(self, thread, *, require_target=False):
        """Offer only the original native project's root for a missing cwd."""
        path = thread.get('cwd')
        if not isinstance(path, str) or not path:
            return None
        try:
            # Any existing entry, including a file/symlink, is not a removed cwd.
            self.connection.request('fs/getMetadata', {'path': path})
            return None
        except CodexError as error:
            if error.code != 'native_rejected' or not error.definite or not any(
                    token in str(error).lower() for token in ('no such file', 'does not exist', 'not found')):
                raise
        managed = self.workspaces.row(thread.get('id')) if self.workspaces else None
        previous = self.observations_for(thread.get('id')).get(('externalWorkspace', ''))
        association = previous if previous and previous.get('path') == path else None
        project_id = thread.get('projectId') or (managed.get('project_id') if managed else None) or (association or {}).get('projectId')
        if not project_id:
            if require_target:
                raise CodexError('原工作目录已不存在，无法确认来源项目', code='workspace_unavailable', http_status=409)
            return None
        project = self.projects.get(project_id)
        roots = [root['path'] for root in project.get('roots', []) if isinstance(root, dict) and root.get('path')]
        if association and association.get('projectRoot') not in roots:
            raise CodexError('原项目目录已变化，不能重绑工作目录', code='workspace_unavailable', http_status=409)
        if len(roots) != 1 or roots[0] == path:
            if require_target:
                raise CodexError('原工作目录已不存在，无法确定唯一来源目录', code='workspace_unavailable', http_status=409)
            return None
        metadata = self.connection.request('fs/getMetadata', {'path': roots[0]})
        if metadata.get('isDirectory') is not True:
            if require_target:
                raise CodexError('来源项目目录已不可用', code='workspace_unavailable', http_status=409)
            return None
        return {'path': roots[0], 'missingPath': path}

    def _release_process_writer(self, thread_id, snapshot, affected):
        # The released bit is the persistent queue/resume fence, not proof of unlock.
        def mark(phase, error=None):
            for target in affected:
                payload = {'released': True, 'releaseState': phase}
                if error:
                    payload['releaseError'] = str(error)
                if self.observations:
                    self.observations.put(target, 'ownership', '', payload)
                self._observe(target, 'ownership', '', payload)
                self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': target}})
        mark('pending')
        try:
            for target in affected:
                with self._lock:
                    active = self._active.get(target) if target in self._joined else None
                if active:
                    try:
                        self.connection.request('turn/interrupt', {'threadId': target, 'turnId': active}, timeout=3)
                    except CodexError:
                        # A wedged RPC must not block the already confirmed process fallback.
                        pass
            self.writers.terminate(snapshot)
            if any(self.writers.busy(target) for target in affected):
                raise CodexError('仍有受影响会话的写锁未释放，请重新预览释放', code='writer_still_held', http_status=409)
            # Releasing never needs to restart/resume the daemon. Desktop can acquire now.
            for target in affected:
                with self._lock:
                    self._joined.discard(target)
                    self._active.pop(target, None)
                    self._created.pop(target, None)
                    self._materializing.pop(target, None)
                    self._settings.pop(target, None)
                    for key in [key for key in self._live if key[0] == target]:
                        self._live.pop(key, None)
                        self._pending_terminal.discard(key)
                self.interactions.discard_thread(target)
                self.on_event({'method': 'relay/historyInvalidated', 'params': {'threadId': target}})
            mark('verified')
        except Exception as error:
            mark('failed', error)
            raise
        return {'threadId': thread_id, 'released': True, 'releaseScope': 'writer-process',
                'affectedThreadIds': sorted(affected)}

    def handoff(self, thread_id, action, *, owner, ticket, confirmed=False, rebind_workspace=None, request_id=None):
        trace_id = new_request_id(request_id)
        started = time.monotonic()
        stage = 'preflight'

        def progress(value):
            nonlocal stage
            stage = value
            event('handoff_stage', request_id=trace_id, thread_id=thread_id,
                  action=action, stage=stage,
                  duration_ms=round((time.monotonic() - started) * 1000, 2))

        progress(stage)
        try:
            result = self._handoff(thread_id, action, owner=owner, ticket=ticket,
                                   confirmed=confirmed, rebind_workspace=rebind_workspace,
                                   progress=progress)
        except Exception as error:
            # Native exception text can contain user paths, input or credentials.
            event('handoff_finished', request_id=trace_id, thread_id=thread_id,
                  action=action, stage=stage, outcome='failed',
                  error_code=getattr(error, 'code', 'handoff_failed'),
                  error_type=type(error).__name__,
                  duration_ms=round((time.monotonic() - started) * 1000, 2))
            raise
        event('handoff_finished', request_id=trace_id, thread_id=thread_id,
              action=action, stage=stage, outcome='succeeded',
              duration_ms=round((time.monotonic() - started) * 1000, 2))
        return result

    def _handoff(self, thread_id, action, *, owner, ticket, confirmed=False, rebind_workspace=None, progress):
        # Only the previewed scope is blocked; consume() revalidates its identity.
        scope = self.writers.preview_scope(thread_id, owner, action, ticket)
        with self.operation_gate(*scope):
            self.start()
            if action == 'resume':
                progress('resume')
                with self._lock:
                    preview = self._subscription_releases.pop(ticket, None)
                connection = self._thread_connection(thread_id)
                if (not preview or preview.get('operation') != 'resume'
                        or preview['threadId'] != thread_id or preview['owner'] != owner
                        or preview['connection'] is not connection
                        or preview['generation'] != str(connection.generation)
                        or preview['expires'] < time.monotonic() or rebind_workspace is not None):
                    raise CodexError('恢复预览已失效，请重新操作', code='handoff_changed', http_status=409)
                if getattr(self.connection, 'owns_writer', lambda _: False)(thread_id) and not connection.status().get('connected'):
                    self.connection.release_writer(thread_id, connection)
                self._assert_workspace(thread_id)
                # Ordinary resume never terminates another writer or starts a turn.
                try:
                    response = self.connection.request('thread/resume', {'threadId': thread_id, 'excludeTurns': True})
                except CodexError as error:
                    if error.code == 'native_rejected' and 'already has an active writer' in str(error):
                        # Keep queues paused, but offer the actual next action
                        # instead of an endless ordinary-resume loop.
                        self._observe(thread_id, 'ownership', '', {'released': True, 'externalWriter': True})
                        self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
                    raise
                if str(connection.generation) != preview['generation']:
                    raise CodexError('原生连接已变化，恢复结果待核对', code='handoff_changed', http_status=409, request_sent=True)
                self._forget_observation(thread_id, 'ownership')
                with self._lock:
                    self._joined.add(thread_id)
                    self._settings[thread_id] = self._settings_from(response)
                self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
                return {'threadId': thread_id}
            if action == 'release' and rebind_workspace is None:
                released = self._release_subscription(thread_id, owner, ticket)
                if released is not None:
                    return released
            connection, descriptor = self._handoff_connection(thread_id)
            with self._lock:
                expected_host = self._handoff_hosts.pop(ticket, None)
            if expected_host != descriptor:
                raise CodexError('原生主机或交接预览已变化，请重新预览', code='handoff_host_changed', http_status=409)
            if action == 'release' and confirmed is not True:
                raise CodexError('请确认释放持锁进程及受影响会话', code='handoff_confirmation_required', http_status=409)
            if action == 'takeover':
                thread = self.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
                current_rebind = self._workspace_rebind(thread, require_target=True)
                with self._lock:
                    preview_rebind = self._handoff_workspaces.pop(ticket, None)
                if current_rebind is not None or rebind_workspace is not None:
                    if (confirmed is not True or not current_rebind or current_rebind != preview_rebind
                            or rebind_workspace != current_rebind['path']):
                        raise CodexError('工作目录已变化，请确认原项目目录后重新接管', code='workspace_rebind_confirmation_required', http_status=409)
            elif rebind_workspace is not None:
                raise CodexError('释放操作不能修改工作目录', code='invalid_request', http_status=400)
            snapshot = self.writers.consume(thread_id, owner, action, ticket, confirmed)
            restart_args = self.writers.restart_command(snapshot, descriptor['pid'], connection.socket_path) if action == 'takeover' else None
            _current_connection, current_host = self._handoff_connection(thread_id)
            if current_host != descriptor or _current_connection is not connection:
                raise CodexError('原生主机已变化，未结束任何进程', code='handoff_host_changed', http_status=409)
            affected = {thread_id, *(t for holder in snapshot['holders'] for t in holder['threads'])}
            if action == 'release':
                progress('release')
                return self._release_process_writer(thread_id, snapshot, affected)
            prepare = getattr(self.connection, 'prepare_takeover', None)
            lease = prepare(thread_id) if prepare and restart_args else nullcontext(None)
            progress('prepare')
            with lease as acquire:
                # Pause all affected Relay queues/automatic subscriptions before killing
                # the writer. Interrupted instructions are never resumed or replayed.
                for affected_id in affected:
                    ownership = {'released': True, 'releaseState': 'takeover', 'takeoverThreadId': thread_id}
                    if self.observations:
                        self.observations.put(affected_id, 'ownership', '', ownership)
                    self._observe(affected_id, 'ownership', '', ownership)
                stage = 'terminate'
                progress(stage)
                try:
                    self.writers.terminate(snapshot)
                    early_response = None
                    if acquire is not None:
                        stage = 'acquire'
                        progress(stage)
                        params = {'threadId': thread_id, 'excludeTurns': True}
                        if rebind_workspace is not None:
                            params['cwd'] = rebind_workspace
                        early_response = acquire(params)
                    for affected_id in affected:
                        with self._lock:
                            self._joined.discard(affected_id)
                            self._active.pop(affected_id, None)
                            self._created.pop(affected_id, None)
                            self._materializing.pop(affected_id, None)
                            for key in [key for key in self._live if key[0] == affected_id]:
                                self._live.pop(key, None)
                                self._pending_terminal.discard(key)
                    # A supervisor may already have restored the same endpoint. Otherwise
                    # replace only the daemon we just terminated using its verified argv.
                    stage = 'runtime'
                    progress(stage)
                    if restart_args:
                        try:
                            connection.start()
                        except CodexError:
                            self.writers.restart(restart_args, self._handoff_log)
                        deadline = time.monotonic() + 15
                        while True:
                            try:
                                connection.start()
                                break
                            except CodexError:
                                if time.monotonic() >= deadline:
                                    raise CodexError('原持锁进程已结束，但原生运行时尚未恢复；不会重发任务', code='handoff_runtime_unavailable', http_status=503, request_sent=True)
                                time.sleep(.2)
                    stage = 'resume'
                    progress(stage)
                    if action == 'takeover':
                        self.start()
                        if rebind_workspace is None and acquire is None:
                            # Preserve the idle lock window after explicit takeover so
                            # an orphan terminal record can be repaired before resume.
                            self._native_history_state(thread_id)
                        resume = {'threadId': thread_id, 'excludeTurns': True}
                        if rebind_workspace is not None:
                            resume['cwd'] = rebind_workspace
                        response = early_response if acquire is not None else self.connection.request('thread/resume', resume)
                        if rebind_workspace is not None and (response.get('thread') or {}).get('cwd') != rebind_workspace:
                            raise CodexError('原生未确认指定工作目录，接管结果待核对', code='workspace_rebind_unconfirmed', http_status=409, request_sent=True)
                        stage = 'verify'
                        progress(stage)
                        acquired = self.writers.snapshot(thread_id)
                        runtime_id = self._thread_connection(thread_id).status().get('runtimeId')
                        if not acquired['holders'] or any(str(h['pid']) != str(runtime_id) for h in acquired['holders']):
                            raise CodexError('原进程已结束，但未确认新运行时持锁', code='handoff_acquire_failed', http_status=409)
                        if self.observations:
                            self.observations.delete(thread_id, 'ownership')
                        self._forget_observation(thread_id, 'ownership')
                        with self._lock:
                            self._joined.add(thread_id)
                            self._settings[thread_id] = self._settings_from(response)
                        if rebind_workspace is not None and self.workspaces:
                            with self.workspaces.db.transaction() as c:
                                # Keep the original worktree/archive record, but this
                                # thread now executes in the verified original project.
                                c.execute('UPDATE workspaces SET thread_id=NULL WHERE thread_id=?', (thread_id,))
                    elif self.writers.busy(thread_id):
                        raise CodexError('会话被其他进程重新持有，未确认释放成功', code='writer_still_held', http_status=409)
                except Exception as error:
                    for affected_id in affected:
                        ownership = {'released': True, 'releaseState': 'takeover_failed',
                                     'takeoverThreadId': thread_id, 'takeoverStage': stage,
                                     'releaseError': str(error),
                                     'errorCode': getattr(error, 'code', 'handoff_failed')}
                        self._observe(affected_id, 'ownership', '', ownership)
                        try:
                            self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': affected_id}})
                        except Exception:
                            logging.getLogger(__name__).warning('Handoff failure notification unavailable')
                    raise
            self.on_event({'method': 'relay/ownershipChanged', 'params': {'threadId': thread_id}})
            return {'threadId': thread_id}
