"""Real local HTTP + temporary SQLite with a controlled native stub.

These tests verify Relay contracts, never claim Desktop or emulator acceptance.
No user's Codex installation, credentials, projects, or services are accessed.
"""

from concurrent.futures import ThreadPoolExecutor
import http.client
import base64
import json
from pathlib import Path
import tempfile
import threading
import unittest

from rivo_relay.api import Handler
from rivo_relay.app import RelayApp
from rivo_relay.config import Config
from rivo_relay.events import EventBus
from rivo_relay.http_server import BoundedHTTPServer
from rivo_relay.pairing import PairingStore


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


class PausingEventBus(EventBus):
    """Pause just after the SSE handler's initial authorization check."""
    def __init__(self):
        super().__init__()
        self.entered = threading.Event()
        self.resume = threading.Event()
        self.pause_next = True

    def read(self, cursor, timeout=15):
        if cursor is not None and self.pause_next:
            self.pause_next = False
            self.entered.set()
            self.resume.wait(3)
            timeout = 0
        return super().read(cursor, timeout)

    def close(self):
        self.resume.set()
        super().close()


class ApiTests(unittest.TestCase):
    def setUp(self):
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

    def test_attachment_and_directory_routes_require_auth_and_use_native_host(self):
        calls = []
        self.native.read_directory = lambda path: {"path": path, "entries": [{"fileName": "a.pdf", "isFile": True, "isDirectory": False}]}
        self.native.upload_attachment = lambda thread, name, data: calls.append((thread, name, data)) or {"type": "mention", "name": name, "path": "/remote/a.pdf"}
        status, body, _ = self.request("GET", "/v1/directories?path=%2Fremote", token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("a.pdf", body["entries"][0]["fileName"])
        status, body, _ = self.request("POST", "/v1/threads/thread-1/attachments", {"name": "a.pdf", "dataBase64": "YQ=="}, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual([("thread-1", "a.pdf", "YQ==")], calls)
        status, _, _ = self.request("POST", "/v1/threads/thread-1/attachments", {"name": "a.pdf", "dataBase64": "YQ=="})
        self.assertEqual(401, status)
        self.assertEqual(1, len(calls))

    def test_binary_transfer_and_expiring_scoped_link(self):
        data = b"\x00\xff" * (3 * 1024 * 1024)
        uploads = []
        self.native.upload_attachment_stream = lambda thread, name, stream, size: uploads.append(stream.read(size)) or {"path": "/remote/test.zip"}
        status, _, _ = self.request("POST", "/v1/threads/thread-1/attachments?name=test.zip", token=self.token,
            raw=data, extra_headers={"Content-Type": "application/octet-stream"})
        self.assertEqual(200, status)
        self.assertEqual([data], uploads)
        status, _, _ = self.request("POST", "/v1/threads/thread-1/attachments?name=test.zip", token=self.token,
            raw=b"", extra_headers={"Content-Type": "application/octet-stream", "Content-Length": str(1024 * 1024 * 1024 + 1)})
        self.assertEqual(413, status)
        self.assertEqual(1, len(uploads))
        reads = []
        self.native.stream_download = lambda path: reads.append(path) or (len(data), iter([data]))
        self.assertEqual(401, self.request("GET", "/v1/files/download?path=%2Fremote%2Ftest.zip")[0])
        status, link, _ = self.request("POST", "/v1/download-links", {"path": "/remote/test.zip"}, token=self.token)
        self.assertEqual(200, status)
        path = "/v1/download-links/" + link["url"].rsplit("/", 1)[-1]
        conn = http.client.HTTPConnection("127.0.0.1", self.port)
        try:
            conn.request("GET", path)
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            self.assertIn("attachment", response.getheader("Content-Disposition"))
            self.assertEqual(data, response.read())
        finally:
            conn.close()
        self.assertEqual(["/remote/test.zip"], reads)
        key = path.rsplit("/", 1)[-1]
        self.app.download_links[key] = ("/remote/test.zip", 0)
        self.assertEqual(404, self.request("GET", path)[0])
        self.assertEqual(401, self.request("POST", "/v1/download-links", {"path": "/private"})[0])

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

    def open_stream(self, *, token=None):
        client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        client.request("GET", "/v1/events", headers={"Authorization": "Bearer " + (token or self.token)})
        response = client.getresponse()
        self.addCleanup(client.close)
        self.addCleanup(response.close)
        return response

    def event(self, response):
        fields = {}
        for _ in range(100):
            line = response.fp.readline().decode("utf-8").rstrip("\r\n")
            if not line:
                if fields:
                    return fields
                continue
            if line.startswith(":"):
                continue
            key, _, value = line.partition(":")
            fields[key] = value.lstrip()
        self.fail("No complete SSE event received")

    def create_todo(self):
        status, result, _ = self.request("POST", "/v1/todos", {"id": "todo-1", "title": "Test task", "notes": "Acceptance notes"}, token=self.token)
        self.assertEqual(status, 200)
        return result["todo"]

    def test_queue_enqueue_is_idempotent_and_stale_edit_fails_definitely(self):
        self.native.validate_queue_target = lambda thread: None
        body = {"submissionId": "queue-a", "operation": "enqueue", "input": [{"type": "text", "text": "queued"}]}
        path = "/v1/threads/thread-1/queue"
        first = self.request("POST", path, body, token=self.token)
        second = self.request("POST", path, body, token=self.token)
        self.assertEqual("accepted", first[1]["status"])
        self.assertEqual(first[1], second[1])
        self.assertEqual(1, len(self.app.queue.list("thread-1")))
        edit = {"submissionId": "edit-a", "operation": "edit", "itemId": "queue-a", "revision": 99, "text": "updated"}
        self.assertEqual("failed", self.request("POST", path, edit, token=self.token)[1]["status"])
        self.assertEqual("queued", self.app.queue.list("thread-1")[0]["text"])
        self.assertEqual(1, len(self.request("GET", "/v1/threads/thread-1", token=self.token)[1]["queue"]))
        self.assertFalse(self.native.sent)

    def test_health_is_public_but_project_data_requires_valid_token(self):
        self.assertEqual(self.request("GET", "/health")[0], 200)
        for token in (None, "invalid"):
            self.assertEqual(self.request("GET", "/v1/projects", token=token)[0], 401)
        self.assertEqual(self.native.read_count, 0)
        status, result, headers = self.request("GET", "/v1/projects", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(result["projects"][0]["id"], "project-1")
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.app.pairing.revoke(self.paired["deviceId"])
        self.assertEqual(self.request("GET", "/v1/projects", token=self.token)[0], 401)
        self.assertEqual(self.native.read_count, 1)

    def test_pairing_http_is_one_time_expires_and_rate_limits(self):
        now = [100.0]
        self.app.pairing = PairingStore(self.app.db, clock=lambda: now[0], attempt_limit=4)
        code = self.app.pairing.create()["code"]
        body = {"code": code, "deviceName": "Phone"}
        self.assertEqual(self.request("POST", "/v1/pair", body)[0], 200)
        self.assertEqual(self.request("POST", "/v1/pair", body)[0], 401)
        expired = self.app.pairing.create(ttl_seconds=1)["code"]
        now[0] += 1
        self.assertEqual(self.request("POST", "/v1/pair", {**body, "code": expired})[0], 401)
        self.assertEqual(self.request("POST", "/v1/pair", {**body, "code": "wrong"})[0], 401)
        status, result, _ = self.request("POST", "/v1/pair", {**body, "code": "wrong"})
        self.assertEqual(status, 429)
        self.assertEqual(result["error"]["code"], "pairing_rate_limited")

    def test_write_duplicate_and_changed_payload_do_not_repeat_native_call(self):
        body = {"submissionId": "send-1", "text": "Check isolated fixture"}
        path = "/v1/threads/thread-1/turns"
        first = self.request("POST", path, body, token=self.token)
        second = self.request("POST", path, body, token=self.token)
        self.assertEqual(first[:2], second[:2])
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(len(self.native.sent), 1)
        status, result, _ = self.request("POST", path, {**body, "text": "Changed"}, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "submission_conflict")
        self.assertEqual(len(self.native.sent), 1)

    def test_transport_uncertainty_and_definite_failure_never_auto_replay(self):
        body = {"submissionId": "unknown-1", "text": "Synthetic task"}
        path = "/v1/threads/thread-1/turns"
        self.native.send_error = TimeoutError("lost reply")
        self.assertEqual(self.request("POST", path, body, token=self.token)[1]["status"], "unknown")
        self.native.send_error = None
        self.assertEqual(self.request("POST", path, body, token=self.token)[1]["status"], "unknown")
        self.assertEqual(len(self.native.sent), 1)
        self.native.send_error = NativeFailure()
        body["submissionId"] = "failed-1"
        result = self.request("POST", path, body, token=self.token)[1]
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["errorCode"], "native_rejected")
        self.assertEqual(self.request("POST", path, body, token=self.token)[1], result)
        self.assertEqual(len(self.native.sent), 2)

    def test_restart_marks_pending_unknown_preserves_checkpoint_and_does_not_dispatch(self):
        body = {"submissionId": "pending-1", "text": "Synthetic task"}
        self.app.submissions.claim("pending-1", self.paired["deviceId"], "thread.turns", "thread-1", body)
        self.app.submissions.checkpoint("pending-1", {"threadId": "thread-1"})
        self._stop()
        self._start()
        status, record, _ = self.request("GET", "/v1/submissions/pending-1", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(record["status"], "unknown")
        self.assertEqual(record["receipt"], {"threadId": "thread-1"})
        self.assertEqual(self.request("POST", "/v1/threads/thread-1/turns", body, token=self.token)[1], record)
        self.assertEqual(self.native.sent, [])

    def test_submission_lookup_is_scoped_to_originating_connection(self):
        body = {"submissionId": "private-1", "text": "Synthetic task"}
        self.request("POST", "/v1/threads/thread-1/turns", body, token=self.token)
        other = self._pair()
        self.assertEqual(self.request("GET", "/v1/submissions/private-1", token=other["token"])[0], 404)

    def test_todo_dispatch_checkpoints_before_sending_and_keeps_manual_completion(self):
        self.create_todo()
        observed = {}

        def at_send(*_):
            observed["submission"] = self.app.submissions.get("dispatch-1")
            observed["todo"] = self.app.todos.get("todo-1")

        self.native.on_send = at_send
        body = {"projectId": "project-1", "submissionId": "dispatch-1"}
        first = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(first[0], 200)
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(observed["submission"]["status"], "pending")
        self.assertEqual(observed["submission"]["receipt"], {"threadId": "created-1"})
        self.assertEqual(observed["todo"]["thread_id"], "created-1")
        self.assertFalse(observed["todo"]["completed"])
        self.assertEqual(self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)[:2], first[:2])
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {**body, "submissionId": "dispatch-2"}, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(len(self.native.created), 1)

    def test_two_distinct_dispatch_ids_cannot_create_two_threads(self):
        self.create_todo()
        self.native.create_gate = threading.Event()
        start = threading.Barrier(2)

        def dispatch(index):
            start.wait()
            return self.request("POST", "/v1/todos/todo-1/dispatch", {
                "projectId": "project-1", "submissionId": f"concurrent-{index}"
            }, token=self.token)

        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = [pool.submit(dispatch, number) for number in range(2)]
            self.assertTrue(self.native.first_create.wait(2))
            self.native.second_create.wait(0.2)
            self.native.create_gate.set()
            responses = [future.result(timeout=4) for future in pending]
        self.assertEqual(sorted(response[0] for response in responses), [200, 409])
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)

    def test_unknown_dispatch_after_restart_blocks_new_id_even_before_todo_link(self):
        self.create_todo()
        self.app.submissions.claim("crashed-dispatch", self.paired["deviceId"], "todo.dispatch", "todo-1", {"test": "crash"})
        self.app.submissions.checkpoint("crashed-dispatch", {"threadId": "already-created"})
        self._stop()
        self._start()
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "submissionId": "new-id"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_sse_rechecks_revocation_after_waiting_before_emitting_native_data(self):
        self._stop()
        bus = PausingEventBus()
        self._start(bus)
        stream = self.open_stream()
        self.assertEqual(stream.status, 200)
        self.assertEqual(self.event(stream)["event"], "reset")
        self.assertTrue(bus.entered.wait(2))
        self.app.pairing.revoke(self.paired["deviceId"])
        bus.publish({"method": "synthetic/private-after-revocation"})
        bus.resume.set()
        self.assertEqual(self.event(stream)["event"], "revoked")

    def test_sse_limit_reserves_capacity_for_ordinary_requests(self):
        first = self.open_stream()
        self.assertEqual(first.status, 200)
        self.assertEqual(self.event(first)["event"], "reset")
        second = self.open_stream()
        self.assertEqual(second.status, 429)
        self.assertEqual(json.loads(second.read())["error"]["code"], "stream_limit")
        self.assertEqual(self.request("GET", "/v1/projects", token=self.token)[0], 200)

    def test_validation_and_native_error_status_are_sanitized(self):
        status, result, _ = self.request("POST", "/v1/todos", token=self.token,
            raw=b"{", extra_headers={"Content-Type": "application/json"})
        self.assertEqual(status, 400)
        self.assertEqual(result["error"]["code"], "invalid_json")
        self.assertEqual(self.request("POST", "/v1/todos", raw=b"{}", token=self.token)[0], 415)
        self.assertEqual(self.request("GET", "/v1/todos/missing", token=self.token)[0], 404)
        self.native.get_error = NativeFailure("thread_not_found", http_status=404)
        status, result, _ = self.request("GET", "/v1/threads/missing", token=self.token)
        self.assertEqual(status, 404)
        self.assertEqual(result["error"]["code"], "thread_not_found")
        self.assertNotIn("private/test-only-path", json.dumps(result))

    def test_duplicate_content_length_is_rejected_before_reading_body(self):
        client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        try:
            client.putrequest("POST", "/v1/todos")
            client.putheader("Authorization", "Bearer " + self.token)
            client.putheader("Content-Type", "application/json")
            client.putheader("Content-Length", "2")
            client.putheader("Content-Length", "3")
            client.endheaders(b"{}")
            response = client.getresponse()
            self.assertEqual(response.status, 411)
            response.read()
        finally:
            client.close()

    def test_history_cursor_and_limit_are_forwarded_and_bad_limit_is_rejected(self):
        status, page, _ = self.request("GET", "/v1/threads/thread-1?cursor=page-a&limit=7", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(page["nextCursor"], "next-page")
        self.assertEqual(self.native.history_reads[-1], ("thread-1", "page-a", 7))
        status, page, _ = self.request("GET", "/v1/threads/thread-1/history?cursor=page-b&limit=4", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(page["turns"][0]["id"], "synthetic-turn")
        self.assertEqual(self.native.history_reads[-1], ("thread-1", "page-b", 4))
        self.assertEqual(self.request("GET", "/v1/threads/thread-1?limit=invalid", token=self.token)[0], 400)

    def test_confirmed_failure_before_thread_creation_allows_explicit_new_submission(self):
        self.create_todo()
        self.native.create_error = NativeFailure()
        body = {"projectId": "project-1", "submissionId": "failed-create"}
        failed = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(failed[1]["status"], "failed")
        self.assertIsNone(failed[1]["receipt"])
        self.native.create_error = None
        body["submissionId"] = "explicit-new-create"
        accepted = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(accepted[1]["status"], "accepted")
        self.assertEqual(len(self.native.created), 2)
        self.assertEqual(len(self.native.sent), 1)

    def test_failed_submission_with_thread_checkpoint_blocks_new_dispatch_after_restart(self):
        self.create_todo()
        self.app.submissions.claim("failed-with-thread", self.paired["deviceId"], "todo.dispatch", "todo-1", {})
        self.app.submissions.checkpoint("failed-with-thread", {"threadId": "already-created"})
        self.app.submissions.mark_failed("failed-with-thread", "send_rejected")
        self._stop()
        self._start()
        status, _, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "submissionId": "must-not-create"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_existing_thread_must_belong_to_selected_project_before_claim(self):
        self.create_todo()
        status, result, _ = self.request("POST", "/v1/todos/todo-1/dispatch", {
            "projectId": "project-1", "threadId": "thread-2", "submissionId": "wrong-project"
        }, token=self.token)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "submission_conflict")
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])
        self.assertIsNone(self.app.todos.get("todo-1")["thread_id"])
        self.assertEqual(self.request("GET", "/v1/submissions/wrong-project", token=self.token)[0], 404)

    def test_duplicate_existing_thread_dispatch_does_not_depend_on_catalog_changes(self):
        self.create_todo()
        body = {"projectId": "project-1", "threadId": "thread-1", "submissionId": "existing-thread"}
        first = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(first[0], 200)
        self.assertEqual(first[1]["status"], "accepted")
        self.assertEqual(self.native.session_reads, ["project-1"])
        self.native.catalog_threads["project-1"] = []
        duplicate = self.request("POST", "/v1/todos/todo-1/dispatch", body, token=self.token)
        self.assertEqual(duplicate[:2], first[:2])
        self.assertEqual(self.native.session_reads, ["project-1"])
        self.assertEqual(self.native.created, [])
        self.assertEqual(len(self.native.sent), 1)


if __name__ == "__main__":
    unittest.main()
