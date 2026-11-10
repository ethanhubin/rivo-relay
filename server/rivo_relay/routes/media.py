"""Authenticated JSON routes for media resources."""

from .common import ApiError, required


def route(handler, path, query, body, connection):
    method = handler.command
    connection_id = connection["connection_id"]
    if path == ['v1', 'media', 'capabilities'] and method == 'GET':
        return handler.app.media_capabilities()
    if path == ['v1', 'speech', 'streams'] and method == 'POST':
        return handler.app.speech.start(connection_id, body.get('context', ''))
    if len(path) == 5 and path[:3] == ['v1', 'speech', 'streams'] and path[4] in ('finish', 'cancel') and method == 'POST':
        return handler.app.speech.stream(connection_id, path[3], path[4])
    if path in (['v1', 'media', 'library'], ['v1', 'media', 'generated']) and method == 'GET':
        return {'items': handler.app.media.list(thread_id=query.get('threadId', [None])[0],
            favorite=query.get('favorite', ['false'])[0] == 'true', generated=path[-1] == 'generated', source=query.get('source', [None])[0])}
    if path == ['v1', 'media', 'library'] and method == 'POST':
        return handler.app.media.import_source(required(body, 'source', 8192), body.get('threadId'))
    if len(path) in (4, 5) and path[:3] in (['v1', 'media', 'library'], ['v1', 'media', 'generated']):
        if method == 'GET' and (len(path) == 4 or path[4] == 'provenance'): return handler.app.media.info(path[3])
        if method == 'PATCH' and len(path) == 4: return handler.app.media.favorite(path[3], body.get('favorite'))
        if method == 'DELETE' and len(path) == 4: return handler.app.media.delete(path[3])
    raise ApiError(404, "not_found", "Unknown endpoint")
