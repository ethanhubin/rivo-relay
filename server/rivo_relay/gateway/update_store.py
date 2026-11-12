"""Immutable VPS releases. Publishing is an administrator filesystem operation."""
import hashlib
import json
import re
from pathlib import Path

from ..compatibility import validate_app

MAX_PACKAGE = 256 * 1024 * 1024


def validate(item):
    if (not isinstance(item, dict) or item.get('schema') != 1
            or not isinstance(item.get('notes'), str) or len(item['notes']) > 16000
            or type(item.get('versionCode')) is not int or item['versionCode'] <= 0
            or not re.fullmatch(r'\d+\.\d+\.\d+', str(item.get('version', '')))):
        raise ValueError('Invalid release manifest')
    if 'compatibility' in item:
        validate_app(item['compatibility'])
    for kind, suffix in [('app', 'apk'), ('node', 'zip')]:
        asset = item[kind]
        if (not isinstance(asset, dict) or not re.fullmatch('[a-f0-9]{64}', str(asset.get('sha256', '')))
                or type(asset.get('size')) is not int or not 0 < asset['size'] <= MAX_PACKAGE
                or asset.get('path') != f"/v1/releases/files/{asset['sha256']}.{suffix}"):
            raise ValueError('Invalid release asset')
    if item['node'].get('agentProtocol') != 1:
        raise ValueError('Unsupported update agent protocol')
    return item


def manifest(workspace):
    path = Path(workspace) / 'releases' / 'latest.json'
    if path.stat().st_size > 65536:
        raise ValueError('Manifest too large')
    return validate(json.loads(path.read_text()))


def artifact(workspace, name):
    if not re.fullmatch(r'[a-f0-9]{64}\.(apk|zip)', name):
        raise FileNotFoundError(name)
    path = Path(workspace) / 'releases' / 'files' / name
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_PACKAGE:
        raise FileNotFoundError(name)
    return path


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


async def serve(request):
    from aiohttp import web
    from .download_transport import UpdateFileResponse
    workspace = request.app['release_workspace']
    try:
        if 'name' in request.match_info:
            return UpdateFileResponse(artifact(workspace, request.match_info['name']), headers={'Cache-Control': 'public, max-age=31536000, immutable'})
        item = manifest(workspace)
        if request.path.endswith('/app-updates/latest'):
            app = item['app']
            item = {k: item[k] for k in ('version', 'versionCode', 'notes')} | {
                **({'compatibility': item['compatibility']} if 'compatibility' in item else {}),
                'sha256': app['sha256'], 'size': app['size'],
                'apkPath': '/v1/app-updates/apks/' + app['sha256'] + '.apk'}
        return web.json_response(item, headers={'Cache-Control': 'no-store'})
    except FileNotFoundError:
        raise web.HTTPNotFound(text='No published update')
    except (ValueError, KeyError, TypeError):
        raise web.HTTPServiceUnavailable(text='Invalid update publication')


def routes(app, workspace):
    app['release_workspace'] = workspace
    app.router.add_get('/v1/releases/latest', serve)
    app.router.add_get('/v1/releases/files/{name}', serve)
    app.router.add_get('/v1/app-updates/latest', serve)
    app.router.add_get('/v1/app-updates/apks/{name}', serve)
