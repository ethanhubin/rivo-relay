from __future__ import annotations

import os

from collections.abc import Callable
import threading
import shlex
from .todo_completion import TodoCompletion

from .config import Config
from .queue import ExecutionQueue
from .database import Database
from .events import EventBus
from .client_projection import client_event
from .pairing import PairingStore
from .submissions import SubmissionStore, SubmissionConflict, SubmissionNotFound
from .todos import TodoStore
from .media import MediaService
from .speech import SpeechService
from .node_name import NodeName


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
        from .update_gate import UpdateGate
        self.update_gate = UpdateGate()
        from .presence import Presence
        self.presence = Presence()
        self.node_name = NodeName()
        self.codex = codex
        self.events = events or EventBus()
        self.db = Database(config.database_path)
        if hasattr(codex, 'configure_storage'):
            codex.configure_storage(self.db)
        self.pairing = PairingStore(self.db)
        self.submissions = SubmissionStore(self.db)
        self.todos = TodoStore(self.db)
        self.todo_completion = TodoCompletion(self.db, self.todos, self.events)
        self.queue = ExecutionQueue(self.db, self.codex, self.events)
        self.download_links = {}
        self.download_lock = threading.Lock()
        self.file_transfer_slots = threading.BoundedSemaphore(2)
        self.media = MediaService(config.workspace, codex)
        self.speech = SpeechService(workspace=config.workspace)
        if hasattr(codex, 'on_event'):
            native_event = codex.on_event
            def media_event(event):
                if event.get('method') in {'turn/completed', 'relay/writerDisconnected', 'relay/ownershipChanged', 'serverRequest/resolved'}:
                    self.queue.signal()
                try:
                    self.media.observe_event(event)
                    params = event.get('params') or {}
                    method = event.get('method')
                    if method in {'turn/started', 'turn/completed'} and isinstance(params.get('turn'), dict):
                        self.media.observe_thread({'id': params.get('threadId'), 'turns': [params['turn']]})
                    elif method == 'thread/started' and isinstance(params.get('thread'), dict):
                        self.media.observe_thread(params['thread'])
                    if method in {'item/completed', 'turn/started', 'turn/completed', 'thread/started'}:
                        event = client_event(event, compact_images=True)
                except Exception:
                    # A derived media receipt cannot block canonical event delivery.
                    pass
                native_event(event)
            codex.on_event = media_event
        self._todo_dispatch_lock = threading.RLock()
        # serve holds an exclusive file lock before constructing the app.
        self.submissions.recover_pending()

    def node(self) -> dict:
        native = self.codex.status()
        return {"id": self.config.node_id, "name": self.node_name.get(),
                "releaseCode": int(os.environ.get("RIVO_RELEASE_CODE", "0")),
                "connected": bool(native.get("connected")),
                "capabilities": {**native.get("capabilities", {}), **self.media_capabilities(),
                             "todoAttachmentUpload": True}, "runtime": native}

    def media_capabilities(self):
        return {'media': {'proxy': True, 'thumbnails': True, 'thumbnailProfile': 'max-1536-webp-q82-v2',
                          'generatedProvenance': True, 'library': True, 'favorites': True},
                'speech': self.speech.capabilities()}

    def submit(self, connection_id: str, submission_id: str, operation: str,
               target, payload: dict, action: Callable[[], dict]) -> dict:
        claim = self.submissions.claim(submission_id, connection_id, operation, target, payload)
        if not claim["should_dispatch"]:
            return public_submission(claim["submission"])
        try:
            from .submission_dispatch import observe_dispatch
            tracked = operation in {'thread.turns', 'thread.continue', 'thread.create', 'thread.fork'}
            local = operation in {'thread.queue.enqueue', 'thread.queue.remove', 'thread.queue.dismiss',
                                  'thread.queue.edit', 'thread.queue.reorder', 'thread.queue.recover'}
            if local:
                self.submissions.begin_local(submission_id)
            def observe(phase, receipt):
                if tracked:
                    self.submissions.record_dispatch(submission_id, phase, receipt)
                elif local and phase == 'committed':
                    self.submissions.mark_accepted(submission_id, receipt)
                elif operation == 'thread.queue.steer' and phase == 'checkpoint':
                    self.submissions.checkpoint(submission_id, receipt)
            with observe_dispatch(submission_id, target, observe):
                receipt = action()
            if not receipt:
                raise RuntimeError("Missing native identity receipt; reconcile before retry")
            record = self.submissions.mark_accepted(submission_id, receipt)
        except Exception as error:
            # Resolve a proven turn boundary first. For uninstrumented/sent
            # requests only an explicit definite rejection is safe to fail.
            current = self.submissions.get(submission_id)
            proven = current if current['status'] in {'accepted', 'failed'} else self.submissions.resolve_dispatch(submission_id)
            if proven is not None:
                record = proven
            elif getattr(error, "definite", False):
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
        submitted_payload = {**payload, "title": todo["title"], "notes": todo["notes"], "details": todo.get("details", {})}
        try:
            self.submissions.get(submission_id, connection_id)
        except SubmissionNotFound:
            pass
        else:
            claim = self.submissions.claim(submission_id, connection_id, "todo.dispatch", todo_id, submitted_payload)
            return public_submission(claim["submission"])
        if todo["completed"]:
            raise SubmissionConflict("Reopen the completed todo before dispatching it")
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
            details = todo.get('details', {})
            subtasks = details.get('subtasks', [])
            if subtasks:
                text += '\n\n事项：\n' + '\n'.join(('[x] ' if item['completed'] else '[ ] ') + item['title'] for item in subtasks)
            credential = self.todo_completion.issue({**todo, "thread_id": thread_id})
            host = f"[{self.config.host}]" if ":" in self.config.host else self.config.host
            base = (f"http://{host}:{self.config.port}" if not self.config.cert
                    else self.config.public_url.rstrip("/"))
            command = ("curl --fail --silent --show-error --connect-timeout 10 --max-time 30 -X POST "
                       + shlex.quote(base + "/v1/todo-completion")
                       + " -H " + shlex.quote("Authorization: Bearer " + credential)
                       + " -H 'Content-Type: application/json' --data '{}'")
            text += ("\n\n来源：Rivo 待办（ID：" + todo_id + "）。"
                     "仅当已验证待办目标全部实现且没有剩余事项，或用户明确确认此待办完成后，"
                     "才执行以下命令回写完成状态。部分完成、失败、无法验证或仍需用户操作时不得标记完成。"
                     "不要仅因本轮回复结束就标记完成。接口失败时说明原因，不要声称已同步。"
                     "凭证仅用于此待办，请勿在回复中展示。\n" + command)
            attachments = details.get('attachments', []) + payload.get('uploadedAttachments', [])
            if attachments:
                inputs = [{'type': 'text', 'text': text, 'text_elements': []}] + [{'type': 'mention', 'name': item['name'], 'path': item['path']} for item in attachments]
                receipt = self.codex.send(thread_id, text, client_message_id=submission_id, input_items=inputs)
            else:
                receipt = self.codex.send(thread_id, text, client_message_id=submission_id)
            return {**receipt, "threadId": thread_id, "todoId": todo_id}

        return self.submit(connection_id, submission_id, "todo.dispatch", todo_id,
                           submitted_payload, dispatch)

    def close(self):
        self.queue.close()
        self.media.close()
        self.events.close()
        self.codex.close()
        self.db.close()
