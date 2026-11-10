"""Local HTTP server and temporary Relay workspace fixtures."""
import threading
import tempfile
import http.client
import json
from pathlib import Path
from unittest.mock import patch
from rivo_relay.api import Handler
from rivo_relay.app import RelayApp
from rivo_relay.config import Config
from rivo_relay.http_server import BoundedHTTPServer

class NativeFailure(Exception):
    def __init__(self, code="native_rejected", http_status=409, definite=True):
        self.code, self.http_status, self.definite = code, http_status, definite
        super().__init__("synthetic private diagnostics /private/test-only-path")


class NativeStub:
    def __init__(self):
        self.created = []
        self.sent = []
        self.read_count = 0
        self.history_reads = []
        self.session_reads = []
        self.catalog_threads = {"project-1": ["thread-1"], "project-2": ["thread-2"]}
        self.send_error = None
        self.get_error = None
        self.create_error = None
        self.on_send = None
        self.create_gate = None
        self.first_create = threading.Event()
        self.second_create = threading.Event()
        self.lock = threading.Lock()

    def status(self):
        return {"connected": True, "capabilities": {"nativeTestStub": True}}

    def list_projects(self):
        self.read_count += 1
        return [{"id": "project-1", "name": "Isolated test project"}]

    def list_sessions(self, project):
        self.session_reads.append(project)
        return [{"id": thread, "projectId": project} for thread in self.catalog_threads.get(project, [])]

    def get_session(self, thread, *, cursor=None, limit=30):
        if self.get_error:
            raise self.get_error
        return {"threadId": thread, **self.history(thread, cursor=cursor, limit=limit)}

    def history(self, thread, *, cursor=None, limit=30):
        self.history_reads.append((thread, cursor, limit))
        return {"turns": [{"id": "synthetic-turn"}], "nextCursor": "next-page"}

    def create_session(self, project, title=None):
        with self.lock:
            self.created.append((project, title))
            thread_id = "created-" + str(len(self.created))
            self.first_create.set()
            if len(self.created) > 1:
                self.second_create.set()
        if self.create_gate:
            if not self.create_gate.wait(3):
                raise TimeoutError("test coordination timeout")
        if self.create_error:
            raise self.create_error
        return {"threadId": thread_id}

    def send(self, thread, text, **kwargs):
        with self.lock:
            self.sent.append((thread, text, kwargs))
            turn_id = "turn-" + str(len(self.sent))
        if self.on_send:
            self.on_send(thread, text, kwargs)
        if self.send_error:
            raise self.send_error
        return {"threadId": thread, "turnId": turn_id}

    def close(self):
        pass



class RelayHTTPFixture:
    def setUp(self):
        discovery = patch('rivo_relay.node_name.discover_name', return_value='test-machine')
        discovery.start()
        self.addCleanup(discovery.stop)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        workspace = Path(self.directory.name)
        self.config = Config(workspace=workspace, codex_home=workspace / "unused-codex-home")
        self.server = None
        self.app = None
        self._start()
        self.addCleanup(self._stop)
        self.paired = self._pair()
        self.token = self.paired["token"]

    def _start(self, bus=None):
        self.native = NativeStub()
        self.app = RelayApp(self.config, self.native, bus)
        self.server = BoundedHTTPServer(("127.0.0.1", 0), Handler, app=self.app,
                                        max_workers=6, max_streams=1)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def _stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=3)
            self.server = None
        if self.app is not None:
            self.app.close()
            self.app = None

    def request(self, method, path, body=None, *, token=None, raw=None, extra_headers=None):
        client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        headers.update(extra_headers or {})
        try:
            client.request(method, path, body=raw, headers=headers)
            response = client.getresponse()
            data = response.read()
            return response.status, json.loads(data), dict(response.getheaders())
        finally:
            client.close()

    def _pair(self):
        code = self.app.pairing.create()["code"]
        status, result, _ = self.request("POST", "/v1/pair", {"code": code, "deviceName": "Test phone"})
        self.assertEqual(status, 200)
        return result
