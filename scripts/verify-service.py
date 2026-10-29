#!/usr/bin/env python3
"""Explicit, isolated Relay acceptance probe; no implicit native writes or retries.

The connection file is the JSON response from POST /v1/pair (top-level token).
Without --project-id this performs authenticated reads only. With it, this
creates one native thread in that existing project, sends one fixed message,
and deliberately repeats its identical submission once to check deduplication.
The new thread remains available for Desktop review. No existing thread,
project, model, permission setting, or runtime is changed. A temporary todo is
created and removed. Never use this script against an unapproved project.
"""

import argparse
from collections import Counter
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import threading
import time
from urllib.parse import quote, urlencode, urlsplit
import uuid


PROMPT = "仅回复：RIVO_RELAY_VERIFY_OK。不要使用工具，不要读取或修改文件。"
MARKER = "RIVO_RELAY_VERIFY_OK"


class VerificationError(Exception):
    """Only fixed, non-sensitive codes are surfaced to the operator."""


def require(condition, code):
    if not condition:
        raise VerificationError(code)


class Client:
    def __init__(self, url, token, ca_file=None, timeout=120):
        self.url = urlsplit(url)
        require(self.url.scheme in {"http", "https"} and self.url.hostname
                and self.url.username is None and self.url.password is None
                and not self.url.query and not self.url.fragment, "invalid_url")
        self.port = self.url.port  # Reject invalid and out-of-range ports.
        require(self.port is None or self.port > 0, "invalid_port")
        if self.url.scheme == "http":
            try:
                local = ipaddress.ip_address(self.url.hostname).is_loopback
            except ValueError:
                local = self.url.hostname == "localhost"
            require(local or self.url.hostname == "10.0.2.2", "https_required")
        require(isinstance(token, str) and 0 < len(token) <= 4096
                and "\r" not in token and "\n" not in token, "invalid_connection_file")
        self.token, self.timeout = token, timeout
        self.context = ssl.create_default_context(cafile=str(ca_file) if ca_file else None)

    def connect(self):
        if self.url.scheme == "https":
            return http.client.HTTPSConnection(self.url.hostname, self.port,
                                               context=self.context, timeout=self.timeout)
        return http.client.HTTPConnection(self.url.hostname, self.port, timeout=self.timeout)

    def path(self, endpoint):
        return self.url.path.rstrip("/") + endpoint

    def headers(self):
        return {"Authorization": "Bearer " + self.token, "Accept": "application/json"}

    def request(self, method, endpoint, body=None):
        connection = self.connect()
        headers = self.headers()
        raw = None
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            connection.request(method, self.path(endpoint), body=raw, headers=headers)
            response = connection.getresponse()
            require(response.status == 200, "http_" + str(response.status))
            data = response.read(16 * 1024 * 1024 + 1)
            require(len(data) <= 16 * 1024 * 1024, "response_too_large")
            result = json.loads(data)
            require(isinstance(result, dict), "invalid_response")
            return result
        finally:
            connection.close()


class EventObserver:
    """Keep only event identities/counts, never event text or account details."""
    def __init__(self, client, thread_id):
        self.client, self.thread_id = client, thread_id
        self.ready = threading.Event()
        self.changed = threading.Event()
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.methods = Counter()
        self.completed = set()
        self.error = None
        self.connection = self.response = self.socket = None
        self.thread = threading.Thread(target=self._read, daemon=True)

    def start(self):
        self.thread.start()
        require(self.ready.wait(min(10, self.client.timeout)), "sse_not_ready")
        require(self.error is None, "sse_failed")

    def _receive(self, fields):
        if fields.get("event") == "reset":
            self.ready.set()
            return
        if fields.get("event") == "revoked":
            raise VerificationError("connection_revoked")
        if fields.get("event") != "native":
            return
        data = json.loads(fields.get("data", "{}"))
        method, params = data.get("method", ""), data.get("params", {})
        if not isinstance(params, dict) or params.get("threadId") != self.thread_id:
            return
        if not isinstance(method, str) or not method.startswith(("thread/", "turn/", "item/")):
            return
        with self.lock:
            self.methods[method] += 1
            turn = params.get("turn") or {}
            if method == "turn/completed" and isinstance(turn, dict) and turn.get("id"):
                self.completed.add(turn["id"])
        self.changed.set()

    def _read(self):
        try:
            self.connection = self.client.connect()
            self.connection.request("GET", self.client.path("/v1/events"), headers=self.client.headers())
            self.socket = self.connection.sock
            self.response = self.connection.getresponse()
            require(self.response.status == 200, "sse_http_failed")
            require(self.response.getheader("Content-Type", "").startswith("text/event-stream"), "sse_content_type")
            fields = {}
            while not self.stop.is_set():
                raw = self.response.readline(1024 * 1024 + 1)
                require(raw and len(raw) <= 1024 * 1024, "sse_closed")
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    self._receive(fields)
                    fields = {}
                elif not line.startswith(":"):
                    key, _, value = line.partition(":")
                    fields[key] = value.lstrip()
        except Exception:
            if not self.stop.is_set():
                self.error = "sse_failed"
                self.ready.set()
                self.changed.set()

    def close(self):
        self.stop.set()
        if self.socket is not None:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.thread.join(timeout=2)
        if self.response is not None:
            self.response.close()
        if self.connection is not None:
            self.connection.close()


