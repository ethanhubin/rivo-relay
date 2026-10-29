from __future__ import annotations

from collections.abc import Callable
import threading

from .config import Config
from .queue import ExecutionQueue
from .database import Database
from .events import EventBus
from .pairing import PairingStore
from .submissions import SubmissionStore, SubmissionConflict, SubmissionNotFound
from .todos import TodoStore


def public_submission(record: dict) -> dict:
    return {"submissionId": record["id"], "status": record["status"],
            "receipt": record.get("receipt"), "errorCode": record.get("error_code")}


def public_todo(record: dict) -> dict:
    names = {"project_id": "projectId", "thread_id": "threadId",
             "created_at": "createdAt", "updated_at": "updatedAt"}
    return {names.get(key, key): value for key, value in record.items()}


class RelayApp:
    def __init__(self, config: Config, codex, events: EventBus | None = None):
        config.prepare()
        self.config = config
        self.codex = codex
        self.events = events or EventBus()
        self.db = Database(config.database_path)
        self.pairing = PairingStore(self.db)
        self.submissions = SubmissionStore(self.db)
        self.todos = TodoStore(self.db)
        self.queue = ExecutionQueue(self.db, self.codex, self.events)
        self.download_links = {}
        self.download_lock = threading.Lock()
        self.file_transfer_slots = threading.BoundedSemaphore(2)
        self._todo_dispatch_lock = threading.RLock()
        # serve holds an exclusive file lock before constructing the app.
        self.submissions.recover_pending()

    def node(self) -> dict:
        native = self.codex.status()
        return {"id": self.config.node_id, "name": self.config.name,
                "connected": bool(native.get("connected")),
                "capabilities": native.get("capabilities", {}), "runtime": native}

    def submit(self, connection_id: str, submission_id: str, operation: str,
               target, payload: dict, action: Callable[[], dict]) -> dict:
        claim = self.submissions.claim(submission_id, connection_id, operation, target, payload)
        if not claim["should_dispatch"]:
            return public_submission(claim["submission"])
        try:
            receipt = action()
            if not receipt:
                raise RuntimeError("Missing native identity receipt; reconcile before retry")
            record = self.submissions.mark_accepted(submission_id, receipt)
        except Exception as error:
            # A transport failure can occur after native acceptance. Only a
            # native rejection explicitly classified definite is safe to fail.
            if getattr(error, "definite", False):
                record = self.submissions.mark_failed(submission_id, getattr(error, "code", "native_rejected"))
            else:
                record = self.submissions.mark_unknown(submission_id)
        self.events.publish({"method": "relay/submission", "params": public_submission(record)})
        return public_submission(record)

    def dispatch_todo(self, todo_id: str, connection_id: str, payload: dict) -> dict:
        with self._todo_dispatch_lock:
            return self._dispatch_todo_locked(todo_id, connection_id, payload)

    def _dispatch_todo_locked(self, todo_id: str, connection_id: str, payload: dict) -> dict:
        todo = self.todos.get(todo_id)
        project_id = payload["projectId"]
        supplied_thread = payload.get("threadId")
        submission_id = payload["submissionId"]
        submitted_payload = {**payload, "title": todo["title"], "notes": todo["notes"]}
        try:
            self.submissions.get(submission_id, connection_id)
        except SubmissionNotFound:
            pass
        else:
            claim = self.submissions.claim(submission_id, connection_id, "todo.dispatch", todo_id, submitted_payload)
            return public_submission(claim["submission"])
        previous = self.submissions.find_for_target("todo.dispatch", todo_id)
        if todo.get("thread_id") or any(item["status"] != "failed" or item.get("receipt") for item in previous):
            raise SubmissionConflict("This todo already has a dispatch; continue its linked thread or reconcile it")
        if supplied_thread and not any(thread["id"] == supplied_thread for thread in self.codex.list_sessions(project_id)):
            raise SubmissionConflict("The selected thread does not belong to the selected project")

        def dispatch():
            thread_id = supplied_thread
            if not thread_id:
                receipt = self.codex.create_session(project_id, title=todo["title"])
                thread_id = receipt["threadId"]
                self.submissions.checkpoint(submission_id, {"threadId": thread_id})
            self.todos.link(todo_id, project_id, thread_id)
            text = todo["title"] + ("\n\n" + todo["notes"] if todo["notes"] else "")
            receipt = self.codex.send(thread_id, text, client_message_id=submission_id)
            return {**receipt, "threadId": thread_id, "todoId": todo_id}

        return self.submit(connection_id, submission_id, "todo.dispatch", todo_id,
                           submitted_payload, dispatch)

    def close(self):
        self.queue.close()
        self.events.close()
        self.codex.close()
        self.db.close()
