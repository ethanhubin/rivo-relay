"""Probe-script safety/flow tests using real HTTP and a controlled native stub."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

from rivo_relay.api import Handler
from rivo_relay.app import RelayApp
from rivo_relay.config import Config
from rivo_relay.http_server import BoundedHTTPServer
from test_api import NativeStub


class CompletingNative(NativeStub):
    def __init__(self):
        super().__init__()
        self.events = None
        self.turns = {}

    def send(self, thread, text, **kwargs):
        receipt = super().send(thread, text, **kwargs)
        turn = {"id": receipt["turnId"], "status": "completed", "items": [
            {"id": "reply-1", "type": "agentMessage", "text": "RIVO_RELAY_VERIFY_OK"}]}
        self.turns.setdefault(thread, []).append(turn)
        self.events.publish({"method": "turn/completed", "params": {"threadId": thread, "turn": turn}})
        return receipt

    def history(self, thread, *, cursor=None, limit=30):
        return {"turns": self.turns.get(thread, []), "nextCursor": None, "backwardsCursor": None}


class VerifyScriptTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.native = CompletingNative()
        self.app = RelayApp(Config(workspace=self.root, codex_home=self.root / "unused-codex-home"), self.native)
        self.native.events = self.app.events
        self.server = BoundedHTTPServer(("127.0.0.1", 0), Handler, app=self.app, max_workers=4, max_streams=1)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        paired = self.app.pairing.exchange(self.app.pairing.create()["code"], "Isolated script test")
        self.token = paired["token"]
        self.connection_file = self.root / "connection.json"
        self.connection_file.write_text(json.dumps({"token": self.token, "deviceId": paired["connection_id"]}))
        self.script = Path(__file__).resolve().parents[2] / "scripts" / "verify-service.py"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.app.close()

    def run_script(self, *extra):
        result = subprocess.run([sys.executable, str(self.script), "--url",
            "http://127.0.0.1:" + str(self.server.server_address[1]), "--connection-file",
            str(self.connection_file), "--timeout", "3", *extra], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stderr, "")
        self.assertNotIn(self.token, result.stdout)
        self.assertNotIn(str(self.connection_file), result.stdout)
        self.assertNotIn("仅回复", result.stdout)
        return result.returncode, json.loads(result.stdout)

    def test_default_reads_only(self):
        code, result = self.run_script()
        self.assertEqual(code, 0)
        self.assertTrue(result["ok"])
        self.assertEqual(result["native_writes"], "skipped_without_explicit_project_id")
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])
        self.assertEqual(self.app.todos.list(), [])

    def test_explicit_project_runs_closed_loop_and_redacts_report(self):
        output = self.root / "report.json"
        code, result = self.run_script("--project-id", "project-1", "--out", str(output))
        self.assertEqual(code, 0, result)
        self.assertTrue(result["ok"])
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)
        self.assertEqual(self.native.created[0][0], "project-1")
        self.assertTrue(result["checks"]["sse_native_completion"])
        self.assertTrue(result["checks"]["same_submission_same_receipt_no_second_turn"])
        self.assertTrue(result["checks"]["todo_cleanup"])
        self.assertEqual(self.app.todos.list(), [])
        self.assertEqual(json.loads(output.read_text()), result)
        self.assertEqual(os.stat(output).st_mode & 0o777, 0o600)

    def test_missing_project_never_writes(self):
        code, result = self.run_script("--project-id", "project-not-found")
        self.assertEqual(code, 1)
        self.assertEqual(result["error"], "project_not_found")
        self.assertEqual(self.native.created, [])
        self.assertEqual(self.native.sent, [])

    def test_unknown_submission_stops_without_retry_or_todo_mutation(self):
        self.native.send_error = TimeoutError("sensitive synthetic internal diagnostics")
        code, result = self.run_script("--project-id", "project-1")
        self.assertEqual(code, 1)
        self.assertEqual(result["error"], "submission_unconfirmed_do_not_retry")
        self.assertEqual(len(self.native.created), 1)
        self.assertEqual(len(self.native.sent), 1)
        self.assertEqual(self.app.todos.list(), [])
        self.assertNotIn("sensitive", json.dumps(result))

    def test_existing_report_is_never_overwritten_or_followed_by_writes(self):
        output = self.root / "existing.json"
        output.write_text("keep existing report")
        code, result = self.run_script("--project-id", "project-1", "--out", str(output))
        self.assertEqual(code, 1)
        self.assertEqual(result["error"], "report_already_exists")
        self.assertEqual(output.read_text(), "keep existing report")
        self.assertEqual(self.native.read_count, 0)
        self.assertEqual(self.native.created, [])


if __name__ == "__main__":
    unittest.main()