def accepted(result, identity):
    require(result.get("status") == "accepted", "submission_unconfirmed_do_not_retry")
    receipt = result.get("receipt")
    require(isinstance(receipt, dict) and isinstance(receipt.get(identity), str)
            and receipt[identity], "missing_native_identity")
    return receipt


def history(client, thread_id):
    turns, cursor, seen = [], None, set()
    for _ in range(10):
        query = {"limit": 100}
        if cursor is not None:
            query["cursor"] = cursor
        result = client.request("GET", "/v1/threads/" + quote(thread_id, safe="")
                                + "/history?" + urlencode(query))
        require(isinstance(result.get("turns"), list), "invalid_history")
        turns.extend(result["turns"])
        cursor = result.get("nextCursor")
        if cursor is None:
            return turns
        require(isinstance(cursor, str) and cursor not in seen, "history_cursor_loop")
        seen.add(cursor)
    raise VerificationError("history_page_limit")


def verify_todos(client, report):
    todo_id = "verify-" + uuid.uuid4().hex
    path = "/v1/todos/" + todo_id
    created = False
    try:
        todo = client.request("POST", "/v1/todos", {"id": todo_id, "title": "Rivo verification", "notes": "Temporary acceptance fixture"})["todo"]
        created = True
        require(todo.get("id") == todo_id and not todo.get("completed"), "todo_create_mismatch")
        require(client.request("GET", path)["todo"]["id"] == todo_id, "todo_read_mismatch")
        todo = client.request("PATCH", path, {"title": "Rivo verification complete", "completed": True})["todo"]
        require(todo.get("completed") is True and todo.get("threadId") is None, "todo_update_mismatch")
        require(any(t.get("id") == todo_id for t in client.request("GET", "/v1/todos")["todos"]), "todo_list_mismatch")
        report["checks"]["todo_crud"] = True
    finally:
        # Also remove a todo if a response was lost after its creation. The ID
        # is random and belongs only to this probe; never delete an existing ID.
        try:
            result = client.request("DELETE", path)
            require(result.get("deleted") is True, "todo_delete_mismatch")
            report["checks"]["todo_cleanup"] = True
        except VerificationError as error:
            if str(error) != "http_404" or created:
                report["checks"]["todo_cleanup"] = False
                raise


