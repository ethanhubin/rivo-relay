"""Public, certificate-pinned release baseline; no node access token required."""
import asyncio
import hashlib
import json
from pathlib import Path


async def _fetch(root, cfg, download, node_only=False):
    import aiohttp
    from rivo_relay.gateway.update_store import validate, require_app_endpoint, PUBLICATION_CAPABILITIES
    connector = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(cfg['gatewayPin'])))
    async with aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=300)) as client:
        async with client.get(cfg['gatewayUrl'] + '/v1/releases/latest', allow_redirects=False) as response:
            if response.status != 200:
                raise RuntimeError('VPS 更新清单不可用')
            item = await response.json()
        validate(item)
        async with client.get(cfg['gatewayUrl'] + '/v1/app-updates/latest', allow_redirects=False) as response:
            if response.status != 200:
                raise RuntimeError('客户端更新接口不可用；先修复网关再发布')
            app_response = await response.json()
        require_app_endpoint(item, app_response)
        if node_only:
            async with client.get(cfg['gatewayUrl'] + '/v1/releases/capabilities', allow_redirects=False) as response:
                if response.status != 200 or await response.json() != PUBLICATION_CAPABILITIES:
                    raise RuntimeError('运行中的网关尚不支持独立客户端版本；先部署新版网关再发布节点')
        app = item['app']
        baseline = {k: item['app'].get(k, item[k]) for k in ('version', 'versionCode', 'notes')}
        baseline.update(nodeVersion=item['version'], nodeVersionCode=item['versionCode'])
        baseline['compatibility'] = item.get('compatibility')
        baseline.update({k: app[k] for k in ('sha256', 'size')})
        if not download:
            return baseline
        destination = Path(root) / 'workspace/local-node/updates'
        apk = destination / 'apks' / (app['sha256'] + '.apk')
        if not (apk.is_file() and apk.stat().st_size == app['size']
                and hashlib.sha256(apk.read_bytes()).hexdigest() == app['sha256']):
            async with client.get(cfg['gatewayUrl'] + app['path'], allow_redirects=False) as response:
                if response.status != 200:
                    raise RuntimeError('VPS 上版 APK 下载失败')
                data = bytearray()
                async for chunk in response.content.iter_chunked(65536):
                    data.extend(chunk)
                    if len(data) > app['size']:
                        raise RuntimeError('VPS 上版 APK 超出清单大小')
            if len(data) != app['size'] or hashlib.sha256(data).hexdigest() != app['sha256']:
                raise RuntimeError('VPS 上版 APK 校验失败')
            from .setup import atomic
            atomic(apk, data)
        from .setup import atomic
        atomic(destination / 'latest.json', (json.dumps(baseline, ensure_ascii=False, indent=2) + '\n').encode())
        return baseline


def baseline(root, cfg, download=False, node_only=False):
    return asyncio.run(_fetch(root, cfg, download, node_only))
