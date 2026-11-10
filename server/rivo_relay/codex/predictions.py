"""Best-effort official composer predictions; never acquire a thread writer."""
import json
from urllib.request import Request, urlopen


def predict_message(service, thread_id, turn_id):
    empty = {'sourceTurnId': turn_id, 'text': None}
    service._assert_workspace(thread_id)
    # Serialize duplicate requests and keep only a small process-local cache.
    with service._prediction_lock:
        key = (thread_id, turn_id)
        result = empty
        try:
            snapshot = service.get_session(thread_id)
            turns = snapshot['thread']['turns']
            if snapshot.get('activeTurnId') or not turns or turns[-1]['id'] != turn_id:
                return empty
            if key in service._prediction_cache: return service._prediction_cache[key]
            if snapshot.get('settings', {}).get('collaborationMode', {}).get('mode') == 'plan':
                return empty
            auth = json.loads((service.writers.home / 'auth.json').read_text())['tokens']
            headers = {'Authorization': 'Bearer ' + auth['access_token']}
            if auth.get('account_id'): headers['ChatGPT-Account-Id'] = auth['account_id']
            request = Request('https://chatgpt.com/backend-api/wham/predictions/config', headers=headers)
            with urlopen(request, timeout=10) as response:
                config = json.load(response)
            if config.get('is_enabled') and snapshot.get('settings', {}).get('model') not in config.get('unsupported_models', []):
                sampled = service.connection.sample_prediction({
                    'threadId': thread_id, 'expectedTurnId': turn_id,
                    'prompt': config['prompt'] + '\n\nReturn only JSON matching this schema: {"type":"object","properties":{"suggestion":{"type":["string","null"]}},"required":["suggestion"],"additionalProperties":false}.', 'effort': config.get('prediction_reasoning_effort') or None,
                    'threadSource': 'composer_predictions', 'turnTrigger': 'composer_predictions',
                    'disableMcp': True})
                text = json.loads(sampled.get('text') or '{}').get('suggestion')
                if isinstance(text, str) and text.strip(): result = dict(empty, text=text.strip())
        except Exception:
            # Optional prediction failure must not affect normal conversation.
            pass
        service._prediction_cache[key] = result
        if len(service._prediction_cache) > 64:
            del service._prediction_cache[next(iter(service._prediction_cache))]
        return result
