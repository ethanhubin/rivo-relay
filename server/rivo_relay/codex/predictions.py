"""Best-effort official predictions, isolated from foreground requests."""
import hashlib
import json
import time
from urllib.request import Request, urlopen

from ..read_budget import checkpoint, prediction_cancellation


def credentials(service):
    tokens = json.loads((service.writers.home / 'auth.json').read_text())['tokens']
    identity = hashlib.sha256(json.dumps(tokens, sort_keys=True).encode()).hexdigest()
    return tokens, identity


def predict_message(service, thread_id, turn_id):
    empty = {'sourceTurnId': turn_id, 'text': None}
    service._assert_workspace(thread_id)
    key = None
    claimed = False
    try:
        tokens, identity = credentials(service)
        snapshot = service.get_session(thread_id)
        turns = snapshot['thread']['turns']
        settings = snapshot.get('settings') or {}
        if (snapshot.get('activeTurnId') or not turns or turns[-1]['id'] != turn_id
                or (settings.get('collaborationMode') or {}).get('mode') == 'plan'
                or settings.get('modelProvider', 'openai') != 'openai'):
            return empty
        generation = service.connection.generation
        epoch = service._prediction_epochs.get(thread_id, 0)
        key = (identity, generation, thread_id, turn_id, json.dumps(settings, sort_keys=True))
        with service._prediction_lock:
            cached = service._prediction_cache.get(key)
            if cached and cached[0] > time.monotonic(): return cached[1]
            if key in service._prediction_inflight: return empty
            service._prediction_inflight.add(key)
            claimed = True

        def cancelled():
            try:
                return (service.connection.generation != generation
                        or service._prediction_epochs.get(thread_id, 0) != epoch
                        or credentials(service)[1] != identity)
            except Exception:
                return True

        with prediction_cancellation(cancelled):
            checkpoint()
            headers = {'Authorization': 'Bearer ' + tokens['access_token']}
            if tokens.get('account_id'): headers['ChatGPT-Account-Id'] = tokens['account_id']
            request = Request('https://chatgpt.com/backend-api/wham/predictions/config', headers=headers)
            with urlopen(request, timeout=10) as response:
                config = json.load(response)
            checkpoint()
            if not config.get('is_enabled') or settings.get('model') in (config.get('unsupported_models') or []):
                return empty
            sampled = service.connection.sample_prediction({
                'threadId': thread_id, 'expectedTurnId': turn_id,
                'prompt': config['prompt'] + '\n\nReturn only JSON matching this schema: {"type":"object","properties":{"suggestion":{"type":["string","null"]}},"required":["suggestion"],"additionalProperties":false}.',
                'effort': config.get('prediction_reasoning_effort') or None,
                'threadSource': 'composer_predictions', 'turnTrigger': 'composer_predictions',
                'interruptOnCancel': True, 'disableMcp': True})
            checkpoint()
            # Missing text means unavailable, not a successful empty prediction.
            if not isinstance(sampled.get('text'), str): return empty
            text = json.loads(sampled['text']).get('suggestion')
            if text is not None and not isinstance(text, str): return empty
            result = dict(empty, text=text.strip() or None) if isinstance(text, str) else empty
            with service._prediction_lock:
                service._prediction_cache[key] = (time.monotonic() + 300, result)
                if len(service._prediction_cache) > 64:
                    del service._prediction_cache[next(iter(service._prediction_cache))]
            return result
    except Exception:
        return empty
    finally:
        if claimed:
            with service._prediction_lock:
                service._prediction_inflight.discard(key)
