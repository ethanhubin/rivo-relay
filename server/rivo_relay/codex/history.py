"""Strict Codex app-server history access, migrated from QiGuang.

This module deliberately knows nothing about QiGuang tasks, events, prompts, or
terminal receipts.  A page returned from here is therefore safe to use as the
canonical history input: it is a validated view of app-server data, not a merge
with the sidecar database.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from typing import Any, Optional, Protocol


class AppServerRequester(Protocol):
    def request(self, method: str, params: dict, *, timeout: float = 30.0) -> dict:
        ...


class NativeHistoryProtocolError(RuntimeError):
    """The configured app-server does not satisfy the pinned history contract."""


@dataclass(frozen=True)
class NativeTurnPage:
    """One chronological page backed only by app-server persisted history."""

    turns: tuple[dict, ...]
    next_cursor: Optional[str]
    backwards_cursor: Optional[str]


class NativeHistoryAdapter:
    """Versioned boundary around the experimental persisted-history RPCs."""

    protocol_version = 1
    supported_item_types = frozenset(
        {
            "agentMessage",
            "collabAgentToolCall",
            "collabToolCall",
            "commandExecution",
            "contextCompaction",
            "dynamicToolCall",
            "enteredReviewMode",
            "exitedReviewMode",
            "fileChange",
            "functionCallOutput",
            "hookPrompt",
            "imageGeneration",
            "imageView",
            "mcpToolCall",
            "plan",
            "reasoning",
            "sleep",
            "subAgentActivity",
            "userMessage",
            "webSearch",
        }
    )
    supported_turn_statuses = frozenset(
        {"notStarted", "inProgress", "completed", "interrupted", "failed", "canceled"}
    )

    def __init__(self, instance_id: Optional[str] = None) -> None:
        self.instance_id = instance_id or uuid.uuid4().hex

    def read_page(
        self,
        connection: AppServerRequester,
        thread_id: str,
        *,
        limit: int,
        cursor: Optional[str] = None,
    ) -> NativeTurnPage:
        result = connection.request(
            "thread/turns/list",
            {
                "threadId": thread_id,
                "cursor": cursor,
                "limit": limit,
                "sortDirection": "desc",
                "itemsView": "full",
            },
            timeout=60,
        )
        if not isinstance(result, dict) or not isinstance(result.get("data"), list):
            raise NativeHistoryProtocolError(
                "thread/turns/list returned an invalid page envelope"
            )

        newest_first = [
            self._validated_turn(raw_turn, turn_index)
            for turn_index, raw_turn in enumerate(result["data"])
        ]
        self._validate_page_identity(newest_first)

        next_cursor = self._cursor(result, "nextCursor")
        backwards_cursor = self._cursor(result, "backwardsCursor")
        if cursor and next_cursor == cursor:
            raise NativeHistoryProtocolError(
                "thread/turns/list returned a non-advancing nextCursor"
            )

        newest_first.reverse()
        return NativeTurnPage(
            turns=tuple(newest_first),
            next_cursor=next_cursor,
            backwards_cursor=backwards_cursor,
        )

    def native_revision(
        self,
        thread: dict,
        head_turns: tuple[dict, ...] | list[dict],
        *,
        repair_epoch: int = 0,
    ) -> str:
        """Return an opaque equality token for one native history snapshot.

        The daemon instance id intentionally invalidates pagination after a
        restart.  Sidecar event/control counters are excluded: only the native
        thread head and an explicit native-repair epoch can change this token.
        """

        head = [
            {
                "id": turn["id"],
                "status": turn["status"],
                "items": [item["id"] for item in turn["items"]],
            }
            for turn in head_turns[-2:]
        ]
        payload = {
            "adapter": self.protocol_version,
            "instance": self.instance_id,
            "thread": str(thread.get("id") or ""),
            "updated_at": thread.get("updatedAt"),
            "head": head,
            "repair_epoch": int(repair_epoch),
        }
        encoded = json.dumps(
            payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def _cursor(result: dict, field: str) -> Optional[str]:
        value = result.get(field)
        if value is None:
            return None
        if not isinstance(value, str) or not value.strip():
            raise NativeHistoryProtocolError(f"{field} must be null or a non-empty string")
        return value

    @classmethod
    def _validated_turn(cls, raw_turn: Any, turn_index: int) -> dict:
        if not isinstance(raw_turn, dict):
            raise NativeHistoryProtocolError(f"turn {turn_index} is not an object")
        turn_id = cls._required_string(raw_turn, "id", f"turn {turn_index}")
        status = cls._required_string(raw_turn, "status", f"turn {turn_id}")
        if status not in cls.supported_turn_statuses:
            raise NativeHistoryProtocolError(
                f"turn {turn_id} has unsupported status {status}"
            )
        raw_items = raw_turn.get("items")
        if not isinstance(raw_items, list):
            raise NativeHistoryProtocolError(f"turn {turn_id} does not contain full items")

        items: list[dict] = []
        item_ids: set[str] = set()
        for item_index, raw_item in enumerate(raw_items):
            if not isinstance(raw_item, dict):
                raise NativeHistoryProtocolError(
                    f"turn {turn_id} item {item_index} is not an object"
                )
            item_id = cls._required_string(
                raw_item, "id", f"turn {turn_id} item {item_index}"
            )
            item_type = cls._required_string(raw_item, "type", f"item {item_id}")
            if item_type not in cls.supported_item_types:
                raise NativeHistoryProtocolError(
                    f"item {item_id} has unsupported type {item_type}"
                )
            if item_id in item_ids:
                raise NativeHistoryProtocolError(
                    f"turn {turn_id} contains duplicate item id {item_id}"
                )
            item_ids.add(item_id)
            items.append(dict(raw_item))

        turn = dict(raw_turn)
        turn["id"] = turn_id
        turn["status"] = status
        turn["items"] = items
        return turn

    @staticmethod
    def _validate_page_identity(turns: list[dict]) -> None:
        turn_ids: set[str] = set()
        item_ids: set[str] = set()
        for turn in turns:
            if turn["id"] in turn_ids:
                raise NativeHistoryProtocolError(
                    f"history page contains duplicate turn id {turn['id']}"
                )
            turn_ids.add(turn["id"])
            for item in turn["items"]:
                if item["id"] in item_ids:
                    raise NativeHistoryProtocolError(
                        f"history page contains duplicate item id {item['id']}"
                    )
                item_ids.add(item["id"])

    @staticmethod
    def _required_string(value: dict, field: str, owner: str) -> str:
        raw = value.get(field)
        if not isinstance(raw, str) or not raw.strip():
            raise NativeHistoryProtocolError(f"{owner} has invalid {field}")
        return raw.strip()
