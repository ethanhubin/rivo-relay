"""Choose a writer for new subscriptions without moving an existing writer."""
from __future__ import annotations

import threading
import time
import uuid

from .connection import CodexError, NativeConnection
from .desktop import DesktopConnection


class RuntimeConnection:
    def __init__(self, socket_path=None, on_event=None, codex_home=None, *, mode='auto',
                 bridge_directory=None, native=None, desktop=None, clock=time.monotonic):
        if mode not in {'auto', 'desktop', 'app_server'}:
            raise ValueError('invalid Codex runtime mode')
        self.mode, self.on_event, self._clock = mode, on_event or (lambda _: None), clock
        if native is None:
            from .owned_writer import SessionWriters
            native = SessionWriters(NativeConnection(socket_path, codex_home=codex_home))
        self.native = native
        self.desktop = desktop or DesktopConnection(bridge_directory, codex_home=codex_home)
        self.codex_home, self.socket_path = self.native.codex_home, self.native.socket_path
        self.generation = uuid.uuid4().hex
        self._lock = threading.RLock()
        self._pins, self._requests = {}, {}
        self._backend_generations = {'app_server': self.native.generation, 'desktop': self.desktop.generation}
        self._preferred = 'desktop' if mode == 'desktop' else 'app_server'
        self._candidate_since, self._last_probe = None, float('-inf')
        self._identity_pair = None
        self._identity_error = None
        self._closed = False
        self.native.on_event = lambda event: self._event('app_server', event)
        self.desktop.on_event = lambda event: self._event('desktop', event)

    def _backend(self, name):
        return self.desktop if name == 'desktop' else self.native

    def _observe_backend_generation(self, name):
        """Retire one writer even if its old disconnect notification arrived late."""
        backend = self._backend(name)
        with self._lock:
            if self._backend_generations.get(name) == backend.generation:
                return
            self._backend_generations[name] = backend.generation
            affected = name in self._pins.values() or any(value[0] == name for value in self._requests.values())
        if affected:
            self._event(name, {'method': 'relay/nativeDisconnected', 'params': {},
                               'connectionId': backend.generation})

    def start(self):
        with self._lock:
            if self._closed:
                raise CodexError('Codex 连接已关闭')
            if self.mode == 'desktop':
                self.desktop.start()
                self._observe_backend_generation('desktop')
            else:
                try:
                    self.native.start()
                except CodexError:
                    if self.mode == 'app_server':
                        raise
                self._observe_backend_generation('app_server')
                self._refresh()
            if not self._backend(self._preferred).status().get('connected'):
                raise CodexError('所选 Codex 运行时尚未连接')
            return self.status()

    def _refresh(self):
        self._observe_backend_generation('app_server')
        self._observe_backend_generation('desktop')
        if self.mode != 'auto' or self._clock() - self._last_probe < 1:
            return
        self._last_probe = self._clock()
        try:
            self.desktop.start()
        except CodexError:
            pass
        self._observe_backend_generation('desktop')
        # With per-session ownership, auto discovery must not silently route an
        # App-created/resumed thread back into Desktop's shared writer. Explicit
        # desktop mode remains available for intentional Desktop sharing.
        if self.native.status().get('sessionIsolation'):
            self._preferred = 'app_server'
            return
        ready = self.desktop.status().get('connected', False)
        native_ready = self.native.status().get('connected', False)
        if ready and native_ready:
            pair = (self.native.generation, self.desktop.generation)
            if pair != self._identity_pair:
                try:
                    daemon, desktop = self.native.native_host_info(), self.desktop.native_host_info()
                    if any(daemon.get(key) != desktop.get(key) for key in ('hostName', 'codexHome', 'platform')):
                        raise CodexError('Desktop 与所选原生主机不一致', code='desktop_host_mismatch')
                    self._identity_pair, self._identity_error = pair, None
                except CodexError as error:
                    self._identity_error = str(error)
            ready = self._identity_pair == pair and not self._identity_error
        if ready and not native_ready and (self._identity_pair is None or
                                          self._identity_pair[1] != self.desktop.generation):
            # A forwarded socket may point at an entirely different host. Auto
            # cannot replace an unavailable target with the local Desktop home.
            ready = False
            self._identity_error = '所选原生主机尚未核对；不会自动切换到 Desktop'
        if ready:
            if self._candidate_since is None:
                self._candidate_since = self._clock()
            if not native_ready or self._clock() - self._candidate_since >= 2:
                self._preferred = 'desktop'
        else:
            self._candidate_since = None
            self._preferred = 'app_server'

    def status(self):
        with self._lock:
            result = dict(self._backend(self._preferred).status())
            bridge = dict(self.desktop.status().get('bridge', {}))
            bridge['error'] = self._identity_error or self.desktop.status().get('error')
            result.update(runtimeMode=self.mode, preferredWriter=self._preferred, bridge=bridge,
                          generation=self.generation, pinnedWriters=dict(self._pins),
                          writerGenerations=dict(self._backend_generations))
            return result

    def request(self, method, params=None, *, timeout=None):
        params = params or {}
        thread_id = params.get('threadId') or params.get('conversationId')
        with self._lock:
            self._refresh()
            name = self._pins.get(thread_id, self._preferred)
            backend = self._backend(name)
            if not backend.status().get('connected'):
                # A created/unmaterialized or active thread must never be retried
                # through a different connection just because its writer vanished.
                raise CodexError('会话所属原生连接不可用，请等待该连接恢复',
                                 code='writer_unavailable')
            generation = backend.generation
        result = backend.request(method, params, timeout=timeout)
        with self._lock:
            if method in {'thread/start', 'thread/fork', 'thread/resume'}:
                selected = result.get('thread', {}).get('id') or thread_id
                if selected:
                    self._pins[selected] = name
            elif method == 'thread/unsubscribe' and thread_id:
                self._pins.pop(thread_id, None)
            if backend.generation != generation:
                raise CodexError('原生连接已变化，请核对结果', request_sent=True)
        return result

    def respond(self, request_id, result):
        with self._lock:
            selected = self._requests.get(request_id)
            if selected is None:
                raise CodexError('原生交互已失效', code='interaction_expired', http_status=409)
            name, generation, native_id = selected
            backend = self._backend(name)
            if generation != backend.generation:
                raise CodexError('原生交互连接已变化', code='interaction_expired', http_status=409)
        answer = backend.respond(native_id, result)
        with self._lock:
            self._requests.pop(request_id, None)
        return answer

    def notify(self, method, params=None):
        return self._backend(self._preferred).notify(method, params)

    def native_host_info(self):
        descriptor = self._backend(self._preferred).native_host_info()
        return {**descriptor, 'generation': self.generation}

    def history_host_connection(self, thread_id):
        """Bind the probe, repair, and following subscription to one native host.

        The backend generation, rather than the multiplexer's event generation,
        fences helpers when only one of the two transports reconnects.
        """
        with self._lock:
            self._refresh()
            selected = self._pins.setdefault(thread_id, self._preferred)
            backend = self._backend(selected)
            bind = getattr(backend, 'history_host_connection', None)
            return bind(thread_id) if bind else backend

    def owns_writer(self, thread_id):
        return self._pins.get(thread_id, self._preferred) == 'app_server' and getattr(self.native, 'owns', lambda _: False)(thread_id)

    def writer_owner(self, thread_id):
        if self._pins.get(thread_id, self._preferred) == 'app_server':
            inspect = getattr(self.native, 'writer_owner', None)
            return inspect(thread_id) if inspect else None
        return None

    def release_writer(self, thread_id, expected):
        return self.native.release(thread_id, expected)

    def verify_release_history(self, thread_id):
        metadata = getattr(self.native, 'metadata', None)
        if metadata is not None:
            metadata.request('thread/read', {'threadId': thread_id, 'includeTurns': False})
            metadata.request('thread/turns/list', {'threadId': thread_id, 'limit': 1,
                                                 'sortDirection': 'desc', 'itemsView': 'full'})

    def _event(self, name, event):
        backend = self._backend(name)
        if event.get('connectionId', backend.generation) != backend.generation:
            return
        self._observe_backend_generation(name)
        forwarded = {**event, 'connectionId': self.generation, 'writerKind': name}
        with self._lock:
            thread_id = event.get('params', {}).get('threadId')
            if thread_id in self._pins and self._pins[thread_id] != name:
                return
            if 'id' in event:
                key = f'{name}:{backend.generation}:{type(event["id"]).__name__}:{event["id"]}'
                self._requests[key] = (name, backend.generation, event['id'])
                forwarded['id'] = key
            if event.get('method') in {'serverRequest/resolved', 'serverRequest/completed'}:
                native_id = event.get('params', {}).get('requestId')
                key = f'{name}:{backend.generation}:{type(native_id).__name__}:{native_id}'
                self._requests.pop(key, None)
                forwarded['params'] = {**event.get('params', {}), 'requestId': key}
            if event.get('method') == 'relay/writerDisconnected':
                expired = []
                native_expired = set(event.get('params', {}).get('requestIds', []))
                for key, value in list(self._requests.items()):
                    if value[0] == name and value[2] in native_expired:
                        expired.append(key)
                        self._requests.pop(key, None)
                forwarded['params'] = {**event.get('params', {}), 'requestIds': expired}
            if event.get('method') == 'relay/nativeDisconnected':
                expired = [key for key, value in self._requests.items() if value[0] == name]
                self._requests = {key: value for key, value in self._requests.items() if value[0] != name}
                # Idle fallback discovery must not clear the live Desktop view.
                if name != self._preferred and name not in self._pins.values() and not expired:
                    return
                forwarded['method'] = 'relay/writerDisconnected'
                forwarded['params'] = {'writerKind': name, 'requestIds': expired,
                                       'threadIds': [key for key, value in self._pins.items() if value == name]}
            elif event.get('method') == 'qiguang/desktopConnectionState':
                forwarded['method'] = 'relay/desktopConnectionState'
        self.on_event(forwarded)

    def close(self):
        self._closed = True
        try:
            self.desktop.close()
        finally:
            self.native.close()
