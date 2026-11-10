"""Native RPC and execution-queue substitutes."""
import copy
from pathlib import Path
from rivo_relay.codex.connection import CodexError

class FakeNative:
    generation = "connection-one"

    def __init__(self):
        self.calls = []
        self.replies = []
        self.thread = {"id": "thread", "status": {"type": "idle"}, "turns": []}

    def start(self):
        return self.status()

    def status(self):
        return {"connected": True}

    def request(self, method, params, **kwargs):
        self.calls.append((method, copy.deepcopy(params)))
        if method == "project/read":
            return {"project": {"id": "project", "roots": [{"path": "/workspace"}]}}
        if method in {"thread/start", "thread/resume", "thread/read"}:
            return {"thread": self.thread, "model": "native-model",
                    "approvalPolicy": "on-request", "sandbox": {"type": "readOnly"}}
        if method == "turn/start":
            return {"turn": {"id": "turn", "status": "inProgress"}}
        if method == "turn/steer":
            return {"turnId": "turn"}
        if method == "thread/turns/list":
            return {"data": [], "nextCursor": None, "backwardsCursor": None}
        return {}

    def respond(self, request_id, result):
        self.replies.append((request_id, result))


class Backend(FakeNative):
    codex_home = Path('/fixture')
    socket_path = '/fixture/socket'
    serial = 0
    def __init__(self, *args):
        super().__init__()
        Backend.serial += 1
        self.generation = str(Backend.serial)
        self.on_event = lambda _: None
        self.closed = False
    def status(self):
        return {'connected': not self.closed, 'runtime': 'relay-session-writer', 'ownedWriter': True}
    def request(self, method, params=None, **kwargs):
        if self.closed:
            raise CodexError('closed', code='writer_unavailable')
        if method in ('thread/start', 'thread/resume', 'thread/fork'):
            self.calls.append((method, params))
            return {'thread': {'id': params.get('threadId') or 'new-'+self.generation, 'status': {'type': 'idle'}}}
        return super().request(method, params, **kwargs)
    def close(self):
        self.closed = True
        self.on_event({'method': 'relay/nativeDisconnected', 'params': {}, 'connectionId': self.generation})


class QueueNative:
    def __init__(self):
        self.snapshot = {'thread': {'turns': []}, 'activeTurnId': None, 'status': 'idle'}
        self.sent = []
        self.error = None
    def validate_queue_target(self, thread): pass
    def get_session(self, thread): return copy.deepcopy(self.snapshot)
    def send_queued(self, thread, **kwargs):
        self.sent.append(kwargs)
        if self.error: raise self.error
        return {'threadId': thread, 'turnId': 'turn-' + str(len(self.sent))}
    def steer(self, thread, text, turn, **kwargs):
        self.sent.append(kwargs)
        if self.error: raise self.error
        return {'threadId': thread, 'turnId': turn}
