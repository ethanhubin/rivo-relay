"""Focused regression tests for native identity, settings and delivery semantics."""
import copy
import base64
import json
import tempfile
import threading
import unittest
from pathlib import Path

from rivo_relay.codex.connection import CodexError, NativeConnection
from rivo_relay.codex.interactions import Interactions
from rivo_relay.codex.sessions import CodexService
from rivo_relay.codex.history import NativeHistoryAdapter
from rivo_relay.codex.files import read_file, read_directory, upload_attachment, download_file, upload_attachment_stream, stream_download


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


class NativeSemanticsTests(unittest.TestCase):
    def test_external_writer_can_read_history_without_takeover_or_write(self):
        native = FakeNative()
        original = native.request
        attempted = []
        def request(method, params, **kwargs):
            attempted.append(method)
            if method == "thread/resume":
                raise CodexError("thread thread already has an active writer", code="native_rejected", http_status=409)
            if method == "thread/turns/list":
                return {"data": [{"id": "turn", "status": "completed", "items": [{"id": "answer", "type": "agentMessage", "text": "Existing answer"}]}], "nextCursor": None}
            return original(method, params, **kwargs)
        native.request = request
        service = CodexService(connection=native)
        result = service.get_session("thread")
        self.assertTrue(result["readOnly"])
        self.assertEqual("Existing answer", result["thread"]["turns"][0]["items"][0]["text"])
        with self.assertRaises(CodexError):
            service.send("thread", "Must not dispatch")
        self.assertNotIn("turn/start", attempted)
        def other_error(method, params, **kwargs):
            if method == "thread/resume":
                raise CodexError("different native failure", code="native_rejected", http_status=409)
            return original(method, params, **kwargs)
        native.request = other_error
        with self.assertRaises(CodexError):
            service.get_session("thread")

    def test_project_activity_uses_existing_native_list_and_project_assignment(self):
        native = FakeNative()
        calls = []
        threads = [
            {"id": "running", "cwd": "/workspace", "projectId": "project", "status": {"type": "active", "activeFlags": []}},
            {"id": "waiting", "cwd": "/workspace", "projectId": "project", "status": {"type": "active", "activeFlags": ["waitingOnApproval", "waitingOnUserInput"]}},
            {"id": "worktree", "cwd": "/worktree", "projectId": "project", "status": {"type": "active", "activeFlags": []}},
            {"id": "legacy", "cwd": "/workspace", "projectId": None, "status": {"type": "active", "activeFlags": []}},
            {"id": "other-project", "cwd": "/workspace", "projectId": "other", "status": {"type": "active", "activeFlags": []}},
            *[{"id": status, "cwd": "/workspace", "projectId": "project", "status": {"type": status}}
              for status in ("idle", "notLoaded", "systemError")],
        ]

        def request(method, params, **kwargs):
            calls.append(method)
            if method == "project/list":
                data = [{"id": "project", "name": "Project", "roots": [{"path": "/workspace"}]},
                        {"id": "empty", "name": "Empty", "roots": [{"path": "/empty"}]}]
            elif method == "thread/list":
                data = threads
            else:
                self.fail("Project activity must not request per-thread metadata or history")
            return {"data": data, "nextCursor": None}

        native.request = request
        projects = CodexService(connection=native).list_projects()
        self.assertEqual(7, projects[0]["threadCount"])
        self.assertEqual(4, projects[0]["outstandingWorkCount"])
        self.assertEqual(0, projects[1]["outstandingWorkCount"])
        self.assertEqual(["project/list", "thread/list"], calls)

    def test_shared_cwd_does_not_override_native_project_assignment(self):
        native = FakeNative()
        original_request = native.request
        threads = [
            {"id": "assigned", "cwd": "/workspace", "projectId": "project"},
            {"id": "legacy", "cwd": "/workspace", "projectId": None},
            {"id": "other-project", "cwd": "/workspace", "projectId": "other"},
            {"id": "worktree", "cwd": "/worktree", "projectId": "project"},
        ]

        def request(method, params, **kwargs):
            if method == "project/list":
                return {"data": [{"id": "project", "name": "Project",
                                  "roots": [{"path": "/workspace"}]}], "nextCursor": None}
            if method == "thread/list":
                data = threads
                if "cwd" in params:
                    data = [t for t in data if t["cwd"] in params["cwd"]]
                if "projectId" in params:
                    data = [t for t in data if t["projectId"] == params["projectId"]]
                return {"data": copy.deepcopy(data), "nextCursor": None}
            return original_request(method, params, **kwargs)

        native.request = request
        service = CodexService(connection=native)
        sessions = service.list_sessions("project")
        self.assertEqual({"assigned", "legacy", "worktree"}, {t["id"] for t in sessions})
        self.assertEqual(3, len(sessions))
        self.assertEqual(len(sessions), service.list_projects()[0]["threadCount"])

    def test_captured_native_history_preserves_ids_and_chronological_order(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/native-0.159.0.json").read_text())
        native = FakeNative()
        native.request = lambda *args, **kwargs: copy.deepcopy(fixture["historyResponse"])
        page = NativeHistoryAdapter().read_page(native, fixture["threadId"], limit=100)
        self.assertEqual(list(reversed([t["id"] for t in fixture["historyResponse"]["data"]])),
                         [t["id"] for t in page.turns])
        self.assertEqual("PASS", page.turns[-1]["items"][-1]["text"])

    def test_structured_inputs_use_native_array_without_text_wrappers(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/native-structured-input-0.159.0.json").read_text())
        native = FakeNative()
        service = CodexService(connection=native)
        service.send("thread", "", input_items=fixture["input"], client_message_id="image-submission")
        params = next(params for method, params in native.calls if method == "turn/start")
        self.assertEqual(fixture["input"], params["input"])
        self.assertEqual("image-submission", params["clientUserMessageId"])
        self.assertFalse({"sandboxPolicy", "permissions", "approvalPolicy"} & params.keys())

    def test_start_and_first_send_preserve_native_settings_and_connection(self):
        native = FakeNative()
        service = CodexService(connection=native)
        self.assertEqual({"threadId": "thread"}, service.create_session("project"))
        self.assertEqual([], service.get_session("thread")["thread"]["turns"])
        receipt = service.send("thread", "hello", client_message_id="stable-client-id")
        self.assertEqual({"threadId": "thread", "turnId": "turn"}, receipt)
        self.assertNotIn("thread/resume", [method for method, _ in native.calls])
        for _, params in native.calls:
            self.assertFalse({"permissions", "approvalPolicy", "sandbox", "sandboxPolicy"} & params.keys())
        sent = next(params for method, params in native.calls if method == "turn/start")
        self.assertEqual("stable-client-id", sent["clientUserMessageId"])
        service.get_session("thread")
        self.assertNotIn("thread/resume", [method for method, _ in native.calls])
        read = next(params for method, params in native.calls if method == "thread/read")
        self.assertEqual({"threadId": "thread", "includeTurns": False}, read)

    def test_repeated_snapshots_join_once_and_do_not_emit_resume_refresh_loop(self):
        native = FakeNative()
        original_request = native.request
        events = []
        service = CodexService(connection=native, on_event=events.append)

        def request(method, params, **kwargs):
            response = original_request(method, params, **kwargs)
            if method == "thread/resume":
                service._event({"method": "thread/goal/cleared", "params": {"threadId": "thread"}})
            return response

        native.request = request
        for _ in range(3):
            service.get_session("thread")
        methods = [method for method, _ in native.calls]
        self.assertEqual(1, methods.count("thread/resume"))
        self.assertEqual(2, methods.count("thread/read"))
        self.assertEqual(3, methods.count("thread/turns/list"))
        self.assertEqual(1, len(events))

    def test_native_settings_event_refreshes_snapshot_without_rejoining(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.get_session("thread")
        service._event({"method": "thread/settings/updated", "params": {
            "threadId": "thread", "threadSettings": {
                "model": "changed-native-model", "effort": "low",
                "approvalPolicy": "untrusted", "sandboxPolicy": {"type": "readOnly"},
                "cwd": "/native-worktree", "serviceTier": None,
            }}})
        settings = service.get_session("thread")["settings"]
        self.assertEqual("changed-native-model", settings["model"])
        self.assertEqual("low", settings["reasoningEffort"])
        self.assertEqual("untrusted", settings["approvalPolicy"])
        self.assertEqual({"type": "readOnly"}, settings["sandbox"])
        self.assertEqual("/native-worktree", settings["cwd"])
        self.assertEqual(1, [m for m, _ in native.calls].count("thread/resume"))

    def test_reconnect_and_unload_each_require_a_new_subscription(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.get_session("thread")
        service._event({"method": "thread/closed", "params": {"threadId": "thread"}})
        service.get_session("thread")
        native.generation = "new-connection"
        service.get_session("thread")
        service.get_session("thread")
        self.assertEqual(3, [m for m, _ in native.calls].count("thread/resume"))
        self.assertEqual(1, [m for m, _ in native.calls].count("thread/read"))

    def test_first_send_history_failure_is_not_replaced_with_empty_history(self):
        native = FakeNative()
        original_request = native.request
        service = CodexService(connection=native)
        service.create_session("project")
        service.send("thread", "hello", client_message_id="stable")

        def request(method, params, **kwargs):
            if method == "thread/turns/list":
                raise CodexError("history not yet materialized", code="native_rejected", http_status=409)
            return original_request(method, params, **kwargs)

        native.request = request
        with self.assertRaises(CodexError) as failed:
            service.get_session("thread")
        self.assertEqual(409, failed.exception.http_status)
        self.assertNotIn("thread", service._created)
        self.assertNotIn("thread/resume", [m for m, _ in native.calls])

    def test_request_ids_are_connection_bound_and_only_answered_once(self):
        native = FakeNative()
        pending = Interactions(native)
        event = {"id": 7, "method": "item/commandExecution/requestApproval",
                 "params": {"threadId": "thread", "turnId": "turn"}, "connectionId": native.generation}
        pending.receive(event)
        key = pending.list("thread")[0]["key"]
        pending.respond(key, {"decision": "decline"})
        with self.assertRaises(CodexError):
            pending.respond(key, {"decision": "accept"})
        self.assertEqual([(7, {"decision": "decline"})], native.replies)
        pending.receive(event)
        native.generation = "connection-two"
        with self.assertRaises(CodexError):
            pending.respond(key, {"decision": "accept"})

    def test_unknown_first_send_does_not_keep_claiming_thread_is_empty(self):
        native = FakeNative()
        original_request = native.request

        def request(method, params, **kwargs):
            if method == "turn/start":
                raise CodexError("lost receipt", request_sent=True)
            return original_request(method, params, **kwargs)

        native.request = request
        service = CodexService(connection=native)
        service.create_session("project")
        with self.assertRaises(CodexError):
            service.send("thread", "hello", client_message_id="stable")
        self.assertNotIn("thread", service._created)

    def test_other_client_resolution_invalidates_pending_request(self):
        native = FakeNative()
        pending = Interactions(native)
        pending.receive({"id": "r", "method": "item/tool/requestUserInput",
                         "params": {"threadId": "thread"}})
        pending.receive({"method": "serverRequest/resolved", "params": {"threadId": "thread", "requestId": "r"}})
        self.assertEqual([], pending.list("thread"))

    def test_desktop_tool_call_does_not_require_user_response(self):
        native = FakeNative()
        service = CodexService(connection=native)
        service.start()
        service._event({"method": "turn/started", "params": {
            "threadId": "thread", "turn": {"id": "turn", "status": "inProgress", "items": []}}})
        service._event({"id": "tool-rpc", "method": "item/tool/call", "params": {
            "threadId": "thread", "turnId": "turn", "callId": "call", "tool": "exec"}})
        snapshot = service.get_session("thread")
        self.assertEqual([], snapshot["requests"])
        self.assertEqual("running", snapshot["status"])
        self.assertEqual([], native.replies)

    def test_tool_calls_preserve_real_questions_and_unknown_requests(self):
        native = FakeNative()
        pending = Interactions(native)
        for request_id, method in [("question", "item/tool/requestUserInput"),
                                   ("extension", "extension/request"),
                                   ("tool", "item/tool/call")]:
            pending.receive({"id": request_id, "method": method,
                             "params": {"threadId": "thread", "turnId": "turn"}})
        self.assertEqual(["question", "extension"], [r["id"] for r in pending.list("thread")])
        pending.receive({"method": "serverRequest/resolved", "params": {"requestId": "tool"}})
        self.assertEqual(2, len(pending.list("thread")))
        self.assertEqual([], native.replies)

    def test_late_turn_completion_does_not_clear_new_active_turn(self):
        service = CodexService(connection=FakeNative())
        for tid in ["older", "newer"]:
            service._event({"method": "turn/started", "params": {"threadId": "thread", "turn": {"id": tid, "status": "inProgress", "items": []}}})
        service._event({"method": "turn/completed", "params": {"threadId": "thread", "turn": {"id": "older", "status": "completed", "items": []}}})
        self.assertEqual("newer", service._active["thread"])


class NativeTransportTests(unittest.TestCase):
    def test_rpc_rejection_is_definite_but_timeout_is_not(self):
        from websockets.sync.server import unix_serve

        def handle(socket):
            for raw in socket:
                message = json.loads(raw)
                if "id" not in message:
                    continue
                method = message["method"]
                if method == "timeout":
                    continue
                if method == "reject":
                    response = {"error": {"code": -32602, "message": "invalid native params"}}
                elif method == "unsupported":
                    response = {"error": {"code": -32600, "message": "Invalid request: unknown variant `unsupported`, expected one of initialize"}}
                else:
                    response = {"result": {"process": {"id": 123}} if method == "server/diagnostics" else {}}
                socket.send(json.dumps({"id": message["id"], **response}))

        with tempfile.TemporaryDirectory() as directory:
            socket_path = str(Path(directory) / "native.sock")
            with unix_serve(handle, socket_path) as server:
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()
                connection = NativeConnection(socket_path)
                try:
                    connection.start()
                    self.assertEqual(123, connection.status()["runtimeId"])
                    self.assertIsNone(connection.status()["desktopShared"])
                    with self.assertRaises(CodexError) as rejected:
                        connection.request("reject", {})
                    self.assertTrue(rejected.exception.definite)
                    with self.assertRaises(CodexError) as unsupported:
                        connection.request("unsupported", {})
                    self.assertEqual("native_method_unsupported", unsupported.exception.code)
                    self.assertEqual(501, unsupported.exception.http_status)
                    with self.assertRaises(CodexError) as uncertain:
                        connection.request("timeout", {}, timeout=.02)
                    self.assertFalse(uncertain.exception.definite)
                finally:
                    connection.close()
                    server.shutdown()
                    worker.join(timeout=2)
                self.assertFalse(connection._dispatcher.is_alive())
                self.assertEqual(set(), connection._readers)
                self.assertFalse(connection.status()["connected"])
                self.assertFalse(worker.is_alive())


class NativeFileTests(unittest.TestCase):
    def test_phone_attachment_writes_native_workspace_and_keeps_bytes(self):
        native = FakeNative()
        payload = base64.b64encode(b"%PDF-1.7\x00binary").decode()
        first = upload_attachment(native, "/remote/worktree", "报告.pdf", payload)
        second = upload_attachment(native, "/remote/worktree", "报告.pdf", payload)
        self.assertEqual("mention", first["type"])
        self.assertTrue(first["path"].startswith("/remote/worktree/.rivo-attachments/"))
        self.assertNotEqual(first["path"], second["path"])
        self.assertEqual(("fs/writeFile", {"path": first["path"], "dataBase64": payload}), native.calls[1])
        windows = upload_attachment(native, "C:\\work", "a.txt", payload)
        self.assertTrue(windows["path"].startswith("C:\\work\\.rivo-attachments\\"))

    def test_transfer_above_preview_limit_preserves_binary(self):
        native = FakeNative()
        data = b"\x00\xff" * (3 * 1024 * 1024)
        encoded = base64.b64encode(data).decode()
        result = upload_attachment(native, "/remote", "large.zip", encoded)
        self.assertEqual(len(data), result["size"])
        def request(method, params, **kwargs):
            if method == "fs/getMetadata": return {"isFile": True}
            if params['command'][2].startswith('wc'): return {"exitCode": 0, "stdout": str(len(data))}
            offset = int(params['command'][-1]) * 4 * 1024 * 1024
            return {"exitCode": 0, "stdout": base64.b64encode(data[offset:offset + 4 * 1024 * 1024]).decode()}
        native.request = request
        size, chunks = stream_download(native, "/remote/large.zip")
        self.assertEqual(len(data), size)
        self.assertEqual(data, b''.join(chunks))
        with self.assertRaises(CodexError):
            read_file(native, "/remote/large.zip")

    def test_large_upload_uses_bounded_parts_and_default_native_permissions(self):
        native = FakeNative()
        calls = []
        def request(method, params, **kwargs):
            calls.append((method, params))
            return {"exitCode": 0} if method == "command/exec" else {}
        native.request = request
        result = upload_attachment(native, "/remote", "a; echo injected.zip", base64.b64encode(b"x" * (12 * 1024 * 1024)).decode())
        writes = [p for m, p in calls if m == "fs/writeFile"]
        self.assertEqual(3, len(writes))
        self.assertTrue(all(len(p["dataBase64"]) < 6 * 1024 * 1024 for p in writes))
        command = next(p for m, p in calls if m == "command/exec")
        self.assertNotIn("sandboxPolicy", command)
        self.assertNotIn("permissionProfile", command)
        self.assertEqual(result["path"], command["command"][4])
        self.assertNotIn("injected", command["command"][2])
        self.assertEqual("fs/remove", calls[-1][0])
        self.assertTrue(calls[-1][1]["path"].endswith("/.parts"))

    def test_gib_upload_never_reads_more_than_one_block(self):
        sizes = []
        class Input:
            def read(self, size):
                sizes.append(size)
                return b'x' * size
        native = FakeNative()
        writes = []
        def request(method, params, **kwargs):
            if method == 'fs/writeFile': writes.append(len(params['dataBase64']))
            return {'exitCode': 0} if method == 'command/exec' else {}
        native.request = request
        result = upload_attachment_stream(native, '/remote', 'large.bin', Input(), 1024**3)
        self.assertEqual(1024**3, result['size'])
        self.assertEqual(256, len(sizes))
        self.assertEqual(4 * 1024**2, max(sizes))
        self.assertLess(max(writes), 6 * 1024**2)
        with self.assertRaises(CodexError):
            upload_attachment_stream(native, '/remote', 'too-big.bin', Input(), 1024**3 + 1)

    def test_invalid_attachments_never_write(self):
        native = FakeNative()
        for name, payload in [("../escape", "YQ=="), ("a", "!invalid"), ("bad\\path", "YQ==")]:
            with self.subTest(name=name):
                with self.assertRaises(CodexError):
                    upload_attachment(native, "/remote/worktree", name, payload)
        self.assertEqual([], native.calls)

    def test_remote_file_is_read_only_via_native_rpc(self):
        native = FakeNative()
        calls = []

        def request(method, params, **kwargs):
            calls.append((method, params))
            if method == "fs/getMetadata": return {"isFile": True}
            return {"exitCode": 0, "stdout": str(len("远端内容".encode())) if params['command'][2].startswith('wc') else base64.b64encode("远端内容".encode()).decode()}

        native.request = request
        result = read_file(native, "/remote-only/preview.txt")
        self.assertEqual("远端内容", result["text"])
        self.assertEqual(["fs/getMetadata", "command/exec", "command/exec"], [m for m, _ in calls])
        self.assertTrue(all('sandboxPolicy' not in p for _, p in calls))

    def test_large_file_and_unsupported_native_api_are_explicit(self):
        native = FakeNative()
        native.request = lambda method, params, **kwargs: ({"isFile": True} if method == "fs/getMetadata"
                                                else {"exitCode": 0, "stdout": "8"})
        with self.assertRaises(CodexError) as limit:
            read_file(native, "/remote-only/file", max_bytes=4)
        self.assertEqual("file_too_large", limit.exception.code)
        self.assertEqual(413, limit.exception.http_status)

        def unsupported(*args):
            raise CodexError("Method not found", native_code=-32601)

        native.request = unsupported
        with self.assertRaises(CodexError) as missing:
            read_directory(native, "/remote-only")
        self.assertEqual("native_fs_unsupported", missing.exception.code)
        self.assertEqual(501, missing.exception.http_status)


if __name__ == "__main__":
    unittest.main()
