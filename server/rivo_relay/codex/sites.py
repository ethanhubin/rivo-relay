"""Read-only Sites projection. Never expose MCP dispatch or credentials to clients."""
from urllib.parse import urlsplit
from contextlib import suppress
from .connection import CodexError


def public_url(value):
    if not isinstance(value, str) or len(value) > 8192 or any(ord(c) < 32 for c in value):
        return None
    try:
        url = urlsplit(value)
        return value if url.scheme in ('http', 'https') and url.hostname and not url.username and not url.password else None
    except ValueError:
        return None


def list_sites(connection, cursor=None):
    if cursor is not None and (not isinstance(cursor, str) or len(cursor) > 16384):
        raise ValueError('无效的站点分页游标')
    # The lease owns and closes its process, including the ephemeral thread.
    with connection.auxiliary_connection() as helper:
        thread = helper.request('thread/start', {'ephemeral': True, 'cwd': str(helper.codex_home)}, timeout=20)
        thread_id = thread['thread']['id']
        arguments = {'limit': 20}
        if cursor: arguments['cursor'] = cursor
        try:
            response = helper.request('mcpServer/tool/call', {'threadId': thread_id,
                'server': 'codex_apps', 'tool': 'sites.list_sites', 'arguments': arguments}, timeout=45)
        finally:
            # Explicit Desktop mode may lease a shared connection. Release only
            # this ephemeral subscription, never a user's existing thread.
            with suppress(Exception):
                helper.request('thread/unsubscribe', {'threadId': thread_id}, timeout=5)
        if response.get('isError'):
            raise CodexError('Sites 暂不可用，请检查节点的 ChatGPT 登录与 Sites 权限', code='sites_unavailable', http_status=503)
        data = response.get('structuredContent')
        if not isinstance(data, dict) or not isinstance(data.get('items'), list):
            raise CodexError('Sites 返回了无法识别的数据', code='sites_invalid_response', http_status=502)
        items = []
        for item in data['items']:
            if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id']: continue
            items.append({'id': item['id'], 'title': item.get('title') if isinstance(item.get('title'), str) else '未命名站点',
                'url': public_url(item.get('current_live_url')), 'accessMode': item.get('access_mode') if item.get('access_mode') in ('public', 'private', 'workspace', 'restricted') else None})
        next_cursor = data.get('cursor')
        return {'items': items, 'cursor': next_cursor if isinstance(next_cursor, str) and next_cursor else None}