def verify(client, project_id, report):
    require(client.request("GET", "/health").get("status") == "ok", "health_failed")
    node = client.request("GET", "/v1/node")
    projects = client.request("GET", "/v1/projects").get("projects")
    require(isinstance(projects, list), "invalid_projects")
    report["checks"].update(health=True, authenticated_read=True)
    report["project_count"] = len(projects)
    report["native_connected"] = node.get("connected") is True
    if project_id is None:
        report["native_writes"] = "skipped_without_explicit_project_id"
        return
    require(any(p.get("id") == project_id for p in projects), "project_not_found")
    require(node.get("connected") is True, "native_disconnected")
    report["project_fingerprint"] = hashlib.sha256(project_id.encode()).hexdigest()[:16]
    report["native_writes"] = "one_new_thread_one_fixed_message"
    create_id, send_id = "verify-" + uuid.uuid4().hex, "verify-" + uuid.uuid4().hex
    report["test_ids"] = {"createSubmissionId": create_id, "sendSubmissionId": send_id}
    receipt = accepted(client.request("POST", "/v1/threads", {
        "submissionId": create_id, "projectId": project_id}), "threadId")
    thread_id = receipt["threadId"]
    report["test_ids"]["threadId"] = thread_id
    report["checks"]["create_thread"] = True
    report["thread_retained_for_desktop_review"] = True
    observer = EventObserver(client, thread_id)
    try:
        observer.start()
        path = "/v1/threads/" + quote(thread_id, safe="") + "/turns"
        body = {"submissionId": send_id, "text": PROMPT}
        first = client.request("POST", path, body)
        receipt = accepted(first, "turnId")
        require(receipt.get("threadId") == thread_id, "turn_thread_mismatch")
        turn_id = receipt["turnId"]
        report["test_ids"]["turnId"] = turn_id
        report["checks"]["send_message"] = True
        deadline = time.monotonic() + client.timeout
        while True:
            turns = history(client, thread_id)
            matching = [t for t in turns if t.get("id") == turn_id]
            require(len(matching) <= 1, "duplicate_native_turn")
            if matching and matching[0].get("status") == "completed":
                with observer.lock:
                    if turn_id in observer.completed:
                        break
            if matching and matching[0].get("status") in {"failed", "interrupted", "canceled"}:
                raise VerificationError("native_turn_not_completed")
            require(observer.error is None, "sse_failed")
            require(time.monotonic() < deadline, "native_completion_timeout_no_retry")
            observer.changed.wait(min(0.5, max(0, deadline - time.monotonic())))
            observer.changed.clear()
        require(len(turns) == 1, "unexpected_turn_in_test_thread")
        require(any(item.get("type") == "agentMessage" and MARKER in item.get("text", "")
                    for item in matching[0].get("items", [])), "reply_marker_missing")
        report["checks"].update(sse_native_completion=True, native_history=True, reply_marker=True)
        # The only intentional repeated write: identical ID, target and payload.
        duplicate = client.request("POST", path, body)
        require(duplicate == first, "duplicate_receipt_changed")
        after = history(client, thread_id)
        require([t.get("id") for t in after] == [turn_id], "duplicate_created_another_turn")
        report["checks"]["same_submission_same_receipt_no_second_turn"] = True
        with observer.lock:
            report["native_event_count"] = sum(observer.methods.values())
        report["native_turn_count"] = len(after)
    finally:
        observer.close()
    verify_todos(client, report)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Relay base URL; TLS checked normally")
    parser.add_argument("--connection-file", required=True, type=Path, help="Private JSON pair response; never printed")
    parser.add_argument("--project-id", help="Explicit existing isolated project; enables one native test thread/message")
    parser.add_argument("--ca-file", type=Path, help="Trust this CA for this process only; no system store changes")
    parser.add_argument("--timeout", type=float, default=120, help="HTTP/completion timeout in seconds")
    parser.add_argument("--out", type=Path, help="Create a new mode-0600 redacted JSON report; refuses overwrite")
    args = parser.parse_args(argv)
    report = {"schema": 1, "ok": False, "checks": {}, "native_writes": "not_started"}
    try:
        require(0 < args.timeout <= 600, "invalid_timeout")
        require(args.project_id is None or bool(args.project_id.strip()), "invalid_project_id")
        require(args.out is None or not args.out.exists(), "report_already_exists")
        require(args.connection_file.stat().st_size <= 65536, "invalid_connection_file")
        credentials = json.loads(args.connection_file.read_text())
        require(isinstance(credentials, dict), "invalid_connection_file")
        client = Client(args.url, credentials.get("token"), args.ca_file, args.timeout)
        verify(client, args.project_id, report)
        report["ok"] = True
    except VerificationError as error:
        report["error"] = str(error)
    except ssl.SSLCertVerificationError:
        report["error"] = "tls_verification_failed"
    except (OSError, http.client.HTTPException, ValueError, KeyError, TypeError):
        # Do not output exception messages: URLs, paths, or server text may
        # contain private information. An uncertain write is never retried.
        report["error"] = "transport_or_input_error_no_retry"
    raw = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.out is not None:
        try:
            descriptor = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as output:
                output.write(raw)
        except OSError:
            report["ok"] = False
            report["report_error"] = "could_not_create_report"
            raw = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    print(raw, end="")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
