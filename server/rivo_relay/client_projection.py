"""Compact mobile transport views; canonical native history stays untouched."""
from __future__ import annotations

import hashlib
import json
import re


def _item(item: dict, *, thread_id=None, turn_id=None, compact_images=False, completed=False) -> dict:
    kind = str(item.get("type", "")).replace("_", "").lower()
    result = item.get("result")
    status = str(item.get("status") or "").casefold().replace("_", "").replace("-", "")
    image_complete = (not item.get("failure") and status not in {"failed", "error", "canceled", "cancelled", "inprogress", "running", "interrupted"}
                      and (completed or status in {"completed", "succeeded", "success"}))
    if (compact_images and image_complete and item.get("type") in {"imageGeneration", "ImageGeneration"} and isinstance(result, str) and 0 < len(result) <= 90 * 1024 * 1024 and result.strip()
            and all(isinstance(value, str) and 0 < len(value) <= 256 for value in (thread_id, turn_id, item.get("id")))):
        value = result.strip()
        # Native result is either a source or inline image bytes. Keep existing
        # URLs/paths; media registration has already retained inline bytes.
        if not value.startswith(("http://", "https://", "/", "\\")) and not re.match(r"^[A-Za-z]:[\\/]", value):
            identity = hashlib.sha256(json.dumps([thread_id, turn_id, item["id"]], separators=(",", ":")).encode()).hexdigest()
            return {**item, "result": f"/v1/media/library/{identity}/content", "mediaRef": True}
    if kind != "commandexecution":
        return item
    # Allowlist keeps command text, output, parsed actions and future bulky fields
    # out of the mobile history while preserving identity and execution state.
    return {key: item[key] for key in ("id", "type", "status", "exitCode", "durationMs") if key in item}


def _turn(turn: dict, *, thread_id=None, compact_images=False) -> dict:
    if not isinstance(turn.get("items"), list):
        return turn
    return {**turn, "items": [_item(item, thread_id=thread_id, turn_id=turn.get("id"), compact_images=compact_images, completed=turn.get("status") in {"completed", "interrupted", "failed"}) for item in turn["items"]]}


def client_history(payload: dict, *, compact_images=False) -> dict:
    result = dict(payload)
    if isinstance(payload.get("turns"), list):
        result["turns"] = [_turn(turn, thread_id=payload.get("id") or payload.get("threadId"), compact_images=compact_images) for turn in payload["turns"]]
    if isinstance(payload.get("thread"), dict):
        result["thread"] = client_history(payload["thread"], compact_images=compact_images)
    return result


def client_session(payload: dict, *, known_head_revision=None, limit=2, cursor=None, compact_images=False) -> dict:
    """Omit only an explicitly acknowledged two-turn head, never fresh control.

    nativeHeadRevision hashes the complete last two turns and native epochs.
    It cannot validate a larger page. No response cache or TTL is involved.
    """
    if (limit == 2 and cursor is None and known_head_revision
            and known_head_revision == payload.get("nativeHeadRevision")
            and payload.get("nativeSync", {}).get("state") == "ready"
            and isinstance(payload.get("thread", {}).get("turns"), list)
            and len(payload["thread"]["turns"]) <= 2):
        return {**payload, "historyUnchanged": True,
                "thread": {key: value for key, value in payload["thread"].items() if key != "turns"}}
    return client_history(payload, compact_images=compact_images)


def client_event(event: dict, *, compact_images=False) -> dict | None:
    # RPC requests (especially command approvals) must retain their full context.
    if "id" in event:
        return event
    method = event.get("method")
    if method == "item/commandExecution/outputDelta":
        return None
    params = event.get("params")
    if not isinstance(params, dict):
        return event
    if method in {"item/started", "item/completed"} and isinstance(params.get("item"), dict):
        return {**event, "params": {**params, "item": _item(params["item"], thread_id=params.get("threadId"), turn_id=params.get("turnId"), compact_images=compact_images, completed=method == "item/completed")}}
    if method in {"turn/started", "turn/completed"} and isinstance(params.get("turn"), dict):
        return {**event, "params": {**params, "turn": _turn(params["turn"], thread_id=params.get("threadId"), compact_images=compact_images)}}
    if method == "thread/started" and isinstance(params.get("thread"), dict):
        return {**event, "params": {**params, "thread": client_history(params["thread"], compact_images=compact_images)}}
    return event
