"""Compact mobile transport views; canonical native history stays untouched."""
from __future__ import annotations


def _item(item: dict) -> dict:
    if str(item.get("type", "")).replace("_", "").lower() != "commandexecution":
        return item
    # Allowlist keeps command text, output, parsed actions and future bulky fields
    # out of the mobile history while preserving identity and execution state.
    return {key: item[key] for key in ("id", "type", "status", "exitCode", "durationMs") if key in item}


def _turn(turn: dict) -> dict:
    if not isinstance(turn.get("items"), list):
        return turn
    return {**turn, "items": [_item(item) for item in turn["items"]]}


def client_history(payload: dict) -> dict:
    result = dict(payload)
    if isinstance(payload.get("turns"), list):
        result["turns"] = [_turn(turn) for turn in payload["turns"]]
    if isinstance(payload.get("thread"), dict):
        result["thread"] = client_history(payload["thread"])
    return result


def client_session(payload: dict, *, known_head_revision=None, limit=2, cursor=None) -> dict:
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
    return client_history(payload)


def client_event(event: dict) -> dict | None:
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
        return {**event, "params": {**params, "item": _item(params["item"])}}
    if method in {"turn/started", "turn/completed"} and isinstance(params.get("turn"), dict):
        return {**event, "params": {**params, "turn": _turn(params["turn"])}}
    if method == "thread/started" and isinstance(params.get("thread"), dict):
        return {**event, "params": {**params, "thread": client_history(params["thread"])}}
    return event
