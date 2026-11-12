"""Node-local public update artifacts, independent of Codex and pairing credentials."""
import json
import re
from pathlib import Path

MAX_APK_BYTES = 256 * 1024 * 1024


def latest_update(workspace):
    root = Path(workspace) / 'updates'
    manifest = root / 'latest.json'
    if not manifest.is_file() or manifest.stat().st_size > 65536:
        raise FileNotFoundError('节点尚未发布安装包')
    item = json.loads(manifest.read_text())
    if (not re.fullmatch(r'\d+\.\d+\.\d+', str(item.get('version', '')))
            or type(item.get('versionCode')) is not int or item['versionCode'] <= 0
            or not re.fullmatch(r'[a-f0-9]{64}', str(item.get('sha256', '')))):
        raise ValueError('更新清单无效')
    apk = update_apk(workspace, item['sha256'] + '.apk')
    if apk.stat().st_size != item.get('size'):
        raise ValueError('更新包长度不匹配')
    return {key: item[key] for key in ('version', 'versionCode', 'sha256', 'size')} | {
        **({'compatibility': item['compatibility']} if 'compatibility' in item else {}),
        'notes': str(item.get('notes', ''))[:16000],
        'apkPath': '/v1/app-updates/apks/' + item['sha256'] + '.apk',
    }


def update_apk(workspace, name):
    if not re.fullmatch(r'[a-f0-9]{64}\.apk', name):
        raise FileNotFoundError('安装包不存在')
    path = Path(workspace) / 'updates' / 'apks' / name
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_APK_BYTES:
        raise FileNotFoundError('安装包不存在')
    return path
